import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { EntryEditPage } from './EntryEditPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { todayIsoDate } from '../../../shared/utils/formatters'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { currenciesError } from '../../../test/mocks/handlers/catalogs'
import { server } from '../../../test/mocks/server'
import type { UpdateCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  DEFAULT_INVOICE_ETAG,
  fakeInvoice,
  fakeProduct,
  updateInvoiceError,
  updateInvoiceSlow,
  updateInvoiceSuccess,
  warehouseInvoiceDetail,
  warehouseInvoiceDetailError,
  warehouseInvoiceDetailSequence,
  warehouseInvoiceDetailSlow,
  warehouseInvoiceDetailWithoutEtag,
  warehouseProductsPage,
} from '../../../test/mocks/handlers/warehouse'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

function renderEditar(id = '1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[`/cotizaciones/almacen/entradas/${id}/editar`]}>
          <Routes>
            <Route path="/cotizaciones/almacen/entradas/:id/editar" element={<EntryEditPage />} />
            <Route path="/cotizaciones/almacen/entradas/:id" element={<div>DETALLE STUB</div>} />
            <Route path="/cotizaciones/almacen/entradas" element={<div>ENTRADAS STUB</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Espera a que el form esté montado (GET del detalle + monedas resueltos). */
async function waitForForm() {
  return screen.findByLabelText('N° de factura')
}

/** Prefill con dos ítems, para probar el reemplazo y el recálculo del total. */
function twoItemInvoice() {
  return fakeInvoice({
    items: [
      { id: 1, product: { id: 1, code: 'PRO-0001', name: 'Filtro de aceite XYZ', unitCode: 'UND' }, quantity: 10, unitPrice: 45, subtotal: 450 },
      { id: 2, product: { id: 2, code: 'PRO-0002', name: 'Aceite 15W40', unitCode: 'GAL' }, quantity: 2, unitPrice: 50, subtotal: 100 },
    ],
    total: 550,
  })
}

async function typeReason(user: UserEvent, text = 'Corregí el precio unitario del filtro') {
  await user.type(screen.getByLabelText(/motivo/i), text)
}

describe('EntryEditPage', () => {
  // ----- Prefill -----
  it('muestra el spinner mientras carga el detalle para el prefill', async () => {
    server.use(warehouseInvoiceDetailSlow(fakeInvoice(), 40))
    renderEditar()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    expect(await waitForForm()).toBeInTheDocument()
  })

  it('prefillea la cabecera con los valores de la factura', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    expect(await waitForForm()).toHaveValue('F001-00123')
    expect(screen.getByLabelText('Fecha de factura')).toHaveValue('2026-07-02')
    expect(screen.getByLabelText(/guía de remisión/i)).toHaveValue('T001-0004567')
    expect(screen.getByLabelText('Moneda')).toHaveValue('2')
  })

  it('prefillea el ítem con su producto, cantidad y precio', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    await waitForForm()
    expect(screen.getByText('Filtro de aceite XYZ')).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad del ítem 1')).toHaveValue(10)
    expect(screen.getByLabelText('Precio unitario del ítem 1')).toHaveValue(45)
  })

  it('prefillea varios ítems cuando la factura tiene más de uno', async () => {
    server.use(warehouseInvoiceDetail(twoItemInvoice()))
    renderEditar()
    await waitForForm()
    expect(screen.getByText('Filtro de aceite XYZ')).toBeInTheDocument()
    expect(screen.getByText('Aceite 15W40')).toBeInTheDocument()
  })

  it('muestra el proveedor como solo lectura, sin buscador ni "+ nuevo proveedor"', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    await waitForForm()
    // Aparece en el encabezado y en el bloque read-only de la sección Factura.
    expect(screen.getAllByText(/REPUESTOS DIÉSEL S\.A\.C\./).length).toBeGreaterThan(0)
    expect(screen.queryByLabelText('Proveedor')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo proveedor/i })).not.toBeInTheDocument()
  })

  it('presenta la pantalla con un único h1', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    await waitForForm()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Editar factura F001-00123')
  })

  it('acota la fecha de factura a hoy (no futura)', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    expect(await screen.findByLabelText('Fecha de factura')).toHaveAttribute('max', todayIsoDate())
  })

  // ----- Carga / error / id inválido / anulada -----
  it('muestra "no encontrado" ante un 404 al cargar el detalle', async () => {
    server.use(warehouseInvoiceDetailError(404, { code: 'WH-003', detail: 'La entrada 999 no existe' }))
    renderEditar('999')
    expect(await screen.findByText(/no se encontró la entrada/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ir a entradas/i })).toBeInTheDocument()
  })

  it('no llama al backend si el id no es numérico', async () => {
    const requests: string[] = []
    const listener = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', listener)
    renderEditar('abc')
    expect(await screen.findByText(/no se encontró la entrada/i)).toBeInTheDocument()
    expect(requests.filter((url) => url.includes('/purchase-invoices/'))).toHaveLength(0)
    server.events.removeListener('request:start', listener)
  })

  it('rebota al detalle y avisa cuando la factura está anulada', async () => {
    server.use(warehouseInvoiceDetail(fakeInvoice({ status: 'CANCELLED', cancelReason: 'x'.repeat(12) })))
    renderEditar()
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    const { toast } = await import('sonner')
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('No se puede editar una factura anulada.'))
  })

  it('muestra el error del backend y permite reintentar el prefill', async () => {
    server.use(warehouseInvoiceDetailError(500, { detail: 'El servidor falló.' }))
    renderEditar()
    expect(await screen.findByText('El servidor falló.', {}, { timeout: 3000 })).toBeInTheDocument()
    server.use(warehouseInvoiceDetail())
    await userEvent.setup().click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await waitForForm()).toBeInTheDocument()
  })

  it('bloquea guardar cuando no llegó el ETag del detalle', async () => {
    server.use(warehouseInvoiceDetailWithoutEtag())
    renderEditar()
    await waitForForm()
    expect(screen.getByText(/falta la versión de la entrada/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeDisabled()
  })

  it('muestra el error de monedas y permite reintentar antes de montar el form', async () => {
    server.use(warehouseInvoiceDetail(), currenciesError(500))
    renderEditar()
    expect(await screen.findByRole('button', { name: /reintentar/i })).toBeInTheDocument()
    expect(screen.queryByLabelText('N° de factura')).not.toBeInTheDocument()
  })

  // ----- Interacción (ítems + total) -----
  it('agregar una fila conserva los valores prefilleados', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    expect(screen.getByLabelText('Producto del ítem 2')).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad del ítem 1')).toHaveValue(10)
    expect(screen.getByText('Filtro de aceite XYZ')).toBeInTheDocument()
  })

  it('no permite quitar la única fila', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    await waitForForm()
    expect(screen.queryByRole('button', { name: /quitar el ítem/i })).not.toBeInTheDocument()
  })

  it('cambiar la cantidad recalcula el subtotal y el total en vivo', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    const qty = screen.getByLabelText('Cantidad del ítem 1')
    await user.clear(qty)
    await user.type(qty, '20')
    expect(screen.getAllByText(/900/).length).toBeGreaterThan(0)
  })

  // ----- Validación del motivo (RN-WH4) -----
  it('exige el motivo cuando se deja vacío', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), updateInvoiceSuccess(sink))
    renderEditar()
    await waitForForm()
    await userEvent.setup().click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('exige al menos 10 caracteres en el motivo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), updateInvoiceSuccess(sink))
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/motivo/i), 'corto')
    await user.tab()
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  // ----- Integración del PUT / If-Match -----
  it('envía el body del contrato sin supplierId y con reason', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), updateInvoiceSuccess(sink))
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() =>
      expect(sink.body).toEqual({
        invoiceNumber: 'F001-00123',
        invoiceDate: '2026-07-02',
        guideNumber: 'T001-0004567',
        currencyId: 2,
        observations: null,
        items: [{ productId: 1, quantity: 10, unitPrice: 45 }],
        reason: 'Corregí el precio unitario del filtro',
      }),
    )
    expect(sink.body).not.toHaveProperty('supplierId')
  })

  it('el PUT viaja con el ETag del header, no con el updatedAt del body', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseInvoiceDetail(fakeInvoice({ updatedAt: '2026-07-02T10:00:00.39289Z' })),
      updateInvoiceSuccess(sink),
    )
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.ifMatch).toBe(DEFAULT_INVOICE_ETAG))
  })

  it('el reemplazo de ítems manda solo lo que queda en el form', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(twoItemInvoice()), updateInvoiceSuccess(sink))
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /quitar el ítem 2/i }))
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() =>
      expect(sink.body?.items).toEqual([{ productId: 1, quantity: 10, unitPrice: 45 }]),
    )
  })

  it('al guardar avisa y vuelve al detalle', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), updateInvoiceSuccess(sink))
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    const { toast } = await import('sonner')
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('F001-00123'))
  })

  it('no permite un segundo envío mientras el PUT está en vuelo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), updateInvoiceSlow(sink, 60))
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByRole('button', { name: /guardando…/i })).toBeDisabled()
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
  })

  it('ancla el duplicado de factura al número (409 WH-002)', async () => {
    server.use(
      warehouseInvoiceDetail(),
      updateInvoiceError(409, { code: 'WH-002', detail: 'Ya existe la factura F001-00123 de ese proveedor' }),
    )
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(
      await screen.findByText('Ya existe la factura F001-00123 de ese proveedor'),
    ).toBeInTheDocument()
    expect(screen.queryByText('DETALLE STUB')).not.toBeInTheDocument()
  })

  it('muestra el detalle del backend si la edición dejaría stock negativo (409 WH-006)', async () => {
    server.use(
      warehouseInvoiceDetail(),
      updateInvoiceError(409, { code: 'WH-006', detail: "De 'Filtro de aceite XYZ' ya se retiraron 9 UND." }),
    )
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith("De 'Filtro de aceite XYZ' ya se retiraron 9 UND."),
    )
    expect(screen.queryByText('DETALLE STUB')).not.toBeInTheDocument()
  })

  it('avisa conflicto de versión y ofrece descartar y recargar (412 COM-004)', async () => {
    server.use(
      warehouseInvoiceDetail(),
      updateInvoiceError(412, { code: 'COM-004', detail: 'La versión enviada no es la vigente.' }),
    )
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText(/La versión enviada no es la vigente/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /descartar y recargar/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeInTheDocument()
  })

  it('al descartar y recargar refresca el prefill con la versión vigente', async () => {
    server.use(
      warehouseInvoiceDetailSequence([
        { invoice: fakeInvoice(), etag: DEFAULT_INVOICE_ETAG },
        { invoice: fakeInvoice({ invoiceNumber: 'F001-00999' }), etag: '"v2"' },
      ]),
      updateInvoiceError(412, { code: 'COM-004', detail: 'Versión vencida.' }),
    )
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await user.click(await screen.findByRole('button', { name: /descartar y recargar/i }))
    expect(await screen.findByDisplayValue('F001-00999')).toBeInTheDocument()
  })

  it('cae al mensaje de respaldo cuando el error no trae detalle', async () => {
    server.use(warehouseInvoiceDetail(), updateInvoiceError(500, { detail: undefined }))
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('No se pudo guardar la entrada. Intenta de nuevo.'),
    )
  })

  // ----- Navegación -----
  it('cancelar vuelve al detalle sin llamar al backend', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), updateInvoiceSuccess(sink))
    renderEditar()
    await waitForForm()
    await userEvent.setup().click(screen.getByRole('button', { name: /cancelar/i }))
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('el BackLink vuelve al detalle', async () => {
    server.use(warehouseInvoiceDetail())
    renderEditar()
    await waitForForm()
    await userEvent.setup().click(screen.getByRole('link', { name: /volver a la entrada/i }))
    expect(screen.getByText('DETALLE STUB')).toBeInTheDocument()
  })

  it('mantiene disponible el alta de producto al vuelo en una fila nueva', async () => {
    server.use(warehouseInvoiceDetail(), warehouseProductsPage([fakeProduct({ id: 2, code: 'PRO-0002', name: 'Aceite 15W40' })]))
    renderEditar()
    await waitForForm()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.type(screen.getByLabelText('Producto del ítem 2'), 'ace')
    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Aceite 15W40')).toBeInTheDocument()
  })

  // ----- A11y -----
  it('no tiene violaciones de accesibilidad con el formulario cargado', async () => {
    server.use(warehouseInvoiceDetail())
    const { container } = renderEditar()
    await waitForForm()
    expect(await axe(container)).toHaveNoViolations()
  })
})
