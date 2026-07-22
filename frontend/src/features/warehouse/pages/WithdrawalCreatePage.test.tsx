import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { WithdrawalCreatePage } from './WithdrawalCreatePage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type {
  BodyCaptureSink,
  ProductsCaptureSink,
} from '../../../test/mocks/handlers/warehouse'
import {
  createWithdrawalCapture,
  createWithdrawalError,
  createWithdrawalSlow,
  fakeProduct,
  warehouseProductsCapture,
  warehouseProductsPage,
  warehouseProductStock,
} from '../../../test/mocks/handlers/warehouse'
import {
  fakeFleetUnit,
  fleetUnitsError,
  fleetUnitsList,
  workersSearchCapture,
  workersSearchError,
  workersSearchPage,
} from '../../../test/mocks/handlers/shared-catalogs'

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
        <MemoryRouter initialEntries={['/cotizaciones/almacen/retiros/nuevo']}>
          <Routes>
            <Route path="/cotizaciones/almacen/retiros/nuevo" element={<WithdrawalCreatePage />} />
            <Route path="/cotizaciones/almacen/retiros" element={<div>LISTADO STUB</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

async function selectProduct(user: UserEvent, name = 'Filtro de aceite XYZ') {
  await user.type(screen.getByLabelText('Producto'), 'fil')
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(name))
}

async function selectWorker(user: UserEvent, name = 'Juan Pérez Gómez') {
  await user.type(screen.getByLabelText('Recibido por'), 'juan')
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(name))
}

async function selectFleetUnit(user: UserEvent, name = 'Tracto ABC-123') {
  await user.click(screen.getByLabelText(/unidad de flota/i))
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(name))
}

/** Carga un retiro mínimo válido: producto, cantidad y trabajador receptor. */
async function fillMinimalWithdrawal(user: UserEvent) {
  await selectProduct(user)
  await user.type(screen.getByLabelText('Cantidad'), '3')
  await selectWorker(user)
}

