import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))
import { WithdrawalDetailPage } from './WithdrawalDetailPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { UpdateCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  AUDIT_USER,
  cancelWithdrawalError,
  cancelWithdrawalSlow,
  cancelWithdrawalSuccess,
  DEFAULT_WITHDRAWAL_ETAG,
  fakeCancelledWithdrawal,
  fakeWithdrawal,
  warehouseWithdrawalDetail,
  warehouseWithdrawalDetailError,
  warehouseWithdrawalDetailSequence,
  warehouseWithdrawalDetailSlow,
  warehouseWithdrawalDetailWithoutEtag,
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
        <MemoryRouter initialEntries={[`/cotizaciones/almacen/retiros/${id}`]}>
          <Routes>
            <Route path="/cotizaciones/almacen/retiros" element={<div>RETIROS</div>} />
            <Route path="/cotizaciones/almacen/retiros/:id" element={<WithdrawalDetailPage />} />
            <Route
              path="/cotizaciones/almacen/productos/:id"
              element={<div>FICHA PRODUCTO</div>}
            />
            <Route
              path="/cotizaciones/almacen/retiros/:id/editar"
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

const CANCELLED_WITHDRAWAL = fakeCancelledWithdrawal()

describe('WithdrawalDetailPage', () => {
  // ----- Render de la cabecera y la ficha -----
  it('muestra el spinner mientras carga el retiro', async () => {
    server.use(warehouseWithdrawalDetailSlow(fakeWithdrawal(), 40))
    renderDetalle()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('renderiza la cabecera del retiro', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      'Retiro de Filtro de aceite XYZ',
    )
    expect(screen.getByText(/4 UND · retirado el 10\/07\/2026/)).toBeInTheDocument()
    expect(screen.getByText('PRO-0001 · UND')).toBeInTheDocument()
  })

  it('muestra el destino: quién recibió y la unidad de flota', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText('Juan Pérez Gómez · Chofer')).toBeInTheDocument()
    expect(screen.getByText('Tracto ABC-123')).toBeInTheDocument()
  })

  it('muestra un guion cuando el retiro no tiene unidad de flota', async () => {
    server.use(warehouseWithdrawalDetail(fakeWithdrawal({ fleetUnit: undefined })))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.queryByText(/ABC-123/)).not.toBeInTheDocument()
  })

  it('muestra las observaciones cuando existen', async () => {
    server.use(
      warehouseWithdrawalDetail(fakeWithdrawal({ observations: 'Cambio de aceite del tracto.' })),
    )
    renderDetalle()
    expect(await screen.findByText('Cambio de aceite del tracto.')).toBeInTheDocument()
  })

  it('muestra quién registró el retiro y cuándo', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText(/Admin TMS · 10\/07\/2026/)).toBeInTheDocument()
  })

  it('muestra el rastro de la última edición cuando el retiro fue editado', async () => {
    server.use(
      warehouseWithdrawalDetail(
        fakeWithdrawal({
          lastEdit: {
            by: AUDIT_USER,
            at: '2026-07-11T15:00:00Z',
            reason: 'Se corrigió la cantidad retirada',
          },
        }),
      ),
    )
    renderDetalle()
    expect(await screen.findByText('Última edición')).toBeInTheDocument()
    expect(screen.getByText('Se corrigió la cantidad retirada')).toBeInTheDocument()
  })

  it('no muestra el bloque de última edición si el retiro nunca se editó', async () => {
    // El fixture base no trae `lastEdit`: el bloque no debe pintarse.
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByText('Última edición')).not.toBeInTheDocument()
  })

  it('distingue un retiro anulado con su motivo y quién lo anuló', async () => {
    server.use(warehouseWithdrawalDetail(CANCELLED_WITHDRAWAL))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText('Anulado')).toBeInTheDocument()
    expect(screen.getByText(/Anulado por Admin TMS · 12\/07\/2026/)).toBeInTheDocument()
    expect(screen.getByText(/Retiro cargado dos veces por error/)).toBeInTheDocument()
  })

  it('no muestra el bloque de anulación si el retiro está activo', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByText('Anulado')).not.toBeInTheDocument()
  })

  // ----- Estados de carga / error / 404 -----
  it('muestra "no encontrado" ante un 404', async () => {
    server.use(
      warehouseWithdrawalDetailError(404, { code: 'WH-003', detail: 'El retiro 999 no existe' }),
    )
    renderDetalle('999')
    expect(await screen.findByText(/no se encontró el retiro/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ir a retiros/i })).toBeInTheDocument()
  })

  it('no llama al backend si el id no es numérico', async () => {
    const requests: string[] = []
    const listener = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', listener)
    renderDetalle('abc')
    expect(await screen.findByText(/no se encontró el retiro/i)).toBeInTheDocument()
    // Con la barra final para no contar un eventual GET del listado (/withdrawals).
    expect(requests.filter((url) => url.includes('/withdrawals/'))).toHaveLength(0)
    server.events.removeListener('request:start', listener)
  })

  it('muestra el error del backend y permite reintentar', async () => {
    server.use(warehouseWithdrawalDetailError(500, { detail: 'El servidor falló.' }))
    renderDetalle()
    expect(await screen.findByText('El servidor falló.', {}, { timeout: 3000 })).toBeInTheDocument()
    server.use(warehouseWithdrawalDetail())
    await userEvent.setup().click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  // ----- Anular: visibilidad -----
  it('muestra el botón Anular si el retiro está activo', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    expect(await screen.findByRole('button', { name: /anular/i })).toBeEnabled()
  })

  it('no muestra el botón Anular si el retiro ya está anulado', async () => {
    server.use(warehouseWithdrawalDetail(CANCELLED_WITHDRAWAL))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('button', { name: /anular/i })).not.toBeInTheDocument()
  })

  it('el botón Editar (solo si activo) lleva a la pantalla de edición', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('link', { name: /editar/i }))
    expect(screen.getByText('EDITAR STUB')).toBeInTheDocument()
  })

  it('no muestra el botón Editar si el retiro ya está anulado', async () => {
    server.use(warehouseWithdrawalDetail(CANCELLED_WITHDRAWAL))
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('link', { name: /editar/i })).not.toBeInTheDocument()
  })

  // ----- Anular: flujo -----
  it('abrir Anular muestra el diálogo con el campo de motivo', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await openCancelDialog()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText(/motivo/i)).toBeInTheDocument()
  })

  it('el diálogo advierte que anular devuelve el stock al almacén', async () => {
    // El copy es el INVERSO al de entradas (allá anular descuenta lo que la factura
    // sumó): un espejo mecánico de EntryCancelModal lo diría al revés.
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await openCancelDialog()
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent(/devuelve al stock/i)
    expect(dialog).toHaveTextContent(/no se puede deshacer/i)
    expect(dialog).not.toHaveTextContent(/descuenta del stock/i)
  })

  it('cancelar el diálogo no llama al backend', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), cancelWithdrawalSuccess(sink))
    renderDetalle()
    const user = await openCancelDialog()
    await user.click(screen.getByRole('button', { name: /^cancelar$/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(sink.body).toBeUndefined()
  })

  it('anular con un motivo válido refleja el estado anulado', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseWithdrawalDetailSequence([
        { withdrawal: fakeWithdrawal(), etag: DEFAULT_WITHDRAWAL_ETAG },
        { withdrawal: CANCELLED_WITHDRAWAL, etag: '"v2"' },
      ]),
      cancelWithdrawalSuccess(sink),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Retiro cargado dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    const { toast } = await import('sonner')
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Anulado')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /anular/i })).not.toBeInTheDocument()
  })

  it('muestra el estado en vuelo mientras anula', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), cancelWithdrawalSlow(sink, 60))
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Retiro cargado dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    expect(await screen.findByRole('button', { name: /anulando/i })).toBeDisabled()
  })

  // ----- Validación del motivo (zod: requerido, min 10) -----
  it('exige el motivo cuando se deja vacío', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), cancelWithdrawalSuccess(sink))
    renderDetalle()
    const user = await openCancelDialog()
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('exige al menos 10 caracteres en el motivo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), cancelWithdrawalSuccess(sink))
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
      warehouseWithdrawalDetail(fakeWithdrawal({ updatedAt: '2026-07-10T09:00:00.39289Z' })),
      cancelWithdrawalSuccess(sink),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Retiro cargado dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    await waitFor(() => expect(sink.ifMatch).toBe(DEFAULT_WITHDRAWAL_ETAG))
  })

  it('el cancel envía solo el motivo en el body', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), cancelWithdrawalSuccess(sink))
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), '  Retiro duplicado del turno noche  ')
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    await waitFor(() =>
      expect(sink.body).toEqual({ reason: 'Retiro duplicado del turno noche' }),
    )
  })

  it('avisa conflicto de versión y ofrece recargar ante un 412', async () => {
    server.use(
      warehouseWithdrawalDetail(),
      cancelWithdrawalError(412, {
        code: 'COM-004',
        detail: 'La versión enviada no es la vigente.',
      }),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Retiro cargado dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    expect(await screen.findByText(/La versión enviada no es la vigente/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /descartar y recargar/i })).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('muestra el mensaje del backend si el retiro ya estaba anulado (409 WH-008)', async () => {
    server.use(
      warehouseWithdrawalDetail(),
      cancelWithdrawalError(409, {
        code: 'WH-008',
        detail: 'El retiro está anulado: no se puede editar ni volver a anular',
      }),
    )
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Retiro cargado dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    expect(await screen.findByText(/no se puede editar ni volver a anular/)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('cae al mensaje de respaldo cuando el error no trae detalle', async () => {
    server.use(warehouseWithdrawalDetail(), cancelWithdrawalError(500, { detail: undefined }))
    renderDetalle()
    const user = await openCancelDialog()
    await user.type(screen.getByLabelText(/motivo/i), 'Retiro cargado dos veces por error')
    await user.click(screen.getByRole('button', { name: /anular retiro/i }))
    expect(await screen.findByText(/No se pudo anular el retiro/)).toBeInTheDocument()
  })

  it('avisa cuando no llegó el ETag y bloquea la anulación', async () => {
    server.use(warehouseWithdrawalDetailWithoutEtag())
    renderDetalle()
    await openCancelDialog()
    expect(await screen.findByText(/falta la versión del retiro/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /anular retiro/i })).toBeDisabled()
  })

  // ----- Navegación -----
  it('el BackLink vuelve al listado', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('link', { name: /volver a retiros/i }))
    expect(screen.getByText('RETIROS')).toBeInTheDocument()
  })

  it('el producto lleva a su ficha', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('link', { name: 'Filtro de aceite XYZ' }))
    expect(screen.getByText('FICHA PRODUCTO')).toBeInTheDocument()
  })

  it('el producto se activa con el teclado (Enter)', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    const user = userEvent.setup()
    const link = await screen.findByRole('link', { name: 'Filtro de aceite XYZ' })
    link.focus()
    await user.keyboard('{Enter}')
    expect(screen.getByText('FICHA PRODUCTO')).toBeInTheDocument()
  })

  // ----- A11y -----
  it('presenta la pantalla con un único h1', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('la pantalla no tiene violaciones de accesibilidad', async () => {
    server.use(warehouseWithdrawalDetail())
    const { container } = renderDetalle()
    await screen.findByRole('heading', { level: 1 })
    expect(await axe(container)).toHaveNoViolations()
  })

  it('el diálogo de anulación no tiene violaciones de accesibilidad', async () => {
    server.use(warehouseWithdrawalDetail())
    renderDetalle()
    await openCancelDialog()
    // Acotado al diálogo: `document.body` incluiría el BackLink de la página, que
    // fuera de un landmark dispara la regla `region` ajena a la accesibilidad del modal.
    const dialog = screen.getByRole('dialog')
    expect(await axe(dialog)).toHaveNoViolations()
  })
})
