import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ServiceStatus, UserRole } from '../../../../api'
import { ServiceStatusActions } from './ServiceStatusActions'
import { DEFAULT_SERVICE_ETAG, fakeServiceDetail } from '../../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from '../../hooks/useService'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

/**
 * El rol va SIEMPRE explícito, sin valor por defecto: un default de parámetro se
 * aplica también cuando el argumento es `undefined`, así que el caso "sin rol" habría
 * corrido como `admin` y no podía fallar.
 */
function renderActions(status: ServiceStatus, role: UserRole | undefined) {
  const service: ServiceWithEtag = {
    ...fakeServiceDetail({ status }),
    _etag: DEFAULT_SERVICE_ETAG,
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return render(<ServiceStatusActions service={service} role={role} />, { wrapper })
}

describe('ServiceStatusActions, qué ofrece', () => {
  it('ofrece iniciar desde pendiente de inicio', () => {
    renderActions('PENDING_START', 'admin')

    expect(screen.getByRole('button', { name: /Iniciar viaje/ })).toBeInTheDocument()
    // Por ROL y no por texto: un `<span>` con la misma palabra, que no se puede
    // clickear, pasaría una búsqueda por texto.
    expect(screen.queryByRole('button', { name: /Finalizar viaje/ })).not.toBeInTheDocument()
  })

  it('ofrece finalizar desde en ruta', () => {
    renderActions('IN_PROGRESS', 'admin')

    expect(screen.getByRole('button', { name: /Finalizar viaje/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Iniciar viaje/ })).not.toBeInTheDocument()
  })

  it('no ofrece nada desde pendiente de asignación', () => {
    // El viaje avanza asignándole recursos, que es otra acción y vive en otra ficha.
    renderActions('PENDING_ASSIGNMENT', 'admin')

    expect(screen.queryByRole('group', { name: 'Acciones del viaje' })).not.toBeInTheDocument()
  })

  it.each(['COMPLETED', 'CANCELLED', 'DELETED'] as const)(
    'no monta ni el contenedor en %s',
    (status) => {
      renderActions(status, 'admin')

      // Las dos mitades: sin la del grupo, un contenedor vacío pasa igual y deja en el
      // encabezado un espacio que nada explica.
      expect(screen.queryByRole('group', { name: 'Acciones del viaje' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
    },
  )

  it('agrupa los botones con un nombre', () => {
    renderActions('PENDING_START', 'admin')

    expect(screen.getByRole('group', { name: 'Acciones del viaje' })).toBeInTheDocument()
  })
})

describe('ServiceStatusActions, por rol', () => {
  it.each(['admin', 'general_manager', 'operations_manager', 'dispatcher'] as const)(
    '%s puede iniciar el viaje',
    (role) => {
      renderActions('PENDING_START', role)

      expect(screen.getByRole('button', { name: /Iniciar viaje/ })).toBeInTheDocument()
    },
  )

  it('a ventas no le ofrece ninguna acción', () => {
    // Colgar la barra de los roles del módulo (que incluyen ventas) en vez de los que
    // operan el viaje es un cambio de una sola constante importada.
    renderActions('IN_PROGRESS', 'sales')

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('sin rol tampoco ofrece nada', () => {
    renderActions('IN_PROGRESS', undefined)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('ServiceStatusActions, la apertura', () => {
  it('abre el diálogo de la acción que se apretó', async () => {
    const user = userEvent.setup()
    renderActions('IN_PROGRESS', 'admin')

    await user.click(screen.getByRole('button', { name: /Finalizar viaje/ }))

    // Con el modal cableado al destino equivocado, el título delataría el cruce.
    expect(await screen.findByRole('dialog', { name: 'Finalizar viaje' })).toBeInTheDocument()
  })

  it('no monta el diálogo hasta que se lo abre', () => {
    // El formulario congela sus valores al montar, y el suyo incluye la hora actual:
    // montado de entrada, precargaría la hora en que se abrió la pantalla.
    renderActions('PENDING_START', 'admin')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
