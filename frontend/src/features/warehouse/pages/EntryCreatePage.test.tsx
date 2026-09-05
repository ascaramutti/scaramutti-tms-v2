import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { EntryCreatePage } from './EntryCreatePage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { formatCurrency, todayIsoDate } from '../../../shared/utils/formatters'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { currenciesError } from '../../../test/mocks/handlers/catalogs'
import { server } from '../../../test/mocks/server'
import type {
  BodyCaptureSink,
  ProductsCaptureSink,
} from '../../../test/mocks/handlers/warehouse'
import {
  createInvoiceCapture,
  createInvoiceError,
  createInvoiceSlow,
  createSupplierCapture,
  createSupplierError,
  createWarehouseProductCapture,
  createWarehouseProductError,
  fakeProduct,
  fakeSupplier,
  warehouseProductsCapture,
  warehouseProductsPage,
  warehouseSuppliersCapture,
  warehouseSuppliersError,
  warehouseSuppliersPage,
  warehouseUnitsOfMeasureError,
} from '../../../test/mocks/handlers/warehouse'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

function renderRegistro() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cotizaciones/almacen/entradas/nueva']}>
          <Routes>
            <Route
              path="/cotizaciones/almacen/entradas/nueva"
              element={<EntryCreatePage />}
            />
            <Route path="/cotizaciones/almacen/entradas" element={<div>LISTADO STUB</div>} />
            <Route path="/cotizaciones/almacen/entradas/:id" element={<div>DETALLE STUB</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/**
 * Importe tal como lo ve el usuario. `Intl` separa el símbolo con un espacio
 * duro, y Testing Library normaliza los espacios del DOM: sin normalizar también
 * el esperado, la comparación falla por un carácter invisible.
 */
function money(amount: number, currencyCode: string): string {
  return formatCurrency(amount, currencyCode).replace(/\s/g, ' ')
}

/** Espera a que el form esté montado (el catálogo de monedas ya resolvió). */
async function waitForForm() {
  return screen.findByLabelText('Proveedor')
}

async function selectSupplier(user: UserEvent) {
  await user.type(screen.getByLabelText('Proveedor'), 'rep')
  await user.click(await screen.findByText('REPUESTOS DIÉSEL S.A.C.'))
}

/**
 * Elige un producto en la fila pedida. La opción se busca DENTRO del listbox
 * abierto: el nombre del producto ya elegido en otra fila también está en
 * pantalla y sin acotar se haría clic sobre esa etiqueta.
 */
async function selectProductInRow(user: UserEvent, row: number, name = 'Filtro de aceite XYZ') {
  await user.type(screen.getByLabelText(`Producto del ítem ${row}`), 'fil')
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(name))
}

/** Carga una factura mínima válida: proveedor, número, fecha y un ítem completo. */
async function fillMinimalInvoice(user: UserEvent) {
  await selectSupplier(user)
  await user.type(screen.getByLabelText(/n° de factura/i), 'F001-00123')
  await user.type(screen.getByLabelText(/fecha de factura/i), '2026-07-02')
  await selectProductInRow(user, 1)
  await user.type(screen.getByLabelText('Cantidad del ítem 1'), '10')
  await user.type(screen.getByLabelText('Precio unitario del ítem 1'), '45')
}

