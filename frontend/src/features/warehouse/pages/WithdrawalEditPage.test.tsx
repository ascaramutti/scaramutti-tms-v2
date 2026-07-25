import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { WithdrawalEditPage } from './WithdrawalEditPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type {
  ProductsCaptureSink,
  UpdateCaptureSink,
} from '../../../test/mocks/handlers/warehouse'
import {
  DEFAULT_WITHDRAWAL_ETAG,
  fakeWithdrawal,
  updateWithdrawalError,
  updateWithdrawalSlow,
  updateWithdrawalSuccess,
  warehouseProductStock,
  warehouseProductStockCapture,
  warehouseWithdrawalDetail,
  warehouseWithdrawalDetailError,
  warehouseWithdrawalDetailSequence,
  warehouseWithdrawalDetailSlow,
  warehouseWithdrawalDetailWithoutEtag,
} from '../../../test/mocks/handlers/warehouse'
import { fakeFleetUnit, fleetUnitsList } from '../../../test/mocks/handlers/shared-catalogs'

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
        <MemoryRouter initialEntries={[`/cotizaciones/almacen/retiros/${id}/editar`]}>
          <Routes>
            <Route path="/cotizaciones/almacen/retiros/:id/editar" element={<WithdrawalEditPage />} />
            <Route path="/cotizaciones/almacen/retiros/:id" element={<div>DETALLE STUB</div>} />
            <Route path="/cotizaciones/almacen/retiros" element={<div>RETIROS STUB</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Espera a que el form esté montado (GET del detalle resuelto). */
async function waitForForm() {
  return screen.findByLabelText('Cantidad')
}

async function typeReason(user: UserEvent, reason = 'Corregí la cantidad retirada') {
  await user.type(screen.getByLabelText(/motivo de edición/i), reason)
}

/**
 * Los dos comboboxes del destino llegan con valor, así que muestran el chip de la
 * selección con su botón de quitar. El orden del DOM es receptor y después unidad.
 */
function clearButtons() {
  return screen.getAllByRole('button', { name: /quitar selección/i })
}

async function clearWorker(user: UserEvent) {
  await user.click(clearButtons()[0])
}

async function clearFleetUnit(user: UserEvent) {
  const buttons = clearButtons()
  await user.click(buttons[buttons.length - 1])
}

const CANCELLED_WITHDRAWAL = fakeWithdrawal({
  status: 'CANCELLED',
  cancelReason: 'Retiro duplicado del turno noche',
})

describe('WithdrawalEditPage', () => {
  // ----- Prefill -----
  it('muestra el spinner mientras carga el detalle para el prefill', async () => {
    server.use(warehouseWithdrawalDetailSlow(fakeWithdrawal(), 40))
    renderEditar()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    await waitForForm()
  })

  it('prefillea la cantidad, el receptor y la unidad de flota', async () => {
    server.use(warehouseWithdrawalDetail())
    renderEditar()
    expect(await waitForForm()).toHaveValue(4)
    // Con valor elegido el combobox muestra el chip de la selección (no un input).
    expect(screen.getByText('Juan Pérez Gómez')).toBeInTheDocument()
    expect(screen.getByText('Tracto ABC-123')).toBeInTheDocument()
  })

  it('prefillea las observaciones cuando existen', async () => {
    server.use(warehouseWithdrawalDetail(fakeWithdrawal({ observations: 'Cambio de aceite' })))
    renderEditar()
    await waitForForm()
    expect(screen.getByLabelText(/observaciones/i)).toHaveValue('Cambio de aceite')
  })

  it('deja las observaciones vacías cuando el retiro no las tiene', async () => {
    // `null` del backend no debe pintar "null" en el textarea.
    server.use(warehouseWithdrawalDetail())
    renderEditar()
    await waitForForm()
    expect(screen.getByLabelText(/observaciones/i)).toHaveValue('')
  })

  it('deja la unidad de flota vacía cuando el retiro no la tiene', async () => {
    server.use(warehouseWithdrawalDetail(fakeWithdrawal({ fleetUnit: undefined })))
    renderEditar()
    await waitForForm()
    // Sin unidad el combobox queda en modo búsqueda, sin placa elegida.
    expect(screen.getByLabelText(/unidad de flota/i)).toHaveValue('')
    expect(screen.queryByText(/ABC-123/)).not.toBeInTheDocument()
  })

  it('muestra el producto como solo lectura, sin buscador', async () => {
    server.use(warehouseWithdrawalDetail())
    renderEditar()
    await waitForForm()
    // RN-WH4: el producto es inmutable, así que no hay combobox ni alta al vuelo.
    expect(screen.queryByLabelText('Producto')).not.toBeInTheDocument()
    expect(screen.getByText(/Filtro de aceite XYZ · PRO-0001 · UND/)).toBeInTheDocument()
    expect(screen.getByText(/anula el retiro y registra otro/i)).toBeInTheDocument()
  })

  it('presenta la pantalla con un único h1', async () => {
    server.use(warehouseWithdrawalDetail())
    renderEditar()
    await waitForForm()
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Editar retiro de Filtro de aceite XYZ')
  })

  // ----- El disponible de la edición incluye lo ya retirado acá -----
  it('suma la cantidad de este retiro al disponible que muestra', async () => {
    // El endpoint devuelve el stock con este retiro YA descontado (12), y el backend
    // valida contra el disponible SIN contarlo: 12 + 4 = 16.
    server.use(warehouseWithdrawalDetail(), warehouseProductStock({ stock: 12 }))
    renderEditar()
    await waitForForm()
    expect(await screen.findByText(/Disponible para este retiro: 16 UND/)).toBeInTheDocument()
    expect(screen.getByText(/incluye lo ya retirado acá/i)).toBeInTheDocument()
  })

  it('no avisa de exceso al bajar la cantidad, aunque supere el stock crudo', async () => {
    // Stock crudo 2 (este retiro de 4 ya descontado) → disponible real 6. Bajar a 3
    // es válido y no debe avisar nada.
    server.use(warehouseWithdrawalDetail(), warehouseProductStock({ stock: 2 }))
    const user = userEvent.setup()
    renderEditar()
    const quantity = await waitForForm()
    await user.clear(quantity)
    await user.type(quantity, '3')
    await waitFor(() => expect(quantity).toHaveValue(3))
    expect(screen.queryByText(/supera el stock disponible/i)).not.toBeInTheDocument()
  })

  it('avisa cuando la cantidad supera el disponible real, sin bloquear el guardado', async () => {
    server.use(warehouseWithdrawalDetail(), warehouseProductStock({ stock: 2 }))
    const user = userEvent.setup()
    renderEditar()
    const quantity = await waitForForm()
    await user.clear(quantity)
    await user.type(quantity, '9')
    expect(await screen.findByText(/supera el stock disponible/i)).toBeInTheDocument()
    // La autoridad es el 409 WH-001 del backend: se avisa, no se bloquea.
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeEnabled()
  })

  it('no reconsulta el stock al cambiar la cantidad (el producto no cambia)', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), warehouseProductStockCapture(sink))
    const user = userEvent.setup()
    renderEditar()
    const quantity = await waitForForm()
    await user.clear(quantity)
    await user.type(quantity, '6')
    await waitFor(() => expect(quantity).toHaveValue(6))
    expect(sink.calls ?? []).toHaveLength(1)
  })

  // ----- Carga / error / id inválido / anulado -----
  it('muestra "no encontrado" ante un 404 al cargar el detalle', async () => {
    server.use(
      warehouseWithdrawalDetailError(404, { code: 'WH-003', detail: 'El retiro 999 no existe' }),
    )
    renderEditar('999')
    expect(await screen.findByText(/no se encontró el retiro/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ir a retiros/i })).toBeInTheDocument()
  })

  it('no llama al backend si el id no es numérico', async () => {
    const requests: string[] = []
    const listener = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', listener)
    renderEditar('abc')
    expect(await screen.findByText(/no se encontró el retiro/i)).toBeInTheDocument()
    expect(requests.filter((url) => url.includes('/withdrawals/'))).toHaveLength(0)
    server.events.removeListener('request:start', listener)
  })

  it('rebota al detalle y avisa cuando el retiro está anulado', async () => {
    server.use(warehouseWithdrawalDetail(CANCELLED_WITHDRAWAL))
    renderEditar()
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('No se puede editar un retiro anulado.'),
    )
  })

  it('muestra el error del backend y permite reintentar el prefill', async () => {
    server.use(warehouseWithdrawalDetailError(500, { detail: 'El servidor falló.' }))
    renderEditar()
    expect(await screen.findByText('El servidor falló.', {}, { timeout: 3000 })).toBeInTheDocument()
    server.use(warehouseWithdrawalDetail())
    await userEvent.setup().click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await waitForForm()).toBeInTheDocument()
  })

  it('bloquea guardar cuando no llegó el ETag del detalle', async () => {
    server.use(warehouseWithdrawalDetailWithoutEtag())
    renderEditar()
    await waitForForm()
    expect(screen.getByText(/falta la versión del retiro/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeDisabled()
  })

  // ----- Interacción -----
  it('cambiar el receptor busca trabajadores desde 3 caracteres', async () => {
    server.use(warehouseWithdrawalDetail())
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await clearWorker(user)
    await user.type(screen.getByLabelText('Recibido por'), 'ma')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
  })

  it('quitar la unidad de flota deja el combobox vacío', async () => {
    server.use(warehouseWithdrawalDetail())
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await clearFleetUnit(user)
    expect(screen.getByLabelText(/unidad de flota/i)).toHaveValue('')
    expect(screen.queryByText('Tracto ABC-123')).not.toBeInTheDocument()
  })

  // ----- Validación zod -----
  it('exige el motivo cuando se deja vacío', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('exige al menos 10 caracteres en el motivo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user, 'corto')
    await user.tab()
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('exige la cantidad cuando se borra', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await user.clear(await waitForForm())
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('Indica la cantidad')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('exige una cantidad mayor a 0', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    const quantity = await waitForForm()
    await user.clear(quantity)
    await user.type(quantity, '0')
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('La cantidad debe ser mayor a 0')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('rechaza una cantidad por encima del tope del formulario', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    const quantity = await waitForForm()
    await user.clear(quantity)
    await user.type(quantity, '10000')
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('Máximo 9999')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('exige el receptor si se limpia', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await clearWorker(user)
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('Indica quién recibe')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('rechaza observaciones de más de 500 caracteres', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await user.type(screen.getByLabelText(/observaciones/i), 'x'.repeat(501))
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('Máximo 500 caracteres')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  // ----- Integración del PUT / If-Match -----
  it('envía el body del contrato sin productId y con el motivo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() =>
      expect(sink.body).toEqual({
        quantity: 4,
        receivedByWorkerId: 8,
        tractorId: 5,
        trailerId: null,
        escortVehicleId: null,
        observations: null,
        reason: 'Corregí la cantidad retirada',
      }),
    )
    // El producto es inmutable: no viaja en el PUT.
    expect(sink.body).not.toHaveProperty('productId')
  })

  it('el PUT viaja con el ETag del header, no con el updatedAt del body', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseWithdrawalDetail(fakeWithdrawal({ updatedAt: '2026-07-10T09:00:00.39289Z' })),
      updateWithdrawalSuccess(sink),
    )
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() => expect(sink.ifMatch).toBe(DEFAULT_WITHDRAWAL_ETAG))
  })

  it('quitar la unidad de flota manda los tres campos en null', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await clearFleetUnit(user)
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() =>
      expect(sink.body).toMatchObject({
        tractorId: null,
        trailerId: null,
        escortVehicleId: null,
      }),
    )
  })

  it('cambiar de tracto a carreta manda trailerId y anula tractorId', async () => {
    // Ids colisionados a propósito: la unidad se resuelve por el par (kind, id).
    const sink: UpdateCaptureSink = {}
    server.use(
      warehouseWithdrawalDetail(),
      fleetUnitsList([
        fakeFleetUnit({ kind: 'TRACTOR', id: 5, plate: 'ABC-123' }),
        fakeFleetUnit({ kind: 'TRAILER', id: 5, plate: 'XY-9876', brand: null, model: null }),
      ]),
      updateWithdrawalSuccess(sink),
    )
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await clearFleetUnit(user)
    await user.click(screen.getByLabelText(/unidad de flota/i))
    const listbox = await screen.findByRole('listbox')
    await user.click(await within(listbox).findByText('Carreta XY-9876'))
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await waitFor(() =>
      expect(sink.body).toMatchObject({ tractorId: null, trailerId: 5, escortVehicleId: null }),
    )
  })

  it('al guardar avisa y vuelve al detalle', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    const { toast } = await import('sonner')
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Filtro de aceite XYZ'))
  })

  it('no permite un segundo envío mientras el PUT está en vuelo', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSlow(sink, 60))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByRole('button', { name: /guardando…/i })).toBeDisabled()
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
  })

  // ----- Errores de API -----
  it('ancla el stock insuficiente a la cantidad con el detalle del backend (409 WH-001)', async () => {
    server.use(
      warehouseWithdrawalDetail(),
      updateWithdrawalError(409, {
        code: 'WH-001',
        detail: 'Stock insuficiente: solo hay 6 UND disponibles (sin contar este retiro)',
      }),
    )
    const user = userEvent.setup()
    renderEditar()
    const quantity = await waitForForm()
    await user.clear(quantity)
    await user.type(quantity, '20')
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(
      await screen.findByText('Stock insuficiente: solo hay 6 UND disponibles (sin contar este retiro)'),
    ).toBeInTheDocument()
    expect(screen.queryByText('DETALLE STUB')).not.toBeInTheDocument()
  })

  it('muestra el detalle del backend si el retiro ya está anulado (409 WH-008)', async () => {
    server.use(
      warehouseWithdrawalDetail(),
      updateWithdrawalError(409, {
        code: 'WH-008',
        detail: 'El retiro está anulado: no se puede editar ni volver a anular',
      }),
    )
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        'El retiro está anulado: no se puede editar ni volver a anular',
      ),
    )
    expect(screen.queryByText('DETALLE STUB')).not.toBeInTheDocument()
  })

  it('avisa conflicto de versión y ofrece descartar y recargar (412 COM-004)', async () => {
    server.use(
      warehouseWithdrawalDetail(),
      updateWithdrawalError(412, { code: 'COM-004', detail: 'La versión enviada no es la vigente.' }),
    )
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    expect(await screen.findByText(/La versión enviada no es la vigente/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /descartar y recargar/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guardar cambios/i })).toBeInTheDocument()
  })

  it('al descartar y recargar refresca el prefill con la versión vigente', async () => {
    server.use(
      warehouseWithdrawalDetailSequence([
        { withdrawal: fakeWithdrawal(), etag: DEFAULT_WITHDRAWAL_ETAG },
        { withdrawal: fakeWithdrawal({ quantity: 7 }), etag: '"v2"' },
      ]),
      updateWithdrawalError(412, { code: 'COM-004', detail: 'Versión vencida.' }),
    )
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await user.click(await screen.findByRole('button', { name: /descartar y recargar/i }))
    await waitFor(() => expect(screen.getByLabelText('Cantidad')).toHaveValue(7))
  })

  it('muestra el detalle del backend si un catálogo no es válido (WH-004)', async () => {
    server.use(
      warehouseWithdrawalDetail(),
      updateWithdrawalError(400, {
        code: 'WH-004',
        detail: 'El trabajador receptor no existe o está inactivo',
      }),
    )
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('El trabajador receptor no existe o está inactivo'),
    )
  })

  it('cae al mensaje de respaldo cuando el error no trae detalle', async () => {
    server.use(warehouseWithdrawalDetail(), updateWithdrawalError(500, { detail: undefined }))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await typeReason(user)
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('No se pudo guardar el retiro. Intenta de nuevo.'),
    )
  })

  // ----- Navegación + a11y -----
  it('cancelar vuelve al detalle sin llamar al backend', async () => {
    const sink: UpdateCaptureSink = {}
    server.use(warehouseWithdrawalDetail(), updateWithdrawalSuccess(sink))
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /^cancelar$/i }))
    expect(await screen.findByText('DETALLE STUB')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('el BackLink vuelve al retiro', async () => {
    server.use(warehouseWithdrawalDetail())
    const user = userEvent.setup()
    renderEditar()
    await waitForForm()
    await user.click(screen.getByRole('link', { name: /volver al retiro/i }))
    expect(screen.getByText('DETALLE STUB')).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad con el formulario cargado', async () => {
    server.use(warehouseWithdrawalDetail())
    const { container } = renderEditar()
    await waitForForm()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad con errores visibles', async () => {
    server.use(warehouseWithdrawalDetail())
    const { container } = renderEditar()
    const user = userEvent.setup()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /guardar cambios/i }))
    await screen.findByText(/al menos 10 caracteres/i)
    expect(await axe(container)).toHaveNoViolations()
  })
})
