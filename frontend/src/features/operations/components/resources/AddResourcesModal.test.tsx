import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HttpResponse, delay, http } from 'msw'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { AddResourcesModal } from './AddResourcesModal'
import { server } from '../../../../test/mocks/server'
import {
  addConflictThenOk,
  addResourcesCapture,
  addResourcesConflict,
  addResourcesDuplicate,
  addResourcesOk,
  driversList,
  fakeServiceDetail,
  type AddResourcesCaptureSink,
} from '../../../../test/mocks/handlers/operations'
import { fakeFleetUnit, fleetUnitsByKind } from '../../../../test/mocks/handlers/shared-catalogs'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

const TRACTOR = fakeFleetUnit({ kind: 'TRACTOR', id: 11, plate: 'V1B-911', brand: 'Scania' })
const TRAILER = fakeFleetUnit({ kind: 'TRAILER', id: 9, plate: 'Z9D-909', brand: 'Fameco' })
const FLEET = [TRACTOR, TRAILER]

const REASON = 'Relevo por descanso reglamentario del conductor principal'

function renderModal(onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const view = render(
    <AddResourcesModal isOpen onClose={onClose} serviceId={77} serviceCode="SRV-0077" />,
    { wrapper },
  )
  return { ...view, onClose }
}

async function pick(user: UserEvent, label: RegExp, option: string) {
  await user.click(screen.getByLabelText(label))
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(option))
}

function submit(user: UserEvent) {
  return user.click(screen.getByRole('button', { name: 'Agregar refuerzo' }))
}

async function fillValid(user: UserEvent) {
  await pick(user, /conductor adicional/i, 'Ana Ríos Chávez')
  await user.type(screen.getByLabelText(/motivo/i), REASON)
}

