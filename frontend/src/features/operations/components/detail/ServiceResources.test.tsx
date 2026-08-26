import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ServiceStatus } from '../../../../api'
import { ServiceResources } from './ServiceResources'
import { fakeBaitedServiceDetail } from '../../../../test/mocks/handlers/operations'

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

describe('ServiceResources · el botón de asignar', () => {
  // Los seis estados, un caso por cada uno y no un bucle sobre un arreglo: cuando
  // aparezca un estado nuevo, el que falte tiene que ser un test que no existe y se
  // ve al leer la lista, no una fila que nadie agregó.
  it('se ofrece con el viaje pendiente de asignación', () => {
    renderResources('PENDING_ASSIGNMENT')
    expectCardsRendered()
    expect(assignButton()).toBeInTheDocument()
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

describe('ServiceResources · quién ve la acción', () => {
  it('el rol que opera el viaje la ve', () => {
    renderResources('PENDING_ASSIGNMENT', true)
    expect(assignButton()).toBeInTheDocument()
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
