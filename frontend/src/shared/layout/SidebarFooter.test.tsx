import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SidebarFooter } from './SidebarFooter'
import { AuthProvider } from '../auth/AuthContext'
import { tokenStorage } from '../auth/tokenStorage'

function renderFooter() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <SidebarFooter />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SidebarFooter', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it('muestra el nombre y cargo del usuario cuando hay sesion', async () => {
    // Hay token → handler default de auth devuelve admin con position
    tokenStorage.setTokens('fake-access', 'fake-refresh')
    renderFooter()
    expect(await screen.findByText(/admin tms/i)).toBeInTheDocument()
    expect(screen.getByText(/administrador del sistema/i)).toBeInTheDocument()
  })

  it('logout dispara clearSession (queda sin user)', async () => {
    const user = userEvent.setup()
    tokenStorage.setTokens('fake-access', 'fake-refresh')
    renderFooter()
    await screen.findByText(/admin tms/i)
    await user.click(screen.getByRole('button', { name: /cerrar sesión/i }))
    // clearSession limpia tokens
    await waitFor(() => {
      expect(tokenStorage.getAccessToken()).toBeNull()
    })
  })

})