describe('AddResourcesModal', () => {
  it('se anuncia con su propio título, distinto del de la asignación', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    renderModal()

    // Con el mismo título que el modal de asignar, abrir el equivocado sería invisible.
    expect(screen.getByRole('dialog', { name: 'Agregar refuerzo' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText(/conductor adicional/i)).toBeInTheDocument())
  })

  it('alcanza con un solo recurso', async () => {
    const sink: AddResourcesCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesCapture(sink))
    const user = userEvent.setup()
    renderModal()

    await fillValid(user)
    await submit(user)

    await waitFor(() =>
      expect(sink.bodies?.[0]).toEqual({
        driverId: 8,
        tractorId: null,
        trailerId: null,
        reason: REASON,
        force: false,
      }),
    )
  })

  it('manda cada recurso en su propio campo', async () => {
    const sink: AddResourcesCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesCapture(sink))
    const user = userEvent.setup()
    renderModal()

    await pick(user, /conductor adicional/i, 'Ana Ríos Chávez')
    await pick(user, /tracto adicional/i, 'Tracto V1B-911')
    await pick(user, /carreta adicional/i, 'Carreta Z9D-909')
    await user.type(screen.getByLabelText(/motivo/i), REASON)
    await submit(user)

    // Los tres ids son disjuntos (8 · 11 · 9), así que cruzar dos campos cambia el
    // objeto. Sin este caso, mandar el id del tracto en `trailerId` pasa verde: el
    // servidor lo acepta porque los tres son opcionales, y el refuerzo queda con la
    // carreta equivocada.
    await waitFor(() =>
      expect(sink.bodies?.[0]).toEqual({
        driverId: 8,
        tractorId: 11,
        trailerId: 9,
        reason: REASON,
        force: false,
      }),
    )
  })

  it('cambiar de recurso descarta el conflicto anterior', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesConflict())
    const user = userEvent.setup()
    renderModal()

    await fillValid(user)
    await submit(user)
    expect(
      await screen.findByRole('button', { name: 'Agregar de todos modos' }),
    ).toBeInTheDocument()

    await pick(user, /tracto adicional/i, 'Tracto V1B-911')

    // Sin esta limpieza queda un botón que fuerza sobre un recurso que ya cambió.
    expect(screen.queryByRole('button', { name: 'Agregar de todos modos' })).not.toBeInTheDocument()
  })

  it('sin ningún recurso no manda y lo dice UNA sola vez', async () => {
    const sink: AddResourcesCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesCapture(sink))
    const user = userEvent.setup()
    renderModal()

    await user.type(screen.getByLabelText(/motivo/i), REASON)
    await submit(user)

    const message = 'Elige al menos un conductor, tracto o carreta'
    expect(await screen.findByText(message)).toBeInTheDocument()
    // Es un error del GRUPO: repetido bajo los tres campos diría que cada uno es
    // obligatorio, que es justo lo contrario de la regla.
    expect(screen.getAllByText(message)).toHaveLength(1)
    expect(sink.bodies).toEqual([])
  })

  it('diez espacios no habilitan el envío', async () => {
    const sink: AddResourcesCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesCapture(sink))
    const user = userEvent.setup()
    renderModal()

    await pick(user, /conductor adicional/i, 'Ana Ríos Chávez')
    await user.type(screen.getByLabelText(/motivo/i), '          ')
    await submit(user)

    // El trim medido en la pantalla, no solo en el schema.
    expect(await screen.findByText(/al menos 10 caracteres/i)).toBeInTheDocument()
    expect(sink.bodies).toEqual([])
  })

  it('el contador cuenta lo que hay escrito', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    renderModal()

    await user.type(screen.getByLabelText(/motivo/i), REASON)

    // 57 caracteres: ni 10 ni 500, los dos literales de los mensajes de validación,
    // con los que el contador no se distinguiría de un límite impreso.
    expect(REASON).toHaveLength(57)
    expect(screen.getByText('57/500')).toBeInTheDocument()
  })

  it('el error del motivo se anuncia y queda atado a su campo', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    renderModal()

    await pick(user, /conductor adicional/i, 'Ana Ríos Chávez')
    await submit(user)

    const field = screen.getByLabelText(/motivo/i)
    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'))
    const describedBy = field.getAttribute('aria-describedby')
    expect(describedBy).toBeTruthy()
  })

  it('un conflicto DURO no ofrece forzar', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesDuplicate())
    const user = userEvent.setup()
    renderModal()

    await fillValid(user)
    await submit(user)

    const alert = await screen.findByRole('alert')
    // El conflicto duro llega SIN tabla, así que el aviso muestra el texto del
    // servidor y no el encabezado genérico: no hay nada que resumir y esa frase es la
    // única que nombra el recurso que rebotó.
    expect(alert).toHaveTextContent('El conductor Ana Ríos Chávez ya participa de este servicio.')
    expect(alert).not.toHaveTextContent('Uno o más recursos')
    // El negativo explícito, y contra el `Problem` PELADO que es la forma real: es la
    // única defensa contra un forzado que colaría el duplicado que el servidor
    // declara no forzable.
    expect(screen.queryByRole('button', { name: /de todos modos/i })).not.toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('un conflicto forzable SÍ ofrece forzar, en el mismo endpoint', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesConflict())
    const user = userEvent.setup()
    renderModal()

    await fillValid(user)
    await submit(user)

    // Este caso y el anterior son el par que da sentido a los dos: el mismo modal, el
    // mismo 409, dos comportamientos. Con uno solo, la pantalla podría ofrecer forzar
    // siempre, o no ofrecerlo nunca.
    expect(
      await screen.findByRole('button', { name: 'Agregar de todos modos' }),
    ).toBeInTheDocument()
  })

  it('forzar reenvía el mismo cuerpo y el motivo sobrevive intacto', async () => {
    const sink: AddResourcesCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), addConflictThenOk(sink))
    const user = userEvent.setup()
    const { onClose } = renderModal()

    await fillValid(user)
    await submit(user)
    await user.click(await screen.findByRole('button', { name: 'Agregar de todos modos' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(2))
    // El motivo es el campo largo y el que más fácil se pierde al rearmar el cuerpo
    // dentro del handler del botón.
    expect(sink.bodies?.[1]).toEqual({ ...sink.bodies?.[0], force: true })
    expect(sink.bodies?.[1].reason).toBe(REASON)
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('al agregar avisa y cierra', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesOk())
    const user = userEvent.setup()
    const { onClose } = renderModal()

    await fillValid(user)
    await submit(user)

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(toast.success).toHaveBeenCalledWith('Refuerzo agregado a SRV-0077.')
  })

  it('una caída de red usa el mensaje propio', async () => {
    server.use(
      fleetUnitsByKind(FLEET),
      driversList(),
      http.post('http://localhost:8080/api/v1/services/:id/resources', () => HttpResponse.error()),
    )
    const user = userEvent.setup()
    renderModal()

    await fillValid(user)
    await submit(user)

    // Es la única salida del usuario cuando el backend no dice nada, y hasta acá no
    // lo leía ningún test. Inventar el texto solo es correcto en este caso.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No se pudo agregar el refuerzo. Intenta de nuevo.',
    )
  })

  it('no manda dos veces con doble clic', async () => {
    const sink: AddResourcesCaptureSink = {}
    server.use(
      fleetUnitsByKind(FLEET),
      driversList(),
      http.post('http://localhost:8080/api/v1/services/:id/resources', async ({ request }) => {
        sink.bodies = [...(sink.bodies ?? []), (await request.json()) as never]
        await delay(40)
        return HttpResponse.json(fakeServiceDetail({ status: 'IN_PROGRESS' }), { status: 200 })
      }),
    )
    const user = userEvent.setup()
    renderModal()

    await fillValid(user)
    const button = screen.getByRole('button', { name: 'Agregar refuerzo' })
    await user.click(button)
    await user.click(button)

    // Los otros dos formularios del PR lo tienen; éste era el que faltaba.
    await waitFor(() => expect(sink.bodies).toHaveLength(1))
  })

  it('no tiene violaciones con el conflicto en pantalla', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), addResourcesConflict())
    const user = userEvent.setup()
    const { baseElement } = renderModal()

    await fillValid(user)
    await submit(user)
    await screen.findByRole('alert')

    expect(await axe(baseElement)).toHaveNoViolations()
  })
})
