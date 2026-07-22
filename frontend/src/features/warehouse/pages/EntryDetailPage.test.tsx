import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))
import { EntryDetailPage } from './EntryDetailPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { UpdateCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  AUDIT_USER,
  cancelInvoiceError,
  cancelInvoiceSlow,
  cancelInvoiceSuccess,
  DEFAULT_INVOICE_ETAG,
  fakeInvoice,
  warehouseInvoiceDetail,
  warehouseInvoiceDetailError,
  warehouseInvoiceDetailSequence,
  warehouseInvoiceDetailSlow,
  warehouseInvoiceDetailWithoutEtag,
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
        <MemoryRouter initialEntries={[`/cotizaciones/almacen/entradas/${id}`]}>
          <Routes>
            <Route path="/cotizaciones/almacen/entradas" element={<div>ENTRADAS</div>} />
            <Route path="/cotizaciones/almacen/entradas/:id" element={<EntryDetailPage />} />
            <Route
              path="/cotizaciones/almacen/productos/:id"
              element={<div>FICHA PRODUCTO</div>}
            />
            <Route
              path="/cotizaciones/almacen/entradas/:id/editar"
              element={<div>EDITAR STUB</div>}
            />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

async function openCancelDialog() {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: /anular/i }))
  return user
}

const CANCELLED_INVOICE = fakeInvoice({
  status: 'CANCELLED',
  cancelReason: 'Factura cargada dos veces por error',
  cancelledBy: AUDIT_USER,
  cancelledAt: '2026-07-06T10:00:00Z',
})

