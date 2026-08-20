import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RouterProvider, createMemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { routes } from './router'
import { AuthProvider } from './shared/auth/AuthContext'
import { tokenStorage } from './shared/auth/tokenStorage'
import { server } from './test/mocks/server'
import type { UserResponse, UserRole } from './api'

const API = 'http://localhost:8080/api/v1'

/**
 * Monta la tabla de rutas REAL. El resto de la suite declara rutas propias en
 * cada archivo de test (`<Route path="/cotizaciones/operaciones" …>`), así que
 * ninguna verifica la del router: con una lista de roles equivocada o un typo
 * en el path, la suite entera queda verde y el usuario se lo come en producción.
 */
function renderRouteAs(role: UserRole, path: string) {
  server.use(http.get(`${API}/auth/me`, () => HttpResponse.json(buildUser(role))))
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
}

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

describe('router — módulo Operaciones', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it('el despachador entra y ve la pantalla del módulo', async () => {
    renderRouteAs('dispatcher', '/cotizaciones/operaciones')
    expect(await screen.findByRole('heading', { level: 1, name: 'Servicios' })).toBeInTheDocument()
  })

  it('un rol de almacén queda afuera y se le ofrece su módulo', async () => {
    renderRouteAs('warehouse_keeper', '/cotizaciones/operaciones')
    expect(await screen.findByRole('heading', { name: /sin acceso a operaciones/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ir a almacén/i })).toHaveAttribute(
      'href',
      '/cotizaciones/almacen',
    )
  })

  it('la ruta del módulo es exactamente donde aterriza el despachador', async () => {
    // El aterrizaje, el menú y la tabla de rutas comparten una sola constante.
    // Si alguna se desviara, este caso cae antes que el usuario.
    renderRouteAs('dispatcher', '/cotizaciones')
    expect(await screen.findByRole('heading', { name: /sin acceso a cotizaciones/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ir a operaciones/i })).toHaveAttribute(
      'href',
      '/cotizaciones/operaciones',
    )
  })
})
