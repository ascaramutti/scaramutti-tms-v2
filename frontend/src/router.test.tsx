import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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
function renderRouteAs(role: UserRole | null, path: string) {
  if (role) {
    server.use(http.get(`${API}/auth/me`, () => HttpResponse.json(buildUser(role))))
    tokenStorage.setTokens('fake-access', 'fake-refresh')
  } else {
    server.use(http.get(`${API}/auth/me`, () => new HttpResponse(null, { status: 401 })))
  }
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Igual que el anterior, pero devuelve el router para leer a dónde terminó. */
function goTo(role: UserRole | null, path: string) {
  if (role) {
    server.use(http.get(`${API}/auth/me`, () => HttpResponse.json(buildUser(role))))
    tokenStorage.setTokens('fake-access', 'fake-refresh')
  } else {
    server.use(http.get(`${API}/auth/me`, () => new HttpResponse(null, { status: 401 })))
  }
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  return router
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

describe('router — ruta que no existe', () => {
  // Ojo con elegir el caso: `/cotizaciones/loquesea` NO llega acá, lo captura
  // `/cotizaciones/:id` (el detalle de cotización lo toma como id). Al catch-all
  // solo llegan las rutas de dos o más segmentos que no matchean nada.
  const RUTA_INEXISTENTE = '/cotizaciones/almacen/esto-no-existe'

  beforeEach(() => {
    tokenStorage.clear()
  })

  it('sin sesión va al login, y NO se guarda la ruta rota como destino', async () => {
    const router = goTo(null, RUTA_INEXISTENTE)
    await screen.findByRole('heading', { name: /iniciar sesión/i })
    expect(router.state.location.pathname).toBe('/cotizaciones/login')
    // Guardarla haría que, después de entrar, se intente volver a una ruta que
    // no existe y el rebote se repita.
    expect(router.state.location.state).toBeNull()
    // Y con `replace` tampoco queda en el historial: el botón Atrás no la repite.
    expect(router.state.historyAction).toBe('REPLACE')
  })

  it.each([
    ['dispatcher', '/cotizaciones/operaciones'],
    ['finance_manager', '/cotizaciones/almacen'],
    ['admin', '/cotizaciones'],
  ] as const)('con sesión, %s cae en su propia pantalla', async (role, destino) => {
    // Antes iba a /cotizaciones fijo: un typo le mostraba "Sin acceso a
    // Cotizaciones" a los tres roles que no trabajan en el módulo comercial.
    const router = goTo(role, RUTA_INEXISTENTE)
    await waitFor(() => expect(router.state.location.pathname).toBe(destino))
    // El destino solo no alcanza: si el redirect mandara a alguien con sesión
    // al login, la pantalla de login lo rebotaría a su módulo y el destino
    // final saldría igual. Se afirma que NO pasó por ahí.
    expect(screen.queryByRole('heading', { name: /iniciar sesión/i })).not.toBeInTheDocument()
    // Con `replace`, la ruta rota no queda en el historial: sin esto, el botón
    // Atrás repite el rebote.
    expect(router.state.historyAction).toBe('REPLACE')
  })
})
