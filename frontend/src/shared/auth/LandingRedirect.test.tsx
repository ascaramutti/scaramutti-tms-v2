import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse, delay } from 'msw'
import { LandingRedirect } from './LandingRedirect'
import { AuthProvider } from './AuthContext'
import { tokenStorage } from './tokenStorage'
import { server } from '../../test/mocks/server'
import type { UserResponse, UserRole } from '../../api'

const API = 'http://localhost:8080/api/v1'

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

/**
 * Se prueba el componente SUELTO, no a través del router de la app: montado en
 * la app, `LoginPage` rebota a quien ya tiene sesión hacia su módulo, así que el
 * destino final sale bien incluso si este componente decide mal. Acá no hay
 * nadie que corrija, y por eso la decisión queda expuesta.
 */
function renderRedirect() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/ruta-que-no-existe']}>
          <Routes>
            <Route path="/ruta-que-no-existe" element={<LandingRedirect />} />
            <Route path="/cotizaciones/login" element={<div>LOGIN</div>} />
            <Route path="/cotizaciones" element={<div>COTIZACIONES</div>} />
            <Route path="/cotizaciones/operaciones" element={<div>OPERACIONES</div>} />
            <Route path="/cotizaciones/almacen" element={<div>ALMACEN</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('LandingRedirect', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it('sin sesión manda al login', async () => {
    server.use(http.get(`${API}/auth/me`, () => new HttpResponse(null, { status: 401 })))
    renderRedirect()
    expect(await screen.findByText('LOGIN')).toBeInTheDocument()
  })

  it.each([
    ['dispatcher', 'OPERACIONES'],
    ['finance_manager', 'ALMACEN'],
    ['admin', 'COTIZACIONES'],
  ] as const)('con sesión, %s va a su módulo y NO pasa por el login', async (role, destino) => {
    server.use(http.get(`${API}/auth/me`, () => HttpResponse.json(buildUser(role))))
    tokenStorage.setTokens('fake-access', 'fake-refresh')
    renderRedirect()
    expect(await screen.findByText(destino)).toBeInTheDocument()
    expect(screen.queryByText('LOGIN')).not.toBeInTheDocument()
  })

  it('mientras la sesión resuelve, espera en vez de mandar al login', async () => {
    // Sin esta espera, quien tiene sesión válida pero una consulta lenta sale
    // despedido al login solo por lo que tarda el servidor en contestar.
    server.use(
      http.get(`${API}/auth/me`, async () => {
        await delay(50)
        return HttpResponse.json(buildUser('dispatcher'))
      }),
    )
    tokenStorage.setTokens('fake-access', 'fake-refresh')
    renderRedirect()
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByText('LOGIN')).not.toBeInTheDocument()
    expect(await screen.findByText('OPERACIONES')).toBeInTheDocument()
  })
})
