import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { Sidebar } from './Sidebar'
import { AuthProvider } from '../auth/AuthContext'
import { tokenStorage } from '../auth/tokenStorage'
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

function renderSidebarAs(role: UserRole) {
  server.use(
    http.get(`${API}/auth/me`, () => HttpResponse.json(buildUser(role))),
  )
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter>
          <Sidebar />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('Sidebar - filtrado por rol', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it('admin ve Cotizaciones + Clientes + Cambiar contraseña', async () => {
    renderSidebarAs('admin')
    await waitFor(() => {
      expect(screen.getByText(/usuario admin/i)).toBeInTheDocument()
    })
    // Cotizaciones ya está activa: es un link navegable (no el placeholder disabled).
    expect(screen.getByRole('link', { name: /cotizaciones/i })).toHaveAttribute(
      'href',
      '/cotizaciones',
    )
    expect(screen.getByText('Clientes')).toBeInTheDocument()
    expect(screen.getByText(/comercial/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /cambiar contraseña/i })).toHaveAttribute(
      'href',
      '/cotizaciones/cuenta/cambiar-contrasena',
    )
    expect(screen.getByText(/administrar cuenta/i)).toBeInTheDocument()
  })

  it('sales ve Cotizaciones + Clientes + Cambiar contraseña', async () => {
    renderSidebarAs('sales')
    await waitFor(() => {
      expect(screen.getByText(/usuario sales/i)).toBeInTheDocument()
    })
    expect(screen.getByText('Cotizaciones')).toBeInTheDocument()
    expect(screen.getByText('Clientes')).toBeInTheDocument()
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
  })

  it('dispatcher ve SOLO Cambiar contraseña (sección Comercial oculta)', async () => {
    renderSidebarAs('dispatcher')
    await waitFor(() => {
      expect(screen.getByText(/usuario dispatcher/i)).toBeInTheDocument()
    })
    expect(screen.queryByText('Cotizaciones')).not.toBeInTheDocument()
    expect(screen.queryByText('Clientes')).not.toBeInTheDocument()
    // La sección Comercial entera (con su <h2>) se oculta cuando queda sin items
    expect(screen.queryByText(/^comercial$/i)).not.toBeInTheDocument()
    // Cambiar contraseña es visible para TODOS los roles (sin allowedRoles)
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
    expect(screen.getByText(/administrar cuenta/i)).toBeInTheDocument()
  })

  it('general_manager ve Cotizaciones + Clientes + Cambiar contraseña', async () => {
    renderSidebarAs('general_manager')
    await waitFor(() => {
      expect(screen.getByText(/usuario general_manager/i)).toBeInTheDocument()
    })
    expect(screen.getByText('Cotizaciones')).toBeInTheDocument()
    expect(screen.getByText('Clientes')).toBeInTheDocument()
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
  })

  it('operations_manager ve Cotizaciones + Clientes + Cambiar contraseña', async () => {
    renderSidebarAs('operations_manager')
    await waitFor(() => {
      expect(screen.getByText(/usuario operations_manager/i)).toBeInTheDocument()
    })
    expect(screen.getByText('Cotizaciones')).toBeInTheDocument()
    expect(screen.getByText('Clientes')).toBeInTheDocument()
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
  })
})
