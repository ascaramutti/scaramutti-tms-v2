import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { StockListPage } from './StockListPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { ProductsCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  fakeProduct,
  fakeProductCategory,
  warehouseProductCategories,
  warehouseProductCategoriesError,
  warehouseProductsCapture,
  warehouseProductsEmpty,
  warehouseProductsError,
  warehouseProductsOkThenErrorOnNextPage,
  warehouseProductsPage,
  warehouseProductsPagedByParam,
  warehouseProductsSlow,
  warehouseStats,
  warehouseStatsError,
  warehouseStatsSlow,
} from '../../../test/mocks/handlers/warehouse'

function renderExistencias() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // La página vive dentro del layout autenticado: sesión admin sembrada en cache.
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cotizaciones/almacen']}>
          <Routes>
            <Route path="/cotizaciones/almacen" element={<StockListPage />} />
            <Route path="/cotizaciones/almacen/productos/:id" element={<DetalleStub />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

// Stub del detalle: refleja el :id para verificar la navegación con el valor correcto.
function DetalleStub() {
  const { id } = useParams()
  return <div>DETALLE {id}</div>
}

/** Sube del nodo de texto a su `<tr>` para acotar asserts a una fila. */
function rowOf(textNode: HTMLElement): HTMLElement {
  return textNode.closest('tr') as HTMLElement
}

describe('StockListPage', () => {
  // ----- Render -----
  it('muestra el spinner de la tabla durante la carga inicial', async () => {
    server.use(warehouseProductsSlow([fakeProduct()], 40))
    renderExistencias()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    // Esperamos la resolución para no dejar el fetch colgado tras el unmount.
    expect(await screen.findByText('PRO-0001')).toBeInTheDocument()
  })

  it('renderiza las filas de la página (happy path)', async () => {
    renderExistencias()
    expect(await screen.findByText('PRO-0001')).toBeInTheDocument()
    expect(screen.getByText('PRO-0002')).toBeInTheDocument()
    expect(screen.getByText('PRO-0003')).toBeInTheDocument()
  })

  it('mapea los campos del producto en su fila', async () => {
    server.use(
      warehouseProductsPage([
        fakeProduct({
          code: 'PRO-0007',
          name: 'Filtro de aceite XYZ',
          category: { id: 7, name: 'Filtros' },
          unitOfMeasure: { id: 1, code: 'UND', name: 'Unidad' },
          brand: 'Bosch',
          partNumber: 'F026407123',
          stock: 12,
          minStock: 4,
        }),
      ]),
    )
    renderExistencias()
    const row = rowOf(await screen.findByText('PRO-0007'))
    expect(within(row).getByText('Filtro de aceite XYZ')).toBeInTheDocument()
    expect(within(row).getByText('Bosch · F026407123')).toBeInTheDocument()
    expect(within(row).getByText('Filtros')).toBeInTheDocument()
    expect(within(row).getByText('12 UND')).toBeInTheDocument()
    expect(within(row).getByText('4 UND')).toBeInTheDocument()
  })

  it('muestra un guion cuando el producto no tiene marca ni número de parte', async () => {
    server.use(
      warehouseProductsPage([fakeProduct({ code: 'PRO-0009', brand: null, partNumber: null })]),
    )
    renderExistencias()
    const row = rowOf(await screen.findByText('PRO-0009'))
    expect(within(row).getByText('—')).toBeInTheDocument()
  })

  it('muestra el estado vacío de base cuando no hay productos ni filtros', async () => {
    server.use(warehouseProductsEmpty())
    renderExistencias()
    expect(await screen.findByText(/aún no hay productos/i)).toBeInTheDocument()
  })

  it('muestra el estado vacío de filtros cuando hay filtros activos y cero resultados', async () => {
    server.use(warehouseProductsEmpty())
    const user = userEvent.setup()
    renderExistencias()
    await screen.findByText(/aún no hay productos/i)
    await user.selectOptions(screen.getByLabelText(/categoría/i), '7')
    expect(await screen.findByText(/no se encontraron productos/i)).toBeInTheDocument()
  })

  it('muestra el error del backend y permite reintentar', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductsError(500, { detail: 'El servidor falló al listar.' }))
    renderExistencias()
    expect(await screen.findByText('El servidor falló al listar.')).toBeInTheDocument()
    server.use(warehouseProductsPage([fakeProduct({ code: 'PRO-0001' })]))
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText('PRO-0001')).toBeInTheDocument()
  })

  // ----- Distintivo de stock bajo -----
  it('marca "Bajo" cuando el backend lo indica', async () => {
    server.use(
      warehouseProductsPage([
        fakeProduct({ code: 'PRO-0001', stock: 2, minStock: 4, lowStock: true }),
      ]),
    )
    renderExistencias()
    const row = rowOf(await screen.findByText('PRO-0001'))
    expect(within(row).getByText('Bajo')).toBeInTheDocument()
  })

  it('no marca "Bajo" cuando el backend lo indica en false', async () => {
    server.use(
      warehouseProductsPage([
        fakeProduct({ code: 'PRO-0001', stock: 10, minStock: 4, lowStock: false }),
      ]),
    )
    renderExistencias()
    const row = rowOf(await screen.findByText('PRO-0001'))
    expect(within(row).queryByText('Bajo')).not.toBeInTheDocument()
  })

  it('el distintivo sale de lowStock del backend, no de un cálculo local', async () => {
    // Combinación imposible a propósito: la regla RN-WH11 vive en la VIEW y el
    // front no la recalcula.
    server.use(
      warehouseProductsPage([
        fakeProduct({ code: 'PRO-0001', stock: 10, minStock: 4, lowStock: true }),
      ]),
    )
    renderExistencias()
    const row = rowOf(await screen.findByText('PRO-0001'))
    expect(within(row).getByText('Bajo')).toBeInTheDocument()
  })

  // ----- Búsqueda -----
  it('no envía q con menos de 3 caracteres y muestra el mensaje guía', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.type(screen.getByLabelText(/buscar/i), 'ac')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    // Ninguna request llevó `q`: ni la inicial ni una disparada por el tipeo.
    await waitFor(() => expect(sink.calls?.every((call) => call.get('q') === null)).toBe(true))
  })

  it('envía q con 3 o más caracteres', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.type(screen.getByLabelText(/buscar/i), 'filtro')
    await waitFor(() => expect(sink.params?.get('q')).toBe('filtro'))
  })

  it('al borrar la búsqueda deja de enviar q', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    const input = screen.getByLabelText(/buscar/i)
    await user.type(input, 'filtro')
    await waitFor(() => expect(sink.params?.get('q')).toBe('filtro'))
    await user.clear(input)
    await waitFor(() => expect(sink.params?.get('q')).toBeNull())
  })

  // ----- Filtros -----
  it('filtra por categoría', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.selectOptions(screen.getByLabelText(/categoría/i), '7')
    await waitFor(() => expect(sink.params?.get('categoryId')).toBe('7'))
  })

  it('puebla el filtro de categoría desde el catálogo', async () => {
    server.use(
      warehouseProductCategories([
        fakeProductCategory({ id: 7, name: 'Filtros' }),
        fakeProductCategory({ id: 8, name: 'Lubricantes' }),
      ]),
    )
    renderExistencias()
    await screen.findByText('PRO-0001')
    await waitFor(() =>
      expect(
        within(screen.getByLabelText(/categoría/i))
          .getAllByRole('option')
          .map((option) => option.textContent),
      ).toEqual(['Todas', 'Filtros', 'Lubricantes']),
    )
  })

  it('avisa cuando el catálogo de categorías no carga', async () => {
    server.use(warehouseProductCategoriesError(500))
    renderExistencias()
    await screen.findByText('PRO-0001')
    // El select queda solo con "Todas": sin el aviso, el filtro parecería vacío.
    expect(await screen.findByText(/no se pudieron cargar las categorías/i)).toBeInTheDocument()
    expect(screen.getByText('PRO-0001')).toBeInTheDocument()
  })

  it('lista solo productos activos', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await waitFor(() => expect(sink.params?.get('isActive')).toBe('true'))
  })

  it('el tile de stock bajo envía lowOnly=true y muestra el chip para quitarlo', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.click(screen.getByRole('button', { name: /filtrar: solo stock bajo/i }))
    await waitFor(() => expect(sink.params?.get('lowOnly')).toBe('true'))
    expect(
      screen.getByRole('button', { name: 'Quitar filtro: solo stock bajo' }),
    ).toBeInTheDocument()
  })

  it('el chip quita el corte de stock bajo y el parámetro se omite', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.click(screen.getByRole('button', { name: /filtrar: solo stock bajo/i }))
    await waitFor(() => expect(sink.params?.get('lowOnly')).toBe('true'))
    await user.click(screen.getByRole('button', { name: 'Quitar filtro: solo stock bajo' }))
    // Se OMITE: equivale al default `false` del contrato.
    await waitFor(() => expect(sink.params?.get('lowOnly')).toBeNull())
  })

  it('reinicia a la primera página al cambiar un filtro', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()], { totalElements: 25, size: 10 }))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.click(screen.getByLabelText('Página siguiente'))
    await waitFor(() => expect(sink.params?.get('page')).toBe('1'))
    await user.click(screen.getByRole('button', { name: /filtrar: solo stock bajo/i }))
    await waitFor(() => {
      expect(sink.params?.get('page')).toBe('0')
      expect(sink.params?.get('lowOnly')).toBe('true')
    })
  })

  // ----- Paginación -----
  it('deshabilita "Página anterior" en la primera página', async () => {
    server.use(warehouseProductsPage([fakeProduct()], { totalElements: 25, size: 10, page: 0 }))
    renderExistencias()
    await screen.findByText('PRO-0001')
    expect(screen.getByLabelText('Página anterior')).toBeDisabled()
    expect(screen.getByLabelText('Página siguiente')).toBeEnabled()
  })

  it('pide la página siguiente al avanzar', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()], { totalElements: 25, size: 10 }))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.click(screen.getByLabelText('Página siguiente'))
    await waitFor(() => expect(sink.params?.get('page')).toBe('1'))
  })

  it('deshabilita "Página siguiente" en la última página', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductsPagedByParam(15, 10))
    renderExistencias()
    await screen.findByText('P0')
    await user.click(screen.getByLabelText('Página siguiente'))
    expect(await screen.findByText('P1')).toBeInTheDocument()
    expect(screen.getByLabelText('Página siguiente')).toBeDisabled()
  })

  it('envía el tamaño de página del listado', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderExistencias()
    await screen.findByText('PRO-0001')
    await waitFor(() => expect(sink.params?.get('size')).toBe('10'))
  })

  it('ante un error al paginar mantiene la tabla previa y muestra un aviso', async () => {
    const user = userEvent.setup()
    server.use(
      warehouseProductsOkThenErrorOnNextPage([fakeProduct({ code: 'PRO-0001' })], {
        totalElements: 25,
        size: 10,
      }),
    )
    renderExistencias()
    await screen.findByText('PRO-0001')
    await user.click(screen.getByLabelText('Página siguiente'))
    expect(await screen.findByText('Fallo al paginar')).toBeInTheDocument()
    expect(screen.getByText('PRO-0001')).toBeInTheDocument()
    // El footer refleja la página realmente mostrada (la 0 retenida), no la que falló.
    expect(screen.getByText('Mostrando 1–1 de 25')).toBeInTheDocument()
  })

  // ----- Indicadores + degradación independiente -----
  it('muestra los cuatro indicadores del almacén', async () => {
    server.use(
      warehouseStats({
        activeProducts: 120,
        lowStockCount: 7,
        entriesThisMonth: 12,
        withdrawalsThisMonth: 31,
      }),
    )
    renderExistencias()
    expect(await screen.findByText('120')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('31')).toBeInTheDocument()
  })

  it('si fallan los indicadores la tabla sigue funcionando', async () => {
    server.use(warehouseStatsError(500, { detail: 'Fallaron los indicadores.' }))
    renderExistencias()
    expect(await screen.findByText('PRO-0001')).toBeInTheDocument()
    expect(screen.getByText('Fallaron los indicadores.')).toBeInTheDocument()
  })

  it('si falla el listado los indicadores siguen visibles', async () => {
    server.use(warehouseProductsError(500, { detail: 'Falló el listado.' }))
    renderExistencias()
    expect(await screen.findByText('Falló el listado.')).toBeInTheDocument()
    expect(screen.getByText('Productos activos')).toBeInTheDocument()
    expect(screen.getByText('120')).toBeInTheDocument()
  })

  it('los indicadores cargan sin bloquear la tabla', async () => {
    server.use(warehouseStatsSlow(40))
    renderExistencias()
    expect(await screen.findByText('PRO-0001')).toBeInTheDocument()
    expect(await screen.findByText('120')).toBeInTheDocument()
  })

  it('la fila lleva al detalle del producto', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductsPage([fakeProduct({ id: 42, code: 'PRO-0042' })]))
    renderExistencias()
    await user.click(await screen.findByText('PRO-0042'))
    expect(await screen.findByText('DETALLE 42')).toBeInTheDocument()
  })

  // ----- Accesibilidad -----
  it('expone los encabezados de columna de la tabla', async () => {
    renderExistencias()
    await screen.findByText('PRO-0001')
    for (const header of ['Código', 'Producto', 'Categoría', 'Stock actual', 'Mínimo']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }
  })

  it('presenta la pantalla con un único h1', async () => {
    renderExistencias()
    await screen.findByText('PRO-0001')
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Existencias')
  })

  it('los controles de filtro tienen rótulo accesible', async () => {
    renderExistencias()
    await screen.findByText('PRO-0001')
    expect(screen.getByLabelText(/buscar/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/categoría/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /filtrar: solo stock bajo/i })).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad', async () => {
    const { container } = renderExistencias()
    await screen.findByText('PRO-0001')
    expect(await axe(container)).toHaveNoViolations()
  })
})