describe('WithdrawalCreatePage', () => {
  // ----- Render -----
  it('renderiza los campos del formulario', () => {
    renderRegistro()
    expect(screen.getByLabelText('Producto')).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad')).toBeInTheDocument()
    expect(screen.getByLabelText('Recibido por')).toBeInTheDocument()
    expect(screen.getByLabelText(/unidad de flota/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/observaciones/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /registrar retiro/i })).toBeInTheDocument()
  })

  it('la cantidad arranca vacía (un 0 no debe pasar como cantidad)', () => {
    renderRegistro()
    expect(screen.getByLabelText('Cantidad')).toHaveValue(null)
  })

  // ----- Búsqueda de producto -----
  it('no busca productos con menos de 3 caracteres', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Producto'), 'fi')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('busca productos activos con 3 o más caracteres', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Producto'), 'fil')
    await waitFor(() => {
      expect(sink.params?.get('q')).toBe('fil')
      expect(sink.params?.get('isActive')).toBe('true')
    })
  })

  it('muestra código y unidad del producto y no ofrece crearlo al vuelo', async () => {
    server.use(warehouseProductsPage([fakeProduct()]))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Producto'), 'fil')
    expect(await screen.findByText('PRO-0001 · UND')).toBeInTheDocument()
    // Un retiro solo descuenta stock existente: no hay alta de producto al vuelo.
    expect(screen.queryByRole('button', { name: /nuevo producto/i })).not.toBeInTheDocument()
  })

  it('muestra el stock disponible del producto elegido', async () => {
    server.use(warehouseProductsPage([fakeProduct()]), warehouseProductStock({ stock: 12 }))
    const user = userEvent.setup()
    renderRegistro()
    await selectProduct(user)
    expect(await screen.findByText(/disponible: 12 und/i)).toBeInTheDocument()
  })

  // ----- Búsqueda de trabajador -----
  it('no busca trabajadores con menos de 3 caracteres', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Recibido por'), 'ju')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('busca trabajadores multi-palabra con 3 o más caracteres', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(workersSearchCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Recibido por'), 'juan perez')
    await waitFor(() => {
      expect(sink.params?.get('q')).toBe('juan perez')
      expect(sink.params?.get('isActive')).toBe('true')
    })
  })

  it('el combobox de trabajador no ofrece crear al vuelo', async () => {
    server.use(workersSearchPage([]))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Recibido por'), 'zzz')
    expect(await screen.findByText(/no se encontraron trabajadores/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo trabajador/i })).not.toBeInTheDocument()
  })

  it('avisa cuando la búsqueda de trabajadores falla, sin romper el resto del form', async () => {
    server.use(workersSearchError(500))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Recibido por'), 'juan')
    expect(await screen.findByText(/no se pudieron buscar trabajadores/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad')).toBeEnabled()
  })

  // ----- Unidad de flota (opcional, disyunta) -----
  it('el combobox de unidad lista los tres subtipos y no ofrece crear al vuelo', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await user.click(screen.getByLabelText(/unidad de flota/i))
    expect(await screen.findByText('Tracto ABC-123')).toBeInTheDocument()
    expect(screen.getByText('Carreta XY-9876')).toBeInTheDocument()
    expect(screen.getByText('Escolta ES-100')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nueva unidad/i })).not.toBeInTheDocument()
  })

  it('avisa cuando la carga de unidades falla; el retiro se registra sin unidad', async () => {
    server.use(fleetUnitsError(500))
    const user = userEvent.setup()
    renderRegistro()
    await user.click(screen.getByLabelText(/unidad de flota/i))
    expect(await screen.findByText(/no se pudieron cargar las unidades/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad')).toBeEnabled()
  })

  // ----- Validación -----
  it('exige el producto cuando no se eligió', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await user.type(screen.getByLabelText('Cantidad'), '3')
    await selectWorker(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(await screen.findByText('Selecciona el producto')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('exige la cantidad', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await selectProduct(user)
    await selectWorker(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(await screen.findByText('Indica la cantidad')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('exige una cantidad mayor a 0', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad'), '0')
    await selectWorker(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(await screen.findByText('La cantidad debe ser mayor a 0')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('exige el trabajador receptor', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad'), '3')
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(await screen.findByText('Indica quién recibe')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('la unidad de flota ausente es válida', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    await waitFor(() => expect(sink.calls ?? []).toHaveLength(1))
  })

  it('no envía el formulario incompleto y marca los campos que faltan', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(await screen.findByText('Selecciona el producto')).toBeInTheDocument()
    expect(screen.getByText('Indica la cantidad')).toBeInTheDocument()
    expect(screen.getByText('Indica quién recibe')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  // ----- Submit / payload -----
  it('envía el payload del contrato al backend (con unidad de flota)', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await selectFleetUnit(user)
    await user.type(screen.getByLabelText(/observaciones/i), 'Cambio de aceite')
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    await waitFor(() =>
      expect(sink.body).toEqual({
        productId: 1,
        quantity: 3,
        receivedByWorkerId: 8,
        tractorId: 5,
        trailerId: null,
        escortVehicleId: null,
        observations: 'Cambio de aceite',
      }),
    )
  })

  it('envía null en las unidades no elegidas y en observaciones vacías', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    await waitFor(() =>
      expect(sink.body).toEqual({
        productId: 1,
        quantity: 3,
        receivedByWorkerId: 8,
        tractorId: null,
        trailerId: null,
        escortVehicleId: null,
        observations: null,
      }),
    )
  })

  it('resuelve la unidad por (kind, id): con ids colisionados manda el campo correcto', async () => {
    // Un tracto y una carreta con el MISMO id (5): elegir la carreta debe mandar
    // trailerId, no tractorId. Resolver por id solo devolvería el tracto homónimo.
    server.use(
      fleetUnitsList([
        fakeFleetUnit({ kind: 'TRACTOR', id: 5, plate: 'ABC-123' }),
        fakeFleetUnit({ kind: 'TRAILER', id: 5, plate: 'XY-9876' }),
      ]),
    )
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await selectFleetUnit(user, 'Carreta XY-9876')
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    await waitFor(() =>
      expect(sink.body).toEqual({
        productId: 1,
        quantity: 3,
        receivedByWorkerId: 8,
        tractorId: null,
        trailerId: 5,
        escortVehicleId: null,
        observations: null,
      }),
    )
  })

  it('al registrar avisa y vuelve al listado', async () => {
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(await screen.findByText('LISTADO STUB')).toBeInTheDocument()
    const { toast } = await import('sonner')
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Filtro de aceite XYZ'))
  })

  it('no permite un segundo envío mientras la mutación está en vuelo', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalSlow(sink, 60))
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(await screen.findByRole('button', { name: /registrando…/i })).toBeDisabled()
    expect(await screen.findByText('LISTADO STUB')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(1)
  })

  it('cancelar vuelve al listado sin llamar al backend', async () => {
    const sink: BodyCaptureSink = {}
    server.use(createWithdrawalCapture(sink))
    const user = userEvent.setup()
    renderRegistro()
    await user.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(await screen.findByText('LISTADO STUB')).toBeInTheDocument()
    expect(sink.calls ?? []).toHaveLength(0)
  })

  // ----- Aviso de stock (sin bloquear) -----
  it('avisa cuando la cantidad supera el disponible, sin deshabilitar el envío', async () => {
    server.use(warehouseProductsPage([fakeProduct()]), warehouseProductStock({ stock: 4 }))
    const user = userEvent.setup()
    renderRegistro()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad'), '12')
    expect(await screen.findByText(/supera el stock disponible/i)).toBeInTheDocument()
    // La decisión del dueño: se avisa pero NO se bloquea; el 409 es la autoridad.
    expect(screen.getByRole('button', { name: /registrar retiro/i })).toBeEnabled()
  })

  // ----- Errores del backend -----
  it('muestra el stock insuficiente anclado a la cantidad, con el detalle del backend', async () => {
    server.use(
      createWithdrawalError(409, {
        code: 'WH-001',
        detail: 'Stock insuficiente: solo hay 4 UND disponibles de Filtro de aceite XYZ',
      }),
    )
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    expect(
      await screen.findByText('Stock insuficiente: solo hay 4 UND disponibles de Filtro de aceite XYZ'),
    ).toBeInTheDocument()
    // El form no navega: se corrige la cantidad y se reintenta.
    expect(screen.queryByText('LISTADO STUB')).not.toBeInTheDocument()
  })

  it('muestra el detalle del backend cuando un catálogo no es válido (WH-004)', async () => {
    server.use(
      createWithdrawalError(400, {
        code: 'WH-004',
        detail: 'El producto seleccionado no existe o está inactivo',
      }),
    )
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('El producto seleccionado no existe o está inactivo'),
    )
  })

  it('muestra el detalle WH-005 si el backend rechaza más de una unidad', async () => {
    server.use(
      createWithdrawalError(400, {
        code: 'WH-005',
        detail: 'La unidad destino admite a lo sumo una: tracto, carreta o escolta',
      }),
    )
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        'La unidad destino admite a lo sumo una: tracto, carreta o escolta',
      ),
    )
  })

  it('cae al mensaje de respaldo cuando el error no trae detalle', async () => {
    server.use(createWithdrawalError(500, { detail: undefined }))
    const user = userEvent.setup()
    renderRegistro()
    await fillMinimalWithdrawal(user)
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('No se pudo registrar el retiro. Intenta de nuevo.'),
    )
  })

  // ----- A11y -----
  it('presenta la pantalla con un único h1', () => {
    renderRegistro()
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Registrar retiro')
  })

  it('no tiene violaciones de accesibilidad con el formulario vacío', async () => {
    const { container } = renderRegistro()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad con errores visibles', async () => {
    const user = userEvent.setup()
    const { container } = renderRegistro()
    await user.click(screen.getByRole('button', { name: /registrar retiro/i }))
    await screen.findByText('Selecciona el producto')
    expect(await axe(container)).toHaveNoViolations()
  })
})
