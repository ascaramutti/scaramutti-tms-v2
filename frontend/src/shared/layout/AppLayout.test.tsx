import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppLayout } from './AppLayout'
import { AuthProvider } from '../auth/AuthContext'
import { tokenStorage } from '../auth/tokenStorage'

function renderLayout() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/" element={<div>CONTENIDO HIJO</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('AppLayout', () => {
  beforeEach(() => {
    tokenStorage.setTokens('fake-access', 'fake-refresh')
  })

  it('renderiza el sidebar + Outlet con el contenido hijo', async () => {
    renderLayout()
    // El children del <Outlet /> se monta:
    expect(await screen.findByText('CONTENIDO HIJO')).toBeInTheDocument()
    // El sidebar tiene la nav principal:
    expect(screen.getByRole('navigation', { name: /principal/i })).toBeInTheDocument()
    // El menú renderiza (admin ve Cotizaciones como link):
    expect(await screen.findByRole('link', { name: /cotizaciones/i })).toBeInTheDocument()
  })
})