describe('EntryDetailPage', () => {
  // ----- Render de la cabecera -----
  it('muestra el spinner mientras carga la entrada', async () => {
    server.use(warehouseInvoiceDetailSlow(fakeInvoice(), 40))
    renderDetalle()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('renderiza la cabecera de la factura', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Factura F001-00123')
    // El proveedor aparece en el encabezado y repetido en la ficha.
    expect(screen.getAllByText(/REPUESTOS DIÉSEL S\.A\.C\./).length).toBeGreaterThan(0)
    expect(screen.getByText('20512345678')).toBeInTheDocument()
    expect(screen.getByText('T001-0004567')).toBeInTheDocument()
    expect(screen.getByText('02/07/2026')).toBeInTheDocument()
    expect(screen.getByText('PEN (S/)')).toBeInTheDocument()
  })

  it('muestra quién registró la entrada y cuándo', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText(/Admin TMS · 02\/07\/2026/)).toBeInTheDocument()
  })

  it('muestra un guion cuando no hay guía', async () => {
    server.use(warehouseInvoiceDetail(fakeInvoice({ guideNumber: null })))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('muestra las observaciones cuando existen', async () => {
    server.use(
      warehouseInvoiceDetail(fakeInvoice({ observations: 'Factura con nota de crédito pendiente.' })),
    )
    renderDetalle()
    expect(await screen.findByText('Factura con nota de crédito pendiente.')).toBeInTheDocument()
  })

  it('muestra el rastro de la última edición cuando la factura fue editada', async () => {
    server.use(
      warehouseInvoiceDetail(
        fakeInvoice({
          lastEdit: { by: AUDIT_USER, at: '2026-07-04T15:00:00Z', reason: 'Se corrigió el precio unitario' },
        }),
      ),
    )
    renderDetalle()
    expect(await screen.findByText('Última edición')).toBeInTheDocument()
    expect(screen.getByText('Se corrigió el precio unitario')).toBeInTheDocument()
  })

  it('no muestra el bloque de última edición si la factura nunca se editó', async () => {
    // El fixture base no trae `lastEdit`: el bloque no debe pintarse.
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByText('Última edición')).not.toBeInTheDocument()
  })

  it('distingue una factura anulada con su motivo y quién la anuló', async () => {
    server.use(warehouseInvoiceDetail(CANCELLED_INVOICE))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText('Anulada')).toBeInTheDocument()
    expect(screen.getByText(/Anulada por Admin TMS · 06\/07\/2026/)).toBeInTheDocument()
    expect(screen.getByText(/Factura cargada dos veces por error/)).toBeInTheDocument()
  })

  it('no muestra el bloque de anulación si la factura está activa', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByText('Anulada')).not.toBeInTheDocument()
  })

  // ----- Render de ítems (el front no recalcula) -----
  it('renderiza los ítems de la factura', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText('Filtro de aceite XYZ')).toBeInTheDocument()
    expect(screen.getByText('PRO-0001 · UND')).toBeInTheDocument()
  })

  it('muestra el total del backend aunque no cuadre con la suma naive de los ítems', async () => {
    server.use(
      warehouseInvoiceDetail(
        fakeInvoice({
          items: [
            { id: 1, product: { id: 1, code: 'PRO-0001', name: 'Filtro', unitCode: 'UND' }, quantity: 9, unitPrice: 50, subtotal: 450 },
            { id: 2, product: { id: 2, code: 'PRO-0002', name: 'Aceite', unitCode: 'GAL' }, quantity: 2, unitPrice: 50, subtotal: 100 },
          ],
          total: 500, // 450 + 100 = 550 a propósito ≠ 500: el back aplicó algún ajuste
        }),
      ),
    )
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText(/Total de la factura/)).toHaveTextContent(/500/)
    expect(screen.queryByText(/550/)).not.toBeInTheDocument()
  })

  // ----- Estados de carga / error / 404 -----
  it('muestra "no encontrado" ante un 404', async () => {
    server.use(
      warehouseInvoiceDetailError(404, { code: 'WH-003', detail: 'La entrada 999 no existe' }),
    )
    renderDetalle('999')
    expect(await screen.findByText(/no se encontró la entrada/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ir a entradas/i })).toBeInTheDocument()
  })

  it('no llama al backend si el id no es numérico', async () => {
    const requests: string[] = []
    const listener = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', listener)
    renderDetalle('abc')
    expect(await screen.findByText(/no se encontró la entrada/i)).toBeInTheDocument()
    expect(requests.filter((url) => url.includes('/purchase-invoices/'))).toHaveLength(0)
    server.events.removeListener('request:start', listener)
  })

  it('muestra el error del backend y permite reintentar', async () => {
    server.use(warehouseInvoiceDetailError(500, { detail: 'El servidor falló.' }))
    renderDetalle()
    expect(await screen.findByText('El servidor falló.', {}, { timeout: 3000 })).toBeInTheDocument()
    server.use(warehouseInvoiceDetail())
    await userEvent.setup().click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  // ----- Anular: visibilidad -----
  it('muestra el botón Anular si la factura está activa', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    expect(await screen.findByRole('button', { name: /anular/i })).toBeEnabled()
  })

  it('no muestra el botón Anular si la factura ya está anulada', async () => {
    server.use(warehouseInvoiceDetail(CANCELLED_INVOICE))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('button', { name: /anular/i })).not.toBeInTheDocument()
  })

  it('el botón Editar (solo si activa) lleva a la pantalla de edición', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('link', { name: /editar/i }))
    expect(screen.getByText('EDITAR STUB')).toBeInTheDocument()
  })

  it('no muestra el botón Editar si la factura ya está anulada', async () => {
    server.use(warehouseInvoiceDetail(CANCELLED_INVOICE))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('link', { name: /editar/i })).not.toBeInTheDocument()
  })

  // ----- Anular: flujo -----
  it('abrir Anular muestra el diálogo con el campo de motivo', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    await openCancelDialog()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText(/motivo/i)).toBeInTheDocument()
  })

  it('cancelar el diálogo no llama al backend', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), cancelInvoiceSuccess(sink))
    renderDetalle()
    const user = await openCancelDialog()
    await user.click(screen.getByRole('button', { name: /^cancelar$/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(sink.body).toBeUndefined()
  })

  it('anular con un motivo válido refleja el estado anulado', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseInvoiceDetailSequence([
        { invoice: fakeInvoice(), etag: DEFAULT_INVOICE_ETAG },
        { invoice: CANCELLED_INVOICE, etag: '"v2"' },
      ]),
      cancelInvoiceSuccess(sink),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Factura cargada dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular factura/i }))
    const { toast } = await import('sonner')
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Anulada')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /anular/i })).not.toBeInTheDocument()
  })

  it('muestra el estado en vuelo mientras anula', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), cancelInvoiceSlow(sink, 60))
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Factura cargada dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular factura/i }))
    expect(await screen.findByRole('button', { name: /anulando/i })).toBeDisabled()
  })

  // ----- Validación del motivo (zod: requerido, min 10) -----
  it('exige el motivo cuando se deja vacío', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), cancelInvoiceSuccess(sink))
    renderDetalle()
    const user = await openCancelDialog()
    await user.click(screen.getByRole('button', { name: /anular factura/i }))
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('exige al menos 10 caracteres en el motivo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), cancelInvoiceSuccess(sink))
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'corto')
    await user.tab()
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  // ----- If-Match / integración del cancel -----
  it('el cancel viaja con el ETag del header, no con el updatedAt del body', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseInvoiceDetail(fakeInvoice({ updatedAt: '2026-07-02T10:00:00.39289Z' })),
      cancelInvoiceSuccess(sink),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Factura cargada dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular factura/i }))
    await waitFor(() => expect(sink.ifMatch).toBe(DEFAULT_INVOICE_ETAG))
  })

  it('el cancel envía solo el motivo en el body', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseInvoiceDetail(), cancelInvoiceSuccess(sink))
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Factura duplicada del proveedor')
    await user.click(screen.getByRole('button', { name: /anular factura/i }))
    await waitFor(() => expect(sink.body).toEqual({ reason: 'Factura duplicada del proveedor' }))
  })

  it('avisa conflicto de versión y ofrece recargar ante un 412', async () => {
    server.use(
      warehouseInvoiceDetail(),
      cancelInvoiceError(412, { code: 'COM-004', detail: 'La versión enviada no es la vigente.' }),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Factura cargada dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular factura/i }))
    expect(await screen.findByText(/La versión enviada no es la vigente/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /descartar y recargar/i })).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('muestra el mensaje del backend si anular dejaría stock negativo (409 WH-006)', async () => {
    server.use(
      warehouseInvoiceDetail(),
      cancelInvoiceError(409, {
        code: 'WH-006',
        detail: "No se puede anular: el stock de 'Filtro de aceite XYZ' quedaría negativo.",
      }),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Factura cargada dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular factura/i }))
    expect(
      await screen.findByText(/el stock de 'Filtro de aceite XYZ' quedaría negativo/),
    ).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('avisa cuando no llegó el ETag y bloquea la anulación', async () => {
    server.use(warehouseInvoiceDetailWithoutEtag())
    renderDetalle()
    await openCancelDialog()
    expect(await screen.findByText(/falta la versión de la entrada/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /anular factura/i })).toBeDisabled()
  })

  // ----- Navegación -----
  it('el BackLink vuelve al listado', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('link', { name: /volver a entradas/i }))
    expect(screen.getByText('ENTRADAS')).toBeInTheDocument()
  })

  it('la fila del ítem lleva a la ficha del producto (fila navegable, como Existencias)', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    const user = userEvent.setup()
    // La fila entera es el control accesible (role button + aria-label), no un link.
    const row = await screen.findByRole('button', { name: /ver la ficha de filtro de aceite xyz/i })
    await user.click(row)
    expect(screen.getByText('FICHA PRODUCTO')).toBeInTheDocument()
  })

  it('la fila del ítem se activa con el teclado (Enter)', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    const user = userEvent.setup()
    const row = await screen.findByRole('button', { name: /ver la ficha de filtro de aceite xyz/i })
    row.focus()
    await user.keyboard('{Enter}')
    expect(screen.getByText('FICHA PRODUCTO')).toBeInTheDocument()
  })

  // ----- A11y -----
  it('presenta la pantalla con un único h1', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('la pantalla no tiene violaciones de accesibilidad', async () => {
    server.use(warehouseInvoiceDetail())
    const { container } = renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(await axe(container)).toHaveNoViolations()
  })

  it('el diálogo de anulación no tiene violaciones de accesibilidad', async () => {
    server.use(warehouseInvoiceDetail())
    renderDetalle()
    await openCancelDialog()
    // Acotado al diálogo: `document.body` incluiría el BackLink de la página, que
    // fuera de un landmark dispara la regla `region` ajena a la accesibilidad del modal.
    const dialog = screen.getByRole('dialog')
    expect(await axe(dialog)).toHaveNoViolations()
  })
})
