import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { ProtectedRoute } from './ProtectedRoute'
import { AuthProvider } from './AuthContext'
import { tokenStorage } from './tokenStorage'
import { server } from '../../test/mocks/server'
import { fakeUser, getCurrentUserErrorResponse } from '../../test/mocks/handlers/auth'
import type { UserResponse, UserRole } from '../../api'

const API = 'http://localhost:8080/api/v1'

function renderProtected(
  initialPath: string,
  options?: { allowedRoles?: UserRole[]; moduleName?: string },
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/cotizaciones/login" element={<div>LOGIN PAGE</div>} />
            <Route path="/cotizaciones/almacen" element={<div>ALMACEN</div>} />
            <Route
              path="/protegida"
              element={
                <ProtectedRoute
                  allowedRoles={options?.allowedRoles}
                  moduleName={options?.moduleName}
                >
                  <div>CONTENIDO PROTEGIDO</div>
                </ProtectedRoute>
              }
            />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it('redirige a /cotizaciones/login si no hay sesion', async () => {
    renderProtected('/protegida')
    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument()
    expect(screen.queryByText('CONTENIDO PROTEGIDO')).not.toBeInTheDocument()
  })

  it('renderiza children cuando hay sesion valida', async () => {
    // Hay token → AuthProvider fetchea getCurrentUser (handler default = admin)
    tokenStorage.setTokens('fake-access-token', 'fake-refresh-token')
    renderProtected('/protegida')
    expect(await screen.findByText('CONTENIDO PROTEGIDO')).toBeInTheDocument()
  })

  it('muestra loading state mientras se valida la sesion', async () => {
    tokenStorage.setTokens('fake-access-token', 'fake-refresh-token')
    renderProtected('/protegida')
    // Antes de que getCurrentUser responda, debe mostrar loading
    expect(screen.getByRole('status')).toBeInTheDocument()
    // Despues del fetch, renderiza el contenido
    expect(await screen.findByText('CONTENIDO PROTEGIDO')).toBeInTheDocument()
  })

  it('redirige a /cotizaciones/login si el token es invalido (getCurrentUser devuelve 401)', async () => {
    tokenStorage.setTokens('expired-token', 'expired-refresh')
    server.use(
      getCurrentUserErrorResponse(401, {
        type: 'urn:tms:error:auth-008',
        title: 'Token invalid',
        status: 401,
        code: 'AUTH-008',
      }),
    )
    renderProtected('/protegida')
    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument()
  })

  it('muestra "Sin acceso" con el nombre del modulo si el rol no esta en allowedRoles', async () => {
    tokenStorage.setTokens('admin-token', 'admin-refresh')
    // Default handler devuelve user con role=admin. Restringimos a solo 'sales'.
    // NO redirige (el landing de cada rol también está protegido → un redirect
    // loopearía): renderiza la vista inline con la salida al módulo del rol.
    renderProtected('/protegida', { allowedRoles: ['sales'], moduleName: 'Cotizaciones' })
    expect(await screen.findByText(/sin acceso a cotizaciones/i)).toBeInTheDocument()
    expect(screen.queryByText('CONTENIDO PROTEGIDO')).not.toBeInTheDocument()
  })

  it('sin moduleName el titulo no nombra ningun modulo (no miente)', async () => {
    tokenStorage.setTokens('admin-token', 'admin-refresh')
    renderProtected('/protegida', { allowedRoles: ['sales'] })
    expect(await screen.findByText(/sin acceso a este módulo/i)).toBeInTheDocument()
  })

  it('la salida lleva al landing del rol denegado, no siempre a v1', async () => {
    tokenStorage.setTokens('finanzas-token', 'finanzas-refresh')
    server.use(
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json({ ...fakeUser, role: 'finance_manager' } satisfies UserResponse),
      ),
    )
    // La Jefa de Finanzas entrando a Cotizaciones: mandarla a v1 (el destino
    // viejo, hardcodeado) la dejaría en otra app donde tampoco trabaja.
    renderProtected('/protegida', { allowedRoles: ['sales'], moduleName: 'Cotizaciones' })
    expect(await screen.findByRole('link', { name: /ir a almacén/i })).toHaveAttribute(
      'href',
      '/cotizaciones/almacen',
    )
  })

  it('la salida a un modulo de esta SPA navega con el router (sin recargar)', async () => {
    tokenStorage.setTokens('finanzas-token', 'finanzas-refresh')
    server.use(
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json({ ...fakeUser, role: 'finance_manager' } satisfies UserResponse),
      ),
    )
    const user = userEvent.setup()
    renderProtected('/protegida', { allowedRoles: ['sales'], moduleName: 'Cotizaciones' })

    await user.click(await screen.findByRole('link', { name: /ir a almacén/i }))
    expect(await screen.findByText('ALMACEN')).toBeInTheDocument()
  })

  it('el dispatcher sale a operaciones, su módulo dentro de esta SPA', async () => {
    tokenStorage.setTokens('dispatcher-token', 'dispatcher-refresh')
    server.use(
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json({ ...fakeUser, role: 'dispatcher' } satisfies UserResponse),
      ),
    )
    renderProtected('/protegida', { allowedRoles: ['sales'], moduleName: 'Cotizaciones' })
    expect(await screen.findByRole('link', { name: /ir a operaciones/i })).toHaveAttribute(
      'href',
      '/cotizaciones/operaciones',
    )
  })

  it('renderiza children si el rol coincide con allowedRoles', async () => {
    tokenStorage.setTokens('admin-token', 'admin-refresh')
    renderProtected('/protegida', { allowedRoles: ['admin', 'general_manager'] })
    expect(await screen.findByText('CONTENIDO PROTEGIDO')).toBeInTheDocument()
  })
})
