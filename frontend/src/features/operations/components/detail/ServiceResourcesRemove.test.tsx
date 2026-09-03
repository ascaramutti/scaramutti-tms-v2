import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import type { ServiceStatus } from '../../../../api'
import { ServiceResources } from './ServiceResources'
import { server } from '../../../../test/mocks/server'
import {
  fakeAdditionalResource,
  fakeBaitedServiceDetail,
  fakeServiceDetail,
  removeResourceCapture,
  removeResourceConflict,
  removeResourceNotFound,
  removeResourceOk,
  removeResourceSlow,
  type RemoveResourceCaptureSink,
} from '../../../../test/mocks/handlers/operations'
import { buttonClasses } from '../../../../shared/ui/buttonClasses'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

/** Dos refuerzos DISTINGUIBLES: quitar el equivocado tiene que verse. */
const FIRST = fakeAdditionalResource()
const SECOND = fakeAdditionalResource({
  id: 52,
  driver: { id: 15, fullName: 'Luis Quispe Mamani' },
  tractor: null,
  trailer: { kind: 'TRAILER', id: 9, plate: 'Z9D-909' },
  reason: 'Se suma una carreta de apoyo para redistribuir la carga en el km 214.',
})

function renderInProgress(canOperate = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const service = fakeServiceDetail({
    status: 'IN_PROGRESS',
    additionalResources: [FIRST, SECOND],
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return render(<ServiceResources service={service} canOperate={canOperate} />, { wrapper })
}

function renderStatus(status: ServiceStatus) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const service = fakeBaitedServiceDetail(status)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  render(<ServiceResources service={service} canOperate />, { wrapper })
  return service
}

function removeButtons() {
  return screen.queryAllByRole('button', { name: /quitar refuerzo/i })
}

/** Abre la confirmación del refuerzo de `driverName`. */
async function openRemove(user: UserEvent, driverName: string) {
  const item = screen.getByText(new RegExp(driverName)).closest('li') as HTMLElement
  await user.click(within(item).getByRole('button', { name: /quitar refuerzo/i }))
}

describe('la baja de un refuerzo · cuándo se ofrece', () => {
  it('se ofrece uno por fila con el viaje en ruta', () => {
    renderInProgress()
    // Uno por fila, no dos fijos: con una sola fila el número no distinguiría.
    expect(removeButtons()).toHaveLength(2)
  })

  it('con un solo refuerzo se ofrece uno solo', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const service = fakeServiceDetail({ status: 'IN_PROGRESS', additionalResources: [FIRST] })
    render(<ServiceResources service={service} canOperate />, {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    })
    expect(removeButtons()).toHaveLength(1)
  })

  // Los cinco estados que NO la admiten, con las dos filas del cebo VISIBLES: sin el
  // cebo, la ausencia se explicaría sola por no haber refuerzos que quitar.
  it('no se ofrece con el viaje pendiente de asignación, y las filas se siguen viendo', () => {
    const service = renderStatus('PENDING_ASSIGNMENT')
    expect(service.additionalResources).toHaveLength(2)
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(screen.getByText(/Luis Quispe Mamani/)).toBeInTheDocument()
    expect(removeButtons()).toHaveLength(0)
  })

  it('no se ofrece con el viaje pendiente de inicio, y las filas se siguen viendo', () => {
    renderStatus('PENDING_START')
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(removeButtons()).toHaveLength(0)
  })

  it('no se ofrece con el viaje completado, y las filas se siguen viendo', () => {
    renderStatus('COMPLETED')
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(removeButtons()).toHaveLength(0)
  })

  it('no se ofrece con el viaje cancelado, y las filas se siguen viendo', () => {
    renderStatus('CANCELLED')
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(removeButtons()).toHaveLength(0)
  })

  it('no se ofrece con el viaje eliminado, y las filas se siguen viendo', () => {
    renderStatus('DELETED')
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(removeButtons()).toHaveLength(0)
  })

  it('el rol que no opera el viaje no la ve', () => {
    renderInProgress(false)
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(removeButtons()).toHaveLength(0)
  })

  it('el botón de quitar tiene un objetivo de clic usable', () => {
    renderInProgress()

    // El texto solo mide 16px de alto, por debajo del mínimo de un objetivo táctil.
    // Se afirma porque es una decisión que se toma mirando la pantalla, y ésta en
    // particular ya se perdió una vez al resolver un conflicto de rebase.
    const [button] = removeButtons()
    expect(button).toHaveClass('-m-1', 'p-1')
  })

  it('cada botón nombra a quién quita', () => {
    renderInProgress()
    // Tres botones "Quitar" idénticos son indistinguibles para un lector de pantalla,
    // que es justo donde el clic errado no tiene vuelta.
    expect(
      screen.getByRole('button', { name: /Quitar refuerzo: Ana Ríos Chávez/ }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /Quitar refuerzo: Luis Quispe Mamani/ }),
    ).toBeInTheDocument()
  })
})

