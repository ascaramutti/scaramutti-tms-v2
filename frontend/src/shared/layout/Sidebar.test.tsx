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

function renderSidebarAs(role: UserRole, initialPath = '/cotizaciones') {
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
        <MemoryRouter initialEntries={[initialPath]}>
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
    // Cross-link a v1 (visible para todos)
    expect(screen.getByRole('link', { name: /servicios \/ viajes/i })).toHaveAttribute('href', '/')
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
    // Pero SÍ ve el cross-link a v1 (es su lugar de trabajo)
    expect(screen.getByRole('link', { name: /servicios \/ viajes/i })).toBeInTheDocument()
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

describe('Sidebar - módulo Almacén', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it.each(['admin', 'general_manager', 'operations_manager', 'finance_manager', 'warehouse_keeper'] as const)(
    '%s ve la sección Almacén con Existencias navegable',
    async (role) => {
      renderSidebarAs(role)
      await waitFor(() => {
        expect(screen.getByText(`Usuario ${role}`)).toBeInTheDocument()
      })
      expect(screen.getByText(/^almacén$/i)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /existencias/i })).toHaveAttribute(
        'href',
        '/cotizaciones/almacen',
      )
    },
  )

  it.each(['sales', 'dispatcher'] as const)(
    '%s no ve la sección Almacén (se oculta entera, sin h2 huérfano)',
    async (role) => {
      renderSidebarAs(role)
      await waitFor(() => {
        expect(screen.getByText(`Usuario ${role}`)).toBeInTheDocument()
      })
      expect(screen.queryByText(/^almacén$/i)).not.toBeInTheDocument()
      expect(screen.queryByText('Existencias')).not.toBeInTheDocument()
    },
  )

  it('los roles de almacén no ven Cotizaciones ni Clientes', async () => {
    renderSidebarAs('finance_manager')
    await waitFor(() => {
      expect(screen.getByText(/usuario finance_manager/i)).toBeInTheDocument()
    })
    expect(screen.queryByText('Cotizaciones')).not.toBeInTheDocument()
    expect(screen.queryByText('Clientes')).not.toBeInTheDocument()
    expect(screen.queryByText(/^comercial$/i)).not.toBeInTheDocument()
  })

  it('entradas, retiros y reportes esperan su pantalla (deshabilitados)', async () => {
    renderSidebarAs('warehouse_keeper')
    await waitFor(() => {
      expect(screen.getByText(/usuario warehouse_keeper/i)).toBeInTheDocument()
    })
    for (const label of ['Entradas', 'Retiros', 'Reportes']) {
      expect(screen.queryByRole('link', { name: new RegExp(label, 'i') })).not.toBeInTheDocument()
      expect(screen.getByText(label).closest('span')).toHaveAttribute('aria-disabled', 'true')
    }
  })

  it('en almacén se resalta Existencias y NO Cotizaciones', async () => {
    // Ambos módulos cuelgan de /cotizaciones (el base de la SPA): sin matcher
    // por módulo, el prefijo marcaría Cotizaciones estando en Almacén.
    renderSidebarAs('admin', '/cotizaciones/almacen')
    const existencias = await screen.findByRole('link', { name: /existencias/i })
    expect(existencias).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: /^cotizaciones$/i })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('el detalle de un producto sigue resaltando Existencias', async () => {
    renderSidebarAs('admin', '/cotizaciones/almacen/productos/42')
    const existencias = await screen.findByRole('link', { name: /existencias/i })
    expect(existencias).toHaveAttribute('aria-current', 'page')
  })
})