describe('EntryCreatePage', () => {
  // ----- Render -----
  it('renderiza la cabecera y una única fila de ítem al abrir', async () => {
    renderRegistro()
    await waitForForm()
    expect(screen.getByLabelText(/n° de factura/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/fecha de factura/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/guía de remisión/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/moneda/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/observaciones/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Producto del ítem 1')).toBeInTheDocument()
    expect(screen.queryByLabelText('Producto del ítem 2')).not.toBeInTheDocument()
  })

  it('puebla el select de moneda con el catálogo y preselecciona soles', async () => {
    renderRegistro()
    await waitForForm()
    const currency = screen.getByLabelText(/moneda/i)
    // Mismo formato que el wizard de cotizaciones: "PEN (S/)".
    expect(within(currency).getByRole('option', { name: 'PEN (S/)' })).toBeInTheDocument()
    expect(within(currency).getByRole('option', { name: 'USD (US$)' })).toBeInTheDocument()
    await waitFor(() => expect(currency).toHaveValue('2'))
  })

  it('acota la fecha de factura a hoy (no se emiten a futuro)', async () => {
    renderRegistro()
    await waitForForm()
    expect(screen.getByLabelText(/fecha de factura/i)).toHaveAttribute('max', todayIsoDate())
  })

  it('muestra el error de monedas y permite reintentar antes de montar el form', async () => {
    server.use(currenciesError(500))
    const user = userEvent.setup()
    renderRegistro()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByLabelText('Proveedor')).not.toBeInTheDocument()
    server.resetHandlers()
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await waitForForm()).toBeInTheDocument()
  })

  // ----- Ítems dinámicos -----
  it('agrega una fila sin tocar los valores de la anterior', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Cantidad del ítem 1'), '7')
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    expect(screen.getByLabelText('Producto del ítem 2')).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad del ítem 1')).toHaveValue(7)
  })

  it('la fila arranca sin cantidad ni precio (un 0 pasaría como bonificación)', async () => {
    renderRegistro()
    await waitForForm()
    expect(screen.getByLabelText('Cantidad del ítem 1')).toHaveValue(null)
    expect(screen.getByLabelText('Precio unitario del ítem 1')).toHaveValue(null)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('exige el precio unitario cuando la fila quedó sin completarlo', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createInvoiceCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await selectSupplier(user)
    await user.type(screen.getByLabelText(/n° de factura/i), 'F001-00123')
    await user.type(screen.getByLabelText(/fecha de factura/i), '2026-07-02')
    await selectProductInRow(user, 1)
    await user.type(screen.getByLabelText('Cantidad del ítem 1'), '10')
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    expect(await screen.findByText('Indica el precio unitario')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('no permite quitar la única fila (el contrato exige al menos un ítem)', async () => {
    renderRegistro()
    await waitForForm()
    expect(screen.queryByRole('button', { name: /quitar el ítem/i })).not.toBeInTheDocument()
  })

  it('quita la fila correcta y conserva los valores de las otras', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.type(screen.getByLabelText('Cantidad del ítem 1'), '1')
    await user.type(screen.getByLabelText('Cantidad del ítem 2'), '2')
    await user.type(screen.getByLabelText('Cantidad del ítem 3'), '3')
    await user.click(screen.getByRole('button', { name: 'Quitar el ítem 2' }))
    expect(screen.getByLabelText('Cantidad del ítem 1')).toHaveValue(1)
    expect(screen.getByLabelText('Cantidad del ítem 2')).toHaveValue(3)
    expect(screen.queryByLabelText('Cantidad del ítem 3')).not.toBeInTheDocument()
  })

  it('quitar una fila conserva el producto elegido en las otras', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await selectProductInRow(user, 1)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.click(screen.getByRole('button', { name: 'Quitar el ítem 2' }))
    expect(screen.getByText('Filtro de aceite XYZ')).toBeInTheDocument()
  })

  it('calcula el subtotal de la fila y el total de la factura', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Cantidad del ítem 1'), '10')
    await user.type(screen.getByLabelText('Precio unitario del ítem 1'), '45')
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.type(screen.getByLabelText('Cantidad del ítem 2'), '24')
    await user.type(screen.getByLabelText('Precio unitario del ítem 2'), '18.5')
    expect(screen.getByText('S/ 450.00')).toBeInTheDocument()
    expect(screen.getByText('S/ 444.00')).toBeInTheDocument()
    expect(screen.getByText('S/ 894.00')).toBeInTheDocument()
  })

  it('recalcula el total al quitar un ítem', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Cantidad del ítem 1'), '10')
    await user.type(screen.getByLabelText('Precio unitario del ítem 1'), '45')
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.type(screen.getByLabelText('Cantidad del ítem 2'), '2')
    await user.type(screen.getByLabelText('Precio unitario del ítem 2'), '10')
    expect(screen.getByText('S/ 470.00')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Quitar el ítem 2' }))
    // Subtotal de la única fila y total de la factura: el mismo importe dos veces.
    expect(screen.getAllByText('S/ 450.00')).toHaveLength(2)
  })

  it('el total sigue la moneda elegida', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Cantidad del ítem 1'), '2')
    await user.type(screen.getByLabelText('Precio unitario del ítem 1'), '100')
    await user.selectOptions(screen.getByLabelText(/moneda/i), '1')
    expect(await screen.findAllByText(money(200, 'USD'))).toHaveLength(2)
  })

  it('avisa (sin bloquear) cuando el mismo producto se repite en dos filas', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await selectProductInRow(user, 1)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await selectProductInRow(user, 2)
    expect(await screen.findByText(/ya cargaste este producto en otra fila/i)).toBeInTheDocument()
  })

  // ----- Búsqueda de proveedor -----
  it('no busca proveedores con menos de 3 caracteres y muestra el mensaje guía', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseSuppliersCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 're')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('busca proveedores con 3 o más caracteres, solo activos', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseSuppliersCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 'rep')
    await waitFor(() => {
      expect(sink.params?.get('q')).toBe('rep')
      expect(sink.params?.get('isActive')).toBe('true')
    })
  })

  it('muestra el RUC del proveedor y lo omite cuando no lo tiene', async () => {
    server.use(warehouseSuppliersPage([fakeSupplier({ name: 'SELLOS SAC', ruc: null })]))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 'sel')
    expect(await screen.findByText('SELLOS SAC')).toBeInTheDocument()
    expect(screen.queryByText(/RUC undefined|RUC null/)).not.toBeInTheDocument()
  })

  it('avisa cuando la búsqueda de proveedores falla, sin romper el resto del form', async () => {
    server.use(warehouseSuppliersError(500))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 'rep')
    expect(await screen.findByText(/no se pudieron buscar proveedores/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/n° de factura/i)).toBeEnabled()
  })

  // ----- Crear proveedor al vuelo -----
  it('crea un proveedor al vuelo y lo deja seleccionado', async () => {
    const sink: BodyCaptureSink = {}
    server.use(
      warehouseSuppliersPage([]),
      createSupplierCapture(sink, fakeSupplier({ id: 99, name: 'SELLOS SAC', ruc: null })),
    )
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 'Sellos SAC')
    await user.click(await screen.findByRole('button', { name: /nuevo proveedor/i }))
    // El nombre llega precargado con lo que se venía buscando.
    expect(screen.getByLabelText(/razón social/i)).toHaveValue('Sellos SAC')
    await user.type(screen.getByLabelText(/^ruc/i), '20512345678')
    // El botón trae la clase que lo apaga mientras envía: pasó de vivir dentro de la
    // constante a ser una prop suelta del componente compartido.
    expect(screen.getByRole('button', { name: /crear proveedor/i }).className).toContain(
      'disabled:bg-accent-disabled',
    )
    await user.click(screen.getByRole('button', { name: /crear proveedor/i }))
    await waitFor(() => expect(sink.body).toEqual({
      name: 'Sellos SAC',
      ruc: '20512345678',
      phone: null,
      contactName: null,
    }))
    expect(await screen.findByText('SELLOS SAC')).toBeInTheDocument()
  })

  it('abrir y enviar el modal de proveedor no envía el formulario de la entrada', async () => {
    // El modal se monta con un portal justamente para esto: anidado, su submit
    // burbujearía al form de la entrada y lo marcaría todo en rojo.
    const invoiceSink: BodyCaptureSink = {}
    server.use(
      warehouseSuppliersPage([]),
      createSupplierCapture({}),
      createInvoiceCapture(invoiceSink),
    )
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 'Sellos SAC')
    await user.click(await screen.findByRole('button', { name: /nuevo proveedor/i }))
    await user.click(screen.getByRole('button', { name: /crear proveedor/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByText('Indica el número de factura')).not.toBeInTheDocument()
    expect(invoiceSink.calls ?? []).toHaveLength(0)
  })

  it('un RUC mal formado no llega al backend', async () => {
    const sink: BodyCaptureSink = {}
    server.use(warehouseSuppliersPage([]), createSupplierCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 'Sellos SAC')
    await user.click(await screen.findByRole('button', { name: /nuevo proveedor/i }))
    await user.type(screen.getByLabelText(/^ruc/i), '123')
    await user.click(screen.getByRole('button', { name: /crear proveedor/i }))
    expect(await screen.findByText(/el ruc debe tener 11 dígitos/i)).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('muestra el detalle del backend cuando el proveedor ya existe y deja el modal abierto', async () => {
    server.use(
      warehouseSuppliersPage([]),
      createSupplierError(409, {
        code: 'WH-010',
        detail: 'Ya existe un proveedor con ese RUC',
      }),
    )
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Proveedor'), 'Sellos SAC')
    await user.click(await screen.findByRole('button', { name: /nuevo proveedor/i }))
    await user.click(screen.getByRole('button', { name: /crear proveedor/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('Ya existe un proveedor con ese RUC'),
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  // ----- Búsqueda de producto -----
  it('no busca productos con menos de 3 caracteres', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Producto del ítem 1'), 'fi')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('busca productos activos con 3 o más caracteres', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Producto del ítem 1'), 'fil')
    await waitFor(() => {
      expect(sink.params?.get('q')).toBe('fil')
      expect(sink.params?.get('isActive')).toBe('true')
    })
  })

  it('muestra código y unidad del producto en la opción, y la unidad en la fila', async () => {
    server.use(warehouseProductsPage([fakeProduct()]))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Producto del ítem 1'), 'fil')
    expect(await screen.findByText('PRO-0001 · UND')).toBeInTheDocument()
    await user.click(screen.getByText('Filtro de aceite XYZ'))
    expect(screen.getByText('UND')).toBeInTheDocument()
  })

  // ----- Crear producto al vuelo (modo crear del modal) -----
  it('crea un producto al vuelo con su unidad y lo selecciona en ESA fila', async () => {
    const sink: BodyCaptureSink = {}
    const invoiceSink: BodyCaptureSink = {}
    server.use(
      warehouseProductsPage([]),
      createWarehouseProductCapture(
        sink,
        fakeProduct({ id: 42, code: 'PRO-0042', name: 'Retén de rueda' }),
      ),
      createInvoiceCapture(invoiceSink),
    )
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.type(screen.getByLabelText('Producto del ítem 2'), 'Retén de rueda')
    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    expect(screen.getByLabelText(/nombre/i)).toHaveValue('Retén de rueda')
    await user.type(screen.getByLabelText('Categoría'), 'Filtros')
    await user.click(await screen.findByText('Filtros'))
    await user.selectOptions(screen.getByLabelText(/unidad de medida/i), '1')
    await user.click(screen.getByRole('button', { name: /crear producto/i }))
    await waitFor(() => expect(sink.body).toMatchObject({
      name: 'Retén de rueda',
      categoryId: 7,
      unitOfMeasureId: 1,
    }))
    // Queda en la fila 2, no en la 1.
    expect(await screen.findByText('Retén de rueda')).toBeInTheDocument()
    expect(screen.getByLabelText('Producto del ítem 1')).toBeInTheDocument()
    // El modal del producto se abre desde el mismo form que el de proveedor: su
    // submit tampoco puede filtrarse al registro de la entrada.
    expect(invoiceSink.calls ?? []).toHaveLength(0)
  })

  it('el catálogo de unidades es cerrado: no ofrece crearlas al vuelo', async () => {
    server.use(warehouseProductsPage([]))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Producto del ítem 1'), 'Retén de rueda')
    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    expect(screen.getByLabelText(/unidad de medida/i).tagName).toBe('SELECT')
    expect(screen.queryByRole('button', { name: /crear unidad/i })).not.toBeInTheDocument()
  })

  it('bloquea el alta de producto cuando el catálogo de unidades no carga', async () => {
    server.use(warehouseProductsPage([]), warehouseUnitsOfMeasureError(500))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Producto del ítem 1'), 'Retén de rueda')
    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    expect(await screen.findByText(/no se pudieron cargar las unidades/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /crear producto/i })).toBeDisabled()
  })

  it('muestra el detalle del backend cuando el producto ya existe', async () => {
    server.use(
      warehouseProductsPage([]),
      createWarehouseProductError(409, {
        code: 'WH-010',
        detail: 'Ya existe un producto con ese nombre, marca y número de parte',
      }),
    )
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.type(screen.getByLabelText('Producto del ítem 1'), 'Retén de rueda')
    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))
    await user.type(screen.getByLabelText('Categoría'), 'Filtros')
    await user.click(await screen.findByText('Filtros'))
    await user.selectOptions(screen.getByLabelText(/unidad de medida/i), '1')
    await user.click(screen.getByRole('button', { name: /crear producto/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        'Ya existe un producto con ese nombre, marca y número de parte',
      ),
    )
  })

  // ----- Submit -----
  it('envía el payload del contrato al backend', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createInvoiceCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await fillMinimalInvoice(user)
    await user.type(screen.getByLabelText(/guía de remisión/i), 'T001-0004567')
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    await waitFor(() =>
      expect(sink.body).toEqual({
        supplierId: 4,
        invoiceNumber: 'F001-00123',
        invoiceDate: '2026-07-02',
        guideNumber: 'T001-0004567',
        currencyId: 2,
        observations: null,
        items: [{ productId: 1, quantity: 10, unitPrice: 45 }],
      }),
    )
  })

  it('al registrar avisa y abre el detalle de la entrada creada', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await fillMinimalInvoice(user)
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    const { toast } = await import('sonner')
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('F001-00123'))
  })

  it('no permite un segundo envío mientras la mutación está en vuelo', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createInvoiceSlow(sink, 60))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await fillMinimalInvoice(user)
    const submit = screen.getByRole('button', { name: /registrar entrada/i })
    await user.click(submit)
    expect(await screen.findByRole('button', { name: /registrando…/i })).toBeDisabled()
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(1)
  })

  it('cancelar vuelve al listado sin llamar al backend', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createInvoiceCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(await screen.findByText('LISTADO STUB')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('no envía la factura incompleta y marca los campos que faltan', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createInvoiceCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    expect(await screen.findByText('Selecciona el proveedor')).toBeInTheDocument()
    expect(screen.getByText('Indica el número de factura')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  // ----- Errores del backend -----
  it('ancla el duplicado de factura al número, con el detalle del backend', async () => {
    server.use(
      createInvoiceError(409, {
        code: 'WH-002',
        detail: 'Ya existe la factura F001-00123 de ese proveedor',
      }),
    )
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await fillMinimalInvoice(user)
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    expect(
      await screen.findByText('Ya existe la factura F001-00123 de ese proveedor'),
    ).toBeInTheDocument()
    // El form no se limpia ni navega: se corrige y se reintenta.
    expect(screen.getByLabelText(/n° de factura/i)).toHaveValue('F001-00123')
    expect(screen.queryByText('DETALLE STUB')).not.toBeInTheDocument()
  })

  it('muestra el detalle del backend cuando un catálogo no es válido', async () => {
    server.use(
      createInvoiceError(400, {
        code: 'WH-004',
        detail: 'El proveedor indicado no existe o está inactivo',
      }),
    )
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await fillMinimalInvoice(user)
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('El proveedor indicado no existe o está inactivo'),
    )
  })

  it('cae al mensaje de respaldo cuando el error no trae detalle', async () => {
    server.use(createInvoiceError(500, { detail: undefined }))
    const user = userEvent.setup()
    renderRegistro()
    await waitForForm()
    await fillMinimalInvoice(user)
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        'No se pudo registrar la entrada. Intenta de nuevo.',
      ),
    )
  })

  // ----- A11y -----
  it('presenta la pantalla con un único h1', async () => {
    renderRegistro()
    await waitForForm()
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Registrar entrada')
  })

  it('no tiene violaciones de accesibilidad con el formulario vacío', async () => {
    const { container } = renderRegistro()
    await waitForForm()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad con errores visibles', async () => {
    const user = userEvent.setup()
    const { container } = renderRegistro()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /registrar entrada/i }))
    await screen.findByText('Indica el número de factura')
    expect(await axe(container)).toHaveNoViolations()
  })
})