describe('la baja de un refuerzo · la confirmación', () => {
  it('pide confirmación antes de llamar al servidor', async () => {
    const sink: RemoveResourceCaptureSink = {}
    server.use(removeResourceCapture(sink))
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')

    expect(screen.getByRole('dialog', { name: 'Quitar refuerzo' })).toBeInTheDocument()
    // La baja es física y no tiene deshacer: sin confirmación, un clic errado en una
    // lista de filas parecidas borra sin red.
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('el botón que confirma la baja se pinta como destructivo', async () => {
    // Misma razón que en las acciones de cotización: la baja es física y sin deshacer, y
    // el rojo es lo que la distingue del "Cancelar" de al lado. Sin esta aserción,
    // volverlo gris deja los otros cincuenta y un casos de esta carpeta en verde.
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')

    const dialog = screen.getByRole('dialog', { name: 'Quitar refuerzo' })
    expect(within(dialog).getByRole('button', { name: 'Quitar refuerzo' }).className).toBe(
      buttonClasses({ variant: 'danger' }),
    )
    // Y el Cancelar de al lado es el secundario: el contraste entre los dos es lo que
    // distingue la salida sin consecuencias de la que borra. Volverlo primario lo dejaba
    // tan fuerte como el destructivo, con todo el archivo en verde.
    expect(within(dialog).getByRole('button', { name: 'Cancelar' }).className).toBe(
      buttonClasses({ variant: 'secondary' }),
    )
  })

  it('confirma sobre el refuerzo que se clickeó, no sobre el primero', async () => {
    const sink: RemoveResourceCaptureSink = {}
    server.use(removeResourceCapture(sink))
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Luis Quispe Mamani')

    const dialog = screen.getByRole('dialog')
    // El diálogo nombra al SEGUNDO: con dos refuerzos indistinguibles, quitar el
    // equivocado sería invisible.
    expect(within(dialog).getByText(/Luis Quispe Mamani/)).toBeInTheDocument()
    expect(within(dialog).queryByText(/Ana Ríos Chávez/)).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Quitar refuerzo' }))
    await waitFor(() => expect(sink.calls?.[0].assignmentId).toBe('52'))
  })

  it('cancelar no llama al servidor y deja los dos refuerzos', async () => {
    const sink: RemoveResourceCaptureSink = {}
    server.use(removeResourceCapture(sink))
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(sink.calls ?? []).toHaveLength(0)
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(screen.getByText(/Luis Quispe Mamani/)).toBeInTheDocument()
  })

  it('Escape equivale a cancelar', async () => {
    const sink: RemoveResourceCaptureSink = {}
    server.use(removeResourceCapture(sink))
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')
    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(sink.calls ?? []).toHaveLength(0)
  })

  it('el motivo y quién lo cargó se repiten en la confirmación', async () => {
    server.use(removeResourceOk())
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')

    const dialog = screen.getByRole('dialog')
    // Es la información que evita el error: sin ella, dos refuerzos del mismo
    // conductor no se distinguen.
    expect(within(dialog).getByText(FIRST.reason)).toBeInTheDocument()
    expect(within(dialog).getByText(/Jorge Vega/)).toBeInTheDocument()
  })

  it('al confirmar avisa y cierra', async () => {
    server.use(removeResourceOk())
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Quitar refuerzo' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(toast.success).toHaveBeenCalledWith('Refuerzo quitado de SRV-0077.')
  })

  it('no manda dos veces con doble clic', async () => {
    const sink: RemoveResourceCaptureSink = {}
    server.use(removeResourceSlow(sink))
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')
    const confirm = within(screen.getByRole('dialog')).getByRole('button', {
      name: 'Quitar refuerzo',
    })
    await user.click(confirm)
    await user.click(confirm)

    await waitFor(() => expect(sink.calls).toHaveLength(1))
  })

  it('un refuerzo que ya no está muestra el detalle del backend y no borra nada', async () => {
    server.use(removeResourceNotFound())
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Quitar refuerzo' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'El recurso adicional no existe o no pertenece a este servicio.',
    )
    // Las dos filas siguen en la FICHA: el error no las saca de la pantalla. Se acota
    // a la tarjeta porque el diálogo, que sigue abierto, también nombra al refuerzo.
    const card = screen.getByRole('region', { name: 'Refuerzos' })
    expect(within(card).getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(within(card).getByText(/Luis Quispe Mamani/)).toBeInTheDocument()
  })

  it('un estado que no admite la baja muestra el detalle del backend', async () => {
    server.use(removeResourceConflict('OPS-006', 'El estado del servicio no admite esta acción'))
    const user = userEvent.setup()
    renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Quitar refuerzo' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'El estado del servicio no admite esta acción',
    )
  })

  it('no tiene violaciones con la confirmación abierta', async () => {
    server.use(removeResourceOk())
    const user = userEvent.setup()
    const { baseElement } = renderInProgress()

    await openRemove(user, 'Ana Ríos Chávez')

    expect(await axe(baseElement)).toHaveNoViolations()
  })
})
