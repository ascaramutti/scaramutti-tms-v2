import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { ProductDetailPage } from './ProductDetailPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { KardexCaptureSink, UpdateCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  createWarehouseProductCategorySuccess,
  DEFAULT_PRODUCT_ETAG,
  fakeProduct,
  fakeProductCategory,
  updateWarehouseProductError,
  updateWarehouseProductSuccess,
  warehouseProductCategories,
  warehouseProductCategoriesError,
  warehouseProductDetail,
  warehouseProductDetailError,
  warehouseProductDetailSequence,
  warehouseProductDetailSlow,
  warehouseProductDetailWithoutEtag,
  warehouseProductKardexCapture,
  warehouseProductKardexEmpty,
  warehouseProductKardexError,
  warehouseProductKardexPagedByParam,
} from '../../../test/mocks/handlers/warehouse'

function renderDetalle(id = '1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[`/cotizaciones/almacen/productos/${id}`]}>
          <Routes>
            <Route path="/cotizaciones/almacen" element={<div>EXISTENCIAS</div>} />
            <Route
              path="/cotizaciones/almacen/productos/:id"
              element={<ProductDetailPage />}
            />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

async function openEditModal() {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: /editar/i }))
  return user
}

describe('ProductDetailPage', () => {
  // ----- Render de la ficha -----
  it('muestra el spinner mientras carga el producto', async () => {
    server.use(warehouseProductDetailSlow(fakeProduct(), 40))
    renderDetalle()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('renderiza la ficha del producto', async () => {
    renderDetalle()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      'Filtro de aceite XYZ',
    )
    expect(screen.getByText(/PRO-0001 · Filtros/)).toBeInTheDocument()
    expect(screen.getByText('Bosch')).toBeInTheDocument()
    expect(screen.getByText('F026407123')).toBeInTheDocument()
    expect(screen.getByText('UND · Unidad')).toBeInTheDocument()
  })

  it('muestra el stock actual y el mínimo con su unidad', async () => {
    server.use(warehouseProductDetail(fakeProduct({ stock: 12, minStock: 4 })))
    renderDetalle()
    // Acotado a la tarjeta: el kardex también muestra saldos con la misma unidad.
    const existencias = within(await screen.findByRole('region', { name: 'Existencias' }))
    expect(existencias.getByText('12 UND')).toBeInTheDocument()
    expect(existencias.getByText('Mínimo 4 UND')).toBeInTheDocument()
  })

  it('marca el stock bajo según el backend, sin recalcularlo', async () => {
    // Combinación imposible a propósito: la regla vive en la vista de stock.
    server.use(warehouseProductDetail(fakeProduct({ stock: 10, minStock: 4, lowStock: true })))
    renderDetalle()
    expect(await screen.findByText('Bajo')).toBeInTheDocument()
  })

  it('muestra un guion cuando no hay marca ni número de parte', async () => {
    server.use(warehouseProductDetail(fakeProduct({ brand: null, partNumber: null })))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getAllByText('—')).toHaveLength(2)
  })

  it('renderiza las características clave-valor', async () => {
    server.use(
      warehouseProductDetail(
        fakeProduct({ attributes: { rosca: '3/4-16', viscosidad: '15W-40' } }),
      ),
    )
    renderDetalle()
    expect(await screen.findByText('rosca')).toBeInTheDocument()
    expect(screen.getByText('3/4-16')).toBeInTheDocument()
    expect(screen.getByText('viscosidad')).toBeInTheDocument()
  })

  it('avisa cuando el producto no tiene características', async () => {
    renderDetalle()
    expect(await screen.findByText(/sin características registradas/i)).toBeInTheDocument()
  })

  it('muestra las observaciones cuando existen', async () => {
    server.use(warehouseProductDetail(fakeProduct({ observations: 'Comprar de a 10 unidades.' })))
    renderDetalle()
    expect(await screen.findByText('Comprar de a 10 unidades.')).toBeInTheDocument()
  })

  it('distingue un producto dado de baja', async () => {
    server.use(warehouseProductDetail(fakeProduct({ isActive: false })))
    renderDetalle()
    expect(await screen.findByText('Inactivo')).toBeInTheDocument()
  })

  it('muestra quién registró el producto', async () => {
    renderDetalle()
    expect(await screen.findByText(/Admin TMS · 20\/05\/2026/)).toBeInTheDocument()
  })

  it('muestra "no encontrado" ante un 404', async () => {
    server.use(warehouseProductDetailError(404))
    renderDetalle()
    expect(await screen.findByText(/no se encontró el producto/i)).toBeInTheDocument()
  })

  it('no le pide nada al backend si el id de la ruta no es un número', async () => {
    const requests: string[] = []
    const record = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', record)
    renderDetalle('abc')
    expect(await screen.findByText(/no se encontró el producto/i)).toBeInTheDocument()
    expect(requests.filter((url) => url.includes('/warehouse/products/'))).toHaveLength(0)
    // Solo el listener de este test: `removeAllListeners` se llevaría los del setup.
    server.events.removeListener('request:start', record)
  })

  it('muestra el error del backend y permite reintentar', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductDetailError(500, { detail: 'El servidor falló.' }))
    renderDetalle()
    // El detalle reintenta una vez antes de rendirse, así que tarda más que un fallo seco.
    expect(await screen.findByText('El servidor falló.', {}, { timeout: 3000 })).toBeInTheDocument()
    server.use(warehouseProductDetail())
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  // ----- Kardex integrado -----
  it('pide el kardex del producto de la ruta', async () => {
    const sink: KardexCaptureSink = {}
    server.use(warehouseProductKardexCapture(sink))
    renderDetalle('7')
    await screen.findByRole('heading', { level: 1 })
    await waitFor(() => expect(sink.productId).toBe('7'))
    expect(sink.params?.get('size')).toBe('10')
  })

  it('muestra los movimientos con su saldo corrido', async () => {
    renderDetalle()
    expect(await screen.findByText(/Retiro RET-0009/)).toBeInTheDocument()
    expect(screen.getByText('−4 UND')).toBeInTheDocument()
    expect(screen.getByText('Corte inicial', { selector: 'span' })).toBeInTheDocument()
  })

  it('muestra el estado vacío del kardex sin ocultar la ficha', async () => {
    server.use(warehouseProductKardexEmpty())
    renderDetalle()
    expect(await screen.findByText(/todavía no hay movimientos/i)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('si falla el kardex la ficha sigue en pantalla', async () => {
    server.use(warehouseProductKardexError(500, { detail: 'Falló el kardex.' }))
    renderDetalle()
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.getByText('Falló el kardex.')).toBeInTheDocument()
  })

  it('pagina el kardex', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductKardexPagedByParam(25, 10))
    renderDetalle()
    await screen.findByText('Movimiento P0')
    expect(screen.getByLabelText('Página anterior')).toBeDisabled()
    await user.click(screen.getByLabelText('Página siguiente'))
    expect(await screen.findByText('Movimiento P1')).toBeInTheDocument()
  })

  // ----- Edición -----
  it('abre el modal de edición con los datos del producto', async () => {
    renderDetalle()
    await openEditModal()
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: /editar producto/i })).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Nombre')).toHaveValue('Filtro de aceite XYZ')
    expect(within(dialog).getByLabelText('Marca')).toHaveValue('Bosch')
  })

  it('cancelar cierra el modal sin llamar al backend', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(updateWarehouseProductSuccess(sink))
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('la unidad de medida se muestra pero no se puede editar', async () => {
    renderDetalle()
    await openEditModal()
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByLabelText(/unidad de medida/i)).not.toBeInTheDocument()
    expect(within(dialog).getByText('UND · Unidad')).toBeInTheDocument()
    expect(within(dialog).getByText(/no se puede cambiar/i)).toBeInTheDocument()
  })

  it('manda el If-Match con el ETag del header, no con el updatedAt del body', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      // El body dice `.39289Z` y el header `.392890Z`: armar el If-Match desde el
      // body daría un 412 espurio.
      warehouseProductDetail(fakeProduct({ updatedAt: '2026-05-20T10:00:00.39289Z' })),
      updateWarehouseProductSuccess(sink),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.ifMatch).toBe(DEFAULT_PRODUCT_ETAG))
  })

  it('el PUT no envía la unidad de medida', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(updateWarehouseProductSuccess(sink))
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body).not.toHaveProperty('unitOfMeasureId')
  })

  it('editar no cambia el estado activo del producto', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseProductDetail(fakeProduct({ isActive: true })),
      updateWarehouseProductSuccess(sink),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.type(screen.getByLabelText('Nombre'), ' editado')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    // La baja se difirió: el PUT conserva el valor actual en vez de omitirlo (el
    // contrato lo define con default true, así que omitirlo REACTIVARÍA el producto).
    await waitFor(() => expect(sink.body?.isActive).toBe(true))
  })

  it('un producto inactivo sigue inactivo tras editarlo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseProductDetail(fakeProduct({ isActive: false })),
      updateWarehouseProductSuccess(sink),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body?.isActive).toBe(false))
  })

  it('guarda los cambios y refleja el producto actualizado', async () => {
    const successToast = vi.spyOn(toast, 'success')
    const actualizado = fakeProduct({ name: 'Filtro de aceite ABC' })
    server.use(updateWarehouseProductSuccess({}, actualizado), warehouseProductDetail(actualizado))
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(successToast).toHaveBeenCalled()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      'Filtro de aceite ABC',
    )
  })

  it('avisa el conflicto de versión y ofrece recargar', async () => {
    server.use(
      updateWarehouseProductError(412, {
        code: 'COM-004',
        detail: 'La versión enviada no es la vigente.',
      }),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    // El aviso suma la advertencia de que recargar descarta lo escrito, así que el
    // detalle del backend queda como parte del texto y no como nodo suelto.
    expect(await screen.findByRole('alert')).toHaveTextContent('La versión enviada no es la vigente.')
    // El modal NO se cierra: lo escrito se conserva.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Descartar y recargar' })).toBeInTheDocument()
  })

  it('tras recargar un conflicto la siguiente edición usa el ETag nuevo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseProductDetailSequence([
        { product: fakeProduct(), etag: DEFAULT_PRODUCT_ETAG },
        { product: fakeProduct({ name: 'Filtro editado por otro' }), etag: '"v2"' },
      ]),
      updateWarehouseProductError(412, { code: 'COM-004', detail: 'Versión vencida.' }),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await user.click(await screen.findByRole('button', { name: 'Descartar y recargar' }))
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
        'Filtro editado por otro',
      ),
    )
    server.use(updateWarehouseProductSuccess(sink))
    await user.click(await screen.findByRole('button', { name: /editar/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.ifMatch).toBe('"v2"'))
  })

  it('muestra el mensaje del backend ante una identidad duplicada', async () => {
    const errorToast = vi.spyOn(toast, 'error')
    server.use(
      updateWarehouseProductError(409, {
        code: 'WH-010',
        detail: 'Ya existe un producto con ese nombre, marca y número de parte.',
      }),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() =>
      expect(errorToast).toHaveBeenCalledWith(
        'Ya existe un producto con ese nombre, marca y número de parte.',
      ),
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('asigna al campo los errores de validación del backend', async () => {
    server.use(
      updateWarehouseProductError(400, {
        detail: 'Datos inválidos',
        errors: [{ field: 'name', message: 'El nombre ya no es válido.' }],
      }),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('El nombre ya no es válido.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nombre')).toHaveAttribute('aria-invalid', 'true')
  })

  // ----- Validación del form -----
  it('valida el nombre al salir del campo y no llama al backend', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(updateWarehouseProductSuccess(sink))
    renderDetalle()
    const user = await openEditModal()
    const nombre = screen.getByLabelText('Nombre')
    await user.clear(nombre)
    await user.tab()
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body).toBeUndefined())
  })

  it('el campo de stock mínimo no deja tipear el signo menos', async () => {
    renderDetalle()
    const user = await openEditModal()
    const minimo = screen.getByLabelText('Stock mínimo')
    await user.clear(minimo)
    await user.type(minimo, '-1')
    // El input numérico descarta el signo, así que el negativo no llega ni al schema
    // (la regla en sí se prueba sobre el schema, en product.schema.test.ts).
    expect(minimo).toHaveValue(1)
  })

  it('exige el stock mínimo cuando se deja vacío', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(updateWarehouseProductSuccess(sink))
    renderDetalle()
    const user = await openEditModal()
    await user.clear(screen.getByLabelText('Stock mínimo'))
    await user.tab()
    expect(await screen.findByText(/indica el stock mínimo/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body).toBeUndefined())
  })

  it('avisa cuando no llegó la versión del producto y bloquea el guardado', async () => {
    // Sin el header ETag (gateway mal configurado) no se puede armar el If-Match.
    server.use(warehouseProductDetailWithoutEtag())
    renderDetalle()
    await openEditModal()
    expect(screen.getByText(/falta la versión del producto/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeDisabled()
  })

  // ----- Características -----
  it('agrega una característica y la envía como objeto', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(updateWarehouseProductSuccess(sink))
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /agregar característica/i }))
    await user.type(screen.getByLabelText('Nombre de la característica 1'), 'rosca')
    await user.type(screen.getByLabelText('Valor de la característica 1'), '3/4-16')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body?.attributes).toEqual({ rosca: '3/4-16' }))
  })

  it('quita una característica existente', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseProductDetail(fakeProduct({ attributes: { rosca: '3/4-16' } })),
      updateWarehouseProductSuccess(sink),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /quitar la característica 1/i }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body?.attributes).toEqual({}))
  })

  it('rechaza dos características con el mismo nombre', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(updateWarehouseProductSuccess(sink))
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: /agregar característica/i }))
    await user.click(screen.getByRole('button', { name: /agregar característica/i }))
    await user.type(screen.getByLabelText('Nombre de la característica 1'), 'rosca')
    await user.type(screen.getByLabelText('Valor de la característica 1'), '3/4')
    await user.type(screen.getByLabelText('Nombre de la característica 2'), 'Rosca')
    await user.type(screen.getByLabelText('Valor de la característica 2'), '5/8')
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText(/ya usaste esa característica/i)).toBeInTheDocument()
    await waitFor(() => expect(sink.body).toBeUndefined())
  })

  // ----- Categoría -----
  it('permite elegir otra categoría del catálogo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseProductCategories([
        fakeProductCategory({ id: 7, name: 'Filtros' }),
        fakeProductCategory({ id: 8, name: 'Lubricantes' }),
      ]),
      updateWarehouseProductSuccess(sink),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: 'Quitar selección' }))
    await user.type(screen.getByRole('combobox', { name: 'Categoría' }), 'Lub')
    // El `role="option"` está en el `li`; el clickable es el botón que envuelve.
    await user.click(await screen.findByRole('button', { name: 'Lubricantes' }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body?.categoryId).toBe(8))
  })

  it('crea una categoría al vuelo y la deja seleccionada', async () => {
    const categorySink: { body?: Record<string, unknown> } = {}
    const updateSink: UpdateCaptureSink = {}
    server.use(
      createWarehouseProductCategorySuccess(categorySink, fakeProductCategory({ id: 99, name: 'Sellos' })),
      updateWarehouseProductSuccess(updateSink),
    )
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: 'Quitar selección' }))
    await user.type(screen.getByRole('combobox', { name: 'Categoría' }), 'Sellos')
    await user.click(await screen.findByRole('button', { name: /crear categoría/i }))
    await waitFor(() => expect(categorySink.body).toEqual({ name: 'Sellos' }))
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(updateSink.body?.categoryId).toBe(99))
  })

  it('avisa en el modal cuando el catálogo de categorías no carga', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseProductCategoriesError(500), updateWarehouseProductSuccess(sink))
    renderDetalle()
    const user = await openEditModal()
    // Sin catálogo el combobox no puede mostrar la categoría guardada y se ve vacío:
    // el aviso evita que parezca borrada.
    expect(
      await screen.findByText(/no se pudieron cargar las categorías/i),
    ).toBeInTheDocument()
    // El valor sigue intacto: guardar conserva la categoría del producto.
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.body?.categoryId).toBe(7))
  })

  it('no ofrece crear la categoría con menos de 3 caracteres', async () => {
    renderDetalle()
    const user = await openEditModal()
    await user.click(screen.getByRole('button', { name: 'Quitar selección' }))
    await user.type(screen.getByRole('combobox', { name: 'Categoría' }), 'Se')
    // El contrato exige 3 caracteres: ofrecerlo antes llevaría derecho a un 400.
    expect(screen.queryByRole('button', { name: /crear categoría/i })).not.toBeInTheDocument()
  })

  // ----- A11y -----
  it('presenta la pantalla con un único h1', async () => {
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('no tiene violaciones de accesibilidad', async () => {
    const { container } = renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(await axe(container)).toHaveNoViolations()
  })

  it('el modal de edición no tiene violaciones de accesibilidad', async () => {
    const { container } = renderDetalle()
    await openEditModal()
    expect(await axe(container)).toHaveNoViolations()
  })
})
