import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ProtectedRoute } from './ProtectedRoute'
import { AuthProvider } from './AuthContext'
import { tokenStorage } from './tokenStorage'
import { server } from '../../test/mocks/server'
import { getCurrentUserErrorResponse } from '../../test/mocks/handlers/auth'
import type { UserRole } from '../../api'

function renderProtected(
  initialPath: string,
  options?: { allowedRoles?: UserRole[] },
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/login" element={<div>LOGIN PAGE</div>} />
            <Route path="/" element={<div>HOME</div>} />
            <Route
              path="/protegida"
              element={
                <ProtectedRoute allowedRoles={options?.allowedRoles}>
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

  it('redirige a /login si no hay sesion', async () => {
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

  it('redirige a /login si el token es invalido (getCurrentUser devuelve 401)', async () => {
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

  it('redirige a / si el rol del usuario no esta en allowedRoles', async () => {
    tokenStorage.setTokens('admin-token', 'admin-refresh')
    // Default handler devuelve user con role=admin. Restringimos a solo 'sales'.
    renderProtected('/protegida', { allowedRoles: ['sales'] })
    expect(await screen.findByText('HOME')).toBeInTheDocument()
    expect(screen.queryByText('CONTENIDO PROTEGIDO')).not.toBeInTheDocument()
  })

  it('renderiza children si el rol coincide con allowedRoles', async () => {
    tokenStorage.setTokens('admin-token', 'admin-refresh')
    renderProtected('/protegida', { allowedRoles: ['admin', 'general_manager'] })
    expect(await screen.findByText('CONTENIDO PROTEGIDO')).toBeInTheDocument()
  })
})
