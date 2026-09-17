import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { http, HttpResponse } from 'msw'
import { ClientDetailPage } from './ClientDetailPage'
import { CLIENTS_BASE } from '../../../shared/paths'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { server } from '../../../test/mocks/server'
import type { UserResponse, UserRole } from '../../../api'
import {
  fakeClient,
  getClientError,
  getClientNotFound,
  getClientCapture,
  getClientOk,
  getClientSlow,
} from '../../../test/mocks/handlers/clients'

const API = 'http://localhost:8080/api/v1'
const DETALLE = `${CLIENTS_BASE}/7`

function buildUser(role: UserRole): UserResponse {
  return {
    id: 1,
    username: `user-${role}`,
    fullName: `Usuario ${role}`,
    position: 'Cargo de prueba',
    role,
    isActive: true,
  }
}

function renderPage(role: UserRole = 'admin') {
  server.use(http.get(`${API}/auth/me`, () => HttpResponse.json(buildUser(role))))
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(
    [
      { path: `${CLIENTS_BASE}/:id`, element: <ClientDetailPage /> },
      { path: CLIENTS_BASE, element: <p>Pantalla de búsqueda</p> },
      { path: `${CLIENTS_BASE}/:id/editar`, element: <p>Formulario</p> },
    ],
    { initialEntries: [DETALLE] },
  )
  const vista = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { router, container: vista.container }
}

describe('ClientDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('muestra el estado de carga mientras trae el cliente', async () => {
    server.use(getClientSlow(fakeClient({ id: 7 }), 80))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Cargando cliente')).toBeInTheDocument())
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('ACME S.A.C.')
  })

  it('muestra los cuatro datos, el estado y la fecha de alta', async () => {
    server.use(
      getClientOk(
        fakeClient({
          id: 7,
          name: 'ACME S.A.C.',
          ruc: '20123456789',
          phone: '987654321',
          contactName: 'Juan Pérez',
          isActive: true,
          createdAt: '2026-05-21T02:00:00Z',
        }),
      ),
    )
    renderPage()

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('ACME S.A.C.')
    expect(screen.getByText('20123456789')).toBeInTheDocument()
    expect(screen.getByText('987654321')).toBeInTheDocument()
    expect(screen.getByText('Juan Pérez')).toBeInTheDocument()
    expect(screen.queryByText('Inactivo')).not.toBeInTheDocument()
    // El instante es de la noche de Lima a propósito: con uno de media mañana,
    // formatear con el helper de fechas sin hora da el mismo día y el caso no
    // distingue el correcto del que corre el día para medio país.
    expect(screen.getByText('20/05/2026')).toBeInTheDocument()
  })

  /**
   * Un espacio en blanco no distingue "no tiene teléfono" de "no cargó la
   * pantalla", así que el vacío se dice.
   */
  it('dice cuáles datos opcionales no están cargados', async () => {
    server.use(getClientOk(fakeClient({ id: 7, phone: null, contactName: null })))
    renderPage()

    await screen.findByRole('heading', { level: 1 })
    // El guion es lo que usa toda la casa para el opcional vacío.
    expect(screen.getAllByText('—')).toHaveLength(2)
  })

  it('un cliente desactivado se ve como tal', async () => {
    server.use(getClientOk(fakeClient({ id: 7, isActive: false })))
    renderPage()

    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText('Inactivo')).toBeInTheDocument()
  })

  it('ofrece editar y lleva al formulario de ese cliente', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage('operations_manager')

    expect(await screen.findByRole('link', { name: /^editar$/i })).toHaveAttribute(
      'href',
      `${CLIENTS_BASE}/7/editar`,
    )
  })

  /**
   * El rol que no puede editar no ve el botón: ofrecer una acción que va a
   * terminar en "Sin acceso" es peor que no ofrecerla. Hoy la ruta del detalle
   * exige los mismos roles, así que este caso mide la intención; el día que el
   * detalle se abra a más roles, es lo único que lo sostiene.
   */
  it('un rol que no edita no ve el botón de editar', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage('dispatcher')

    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('link', { name: /^editar$/i })).not.toBeInTheDocument()
  })

  it('un cliente que ya no existe lo dice y ofrece la vuelta', async () => {
    const user = userEvent.setup()
    server.use(getClientNotFound())
    const { router } = renderPage()

    expect(await screen.findByText('Este cliente ya no existe')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /volver a clientes/i }))
    await waitFor(() => expect(router.state.location.pathname).toBe(CLIENTS_BASE))
  })

  it('un error muestra lo que explica el backend y deja reintentar', async () => {
    const user = userEvent.setup()
    server.use(getClientError())
    const { router } = renderPage()

    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toHaveTextContent(
      'Error interno del servidor',
    )
    expect(router.state.location.pathname).toBe(DETALLE)

    server.use(getClientOk(fakeClient({ id: 7, name: 'ACME RECUPERADO' })))
    await user.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('ACME RECUPERADO')
  })

  it('no tiene violaciones de accesibilidad', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    const { container } = renderPage()

    await screen.findByRole('heading', { level: 1 })
    expect(await axe(container)).toHaveNoViolations()
  })
  /**
   * B1: la única promesa de una pantalla de solo lectura es poner cada dato bajo
   * su rótulo. Afirmar los valores sueltos pasa igual con el RUC debajo de
   * "Teléfono".
   */
  it('cada dato va bajo su rótulo, y no al lado del de otro', async () => {
    server.use(getClientOk(fakeClient({ id: 7, ruc: '20123456789', phone: '987654321' })))
    renderPage()
    await screen.findByRole('heading', { level: 1 })

    const par = (rotulo: string) => screen.getByText(rotulo).parentElement as HTMLElement
    expect(within(par('RUC')).getByText('20123456789')).toBeInTheDocument()
    expect(within(par('Teléfono')).getByText('987654321')).toBeInTheDocument()
  })

  /**
   * B2: sin esto, pedirle al servidor otro cliente pasa entero, porque el doble
   * responde lo mismo para cualquier id.
   */
  it('pide al servidor el cliente de la URL', async () => {
    const sink: { id?: number } = {}
    server.use(getClientCapture(sink, fakeClient({ id: 7 })))
    renderPage()
    await screen.findByRole('heading', { level: 1 })

    expect(sink.id).toBe(7)
  })

  /** El encabezado es exactamente la razón social: la subcadena no distingue un sufijo. */
  it('el encabezado es la razón social, sin agregados', async () => {
    server.use(getClientOk(fakeClient({ id: 7, name: 'ACME S.A.C.' })))
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'ACME S.A.C.' })).toBeInTheDocument()
  })

  /** El camino de vuelta se ve en los cuatro estados de la pantalla. */
  it('ofrece volver a la búsqueda', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await screen.findByRole('heading', { level: 1 })

    expect(screen.getByRole('link', { name: /volver a clientes/i })).toHaveAttribute(
      'href',
      CLIENTS_BASE,
    )
  })

  /** Las decisiones de disposición se miden, que si no son un comentario. */
  it('la razón social y el contacto ocupan la fila entera', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await screen.findByRole('heading', { level: 1 })

    const grilla = screen.getByText('RUC').closest('dl')
    expect(grilla).toHaveClass('grid-cols-1')
    expect(grilla).toHaveClass('sm:grid-cols-2')
    expect(screen.getByText('Razón social').parentElement).toHaveClass('sm:col-span-2')
    expect(screen.getByText('Persona de contacto').parentElement).toHaveClass('sm:col-span-2')
  })

})
