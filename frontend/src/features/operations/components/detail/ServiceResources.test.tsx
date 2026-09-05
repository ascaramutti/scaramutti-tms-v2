import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ServiceStatus } from '../../../../api'
import { ServiceResources } from './ServiceResources'
import { server } from '../../../../test/mocks/server'
import { driversList, fakeBaitedServiceDetail } from '../../../../test/mocks/handlers/operations'
import { fakeFleetUnit, fleetUnitsByKind } from '../../../../test/mocks/handlers/shared-catalogs'
import { buttonClasses } from '../../../../shared/ui/buttonClasses'

/**
 * Cada caso monta SU fixture: compartir un objeto entre casos y mutarlo es la forma
 * de aliasing que vuelve no-op un cruce y deja pasar cualquier afirmación.
 */
function renderResources(status: ServiceStatus, canOperate = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const service = fakeBaitedServiceDetail(status)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  render(<ServiceResources service={service} canOperate={canOperate} />, { wrapper })
  return service
}

/**
 * Toda afirmación de AUSENCIA se ancla primero en algo presente. Sin el ancla, un
 * componente que no renderizó nada (o que reventó) satisface cualquier "no está".
 */
function expectCardsRendered() {
  expect(screen.getByRole('heading', { name: 'Recursos asignados' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Refuerzos' })).toBeInTheDocument()
}

function assignButton() {
  return screen.queryByRole('button', { name: /asignar recursos/i })
}

function addButton() {
  return screen.queryByRole('button', { name: /agregar refuerzo/i })
}

describe('ServiceResources · el botón de asignar', () => {
  // Los seis estados, un caso por cada uno y no un bucle sobre un arreglo: cuando
  // aparezca un estado nuevo, el que falte tiene que ser un test que no existe y se
  // ve al leer la lista, no una fila que nadie agregó.
  it('se ofrece con el viaje pendiente de asignación', () => {
    renderResources('PENDING_ASSIGNMENT')
    expectCardsRendered()
    expect(assignButton()).toBeInTheDocument()
  })

  it('asignar es la acción principal de la ficha, y agregar refuerzo la secundaria', () => {
    // La jerarquía entre los dos, que es la decisión: asignar recursos es lo que falta para
    // que el viaje pueda arrancar; agregar refuerzo es opcional. Intercambiarlos deja esta
    // pantalla y el detalle del viaje en verde: medido. Van en dos estados distintos porque
    // los dos botones no se ofrecen a la vez: asignar mientras falta asignar, reforzar una
    // vez que el viaje salió.
    renderResources('PENDING_ASSIGNMENT')
    expect(assignButton()?.className).toBe(buttonClasses({ variant: 'primary' }) + ' shrink-0')
    cleanup()

    renderResources('IN_PROGRESS')
    expect(addButton()?.className).toBe(buttonClasses({ variant: 'secondary' }) + ' shrink-0')
  })

  it('no se ofrece con el viaje pendiente de inicio', () => {
    renderResources('PENDING_START')
    expectCardsRendered()
    expect(assignButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje en ruta, aunque no tenga recursos cargados', () => {
    // El fixture cebo llega SIN conductor ni tracto, que es el dato que produciría el
    // botón si la guarda mirara los recursos en vez del estado. Es el caso que separa
    // "lo decide el estado" de "lo decide el dato": sin él, cambiar la condición por
    // `driver === null` no rompería nada.
    const service = renderResources('IN_PROGRESS')
    expect(service.driver).toBeNull()
    expectCardsRendered()
    expect(assignButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje completado', () => {
    renderResources('COMPLETED')
    expectCardsRendered()
    expect(assignButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje cancelado', () => {
    renderResources('CANCELLED')
    expectCardsRendered()
    expect(assignButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje eliminado', () => {
    renderResources('DELETED')
    expectCardsRendered()
    expect(assignButton()).not.toBeInTheDocument()
  })

  it('vive dentro de la ficha de recursos, no en otra', () => {
    renderResources('PENDING_ASSIGNMENT')
    const card = screen.getByRole('region', { name: 'Recursos asignados' })
    // Ata el botón a SU ficha: mudarlo a la de refuerzos o a un encabezado global
    // rompe, y eso es lo que hoy solo se decide mirando la pantalla.
    expect(within(card).getByRole('button', { name: /asignar recursos/i })).toBeInTheDocument()
  })
})

describe('ServiceResources · el botón de agregar refuerzo', () => {
  it('se ofrece con el viaje en ruta', () => {
    renderResources('IN_PROGRESS')
    expectCardsRendered()
    expect(addButton()).toBeInTheDocument()
  })

  it('no se ofrece con el viaje pendiente de asignación, aunque ya tenga refuerzos', () => {
    // El fixture cebo llega CON dos refuerzos vivos, que es el dato que produciría el
    // botón si la guarda mirara la lista en vez del estado. Sin ese cebo, la ausencia
    // se explicaría sola por no haber refuerzos y no mediría la guarda.
    const service = renderResources('PENDING_ASSIGNMENT')
    expect(service.additionalResources).toHaveLength(2)
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
    expect(addButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje pendiente de inicio', () => {
    // Ya tiene recursos pero todavía no salió: un refuerzo refuerza a un viaje EN RUTA.
    renderResources('PENDING_START')
    expectCardsRendered()
    expect(addButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje completado', () => {
    renderResources('COMPLETED')
    expectCardsRendered()
    expect(addButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje cancelado', () => {
    renderResources('CANCELLED')
    expectCardsRendered()
    expect(addButton()).not.toBeInTheDocument()
  })

  it('no se ofrece con el viaje eliminado', () => {
    renderResources('DELETED')
    expectCardsRendered()
    expect(addButton()).not.toBeInTheDocument()
  })

  it('vive dentro de la ficha de refuerzos, no en la de recursos', () => {
    renderResources('IN_PROGRESS')
    const card = screen.getByRole('region', { name: 'Refuerzos' })
    expect(within(card).getByRole('button', { name: /agregar refuerzo/i })).toBeInTheDocument()
  })
})

describe('ServiceResources · las acciones nunca conviven', () => {
  // Cuenta las DOS acciones de ficha por estado (asignar y agregar refuerzo): una que
  // se filtre a un estado ajeno mueve el número aunque su afirmación puntual siga
  // verde. Los botones de quitar quedan afuera del conteo a propósito: van por fila y
  // los cuenta su propio archivo.
  it('pendiente de asignación ofrece una sola acción', () => {
    renderResources('PENDING_ASSIGNMENT')
    expect(screen.getAllByRole('button', { name: /asignar recursos|agregar refuerzo/i })).toHaveLength(1)
  })

  it('en ruta ofrece una sola acción, y es la otra', () => {
    renderResources('IN_PROGRESS')
    const actions = screen.getAllByRole('button', { name: /asignar recursos|agregar refuerzo/i })
    expect(actions).toHaveLength(1)
    expect(actions[0]).toHaveAccessibleName(/agregar refuerzo/i)
  })

  it('completado no ofrece ninguna', () => {
    renderResources('COMPLETED')
    expectCardsRendered()
    expect(screen.queryAllByRole('button', { name: /asignar recursos|agregar refuerzo/i })).toHaveLength(0)
  })
})

describe('ServiceResources · cada botón abre SU modal', () => {
  // Sin estos dos casos nadie hace click, así que cruzar los `onClick` de los dos
  // botones sobrevive: los dos estados nunca coexisten y el modal equivocado no se
  // vería en ninguna pantalla.
  it('asignar recursos abre el modal de asignación', async () => {
    server.use(fleetUnitsByKind([fakeFleetUnit()]), driversList())
    const user = userEvent.setup()
    renderResources('PENDING_ASSIGNMENT')

    await user.click(screen.getByRole('button', { name: /asignar recursos/i }))

    expect(await screen.findByRole('dialog', { name: 'Asignar recursos' })).toBeInTheDocument()
  })

  it('agregar refuerzo abre el modal de refuerzos', async () => {
    server.use(fleetUnitsByKind([fakeFleetUnit()]), driversList())
    const user = userEvent.setup()
    renderResources('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: /agregar refuerzo/i }))

    // Los dos títulos son distintos justamente para que abrir el equivocado se vea.
    expect(await screen.findByRole('dialog', { name: 'Agregar refuerzo' })).toBeInTheDocument()
  })
})

describe('ServiceResources · quién ve la acción', () => {
  it('el rol que opera el viaje la ve', () => {
    renderResources('PENDING_ASSIGNMENT', true)
    expect(assignButton()).toBeInTheDocument()
  })

  it('el rol que no opera el viaje no ve tampoco la de refuerzos', () => {
    renderResources('IN_PROGRESS', false)
    expectCardsRendered()
    expect(addButton()).not.toBeInTheDocument()
    // Y sigue viendo los refuerzos que ya existen: se le saca la acción, no el dato.
    expect(screen.getByText(/Ana Ríos Chávez/)).toBeInTheDocument()
  })

  it('el rol que no opera el viaje no la ve, y sigue viendo los datos', () => {
    renderResources('PENDING_ASSIGNMENT', false)
    expectCardsRendered()
    expect(assignButton()).not.toBeInTheDocument()
    // Se le saca la acción, no el dato: la ficha sigue mostrando sus tres campos.
    expect(screen.getByText('Conductor')).toBeInTheDocument()
    expect(screen.getByText('Tracto')).toBeInTheDocument()
    expect(screen.getByText('Carreta')).toBeInTheDocument()
  })
})
