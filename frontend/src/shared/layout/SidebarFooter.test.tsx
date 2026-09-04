import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SidebarFooter } from './SidebarFooter'
import { AuthProvider } from '../auth/AuthContext'
import { ThemeProvider } from '../ui/theme/ThemeContext'
import { tokenStorage } from '../auth/tokenStorage'

function renderFooter() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ThemeProvider>
          <AuthProvider>
            <SidebarFooter />
          </AuthProvider>
        </ThemeProvider>
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


  /**
   * El interruptor del tema. Lo que se cuida es lo que un ícono solo no puede decir: que el
   * texto nombre la ACCIÓN y que el estado esté en `aria-pressed`, que es lo que un lector
   * de pantalla anuncia.
   */
  it('el interruptor conserva su nombre y anuncia el estado en aria-pressed', async () => {
    renderFooter()
    const boton = await screen.findByRole('button', { name: 'Modo oscuro' })
    expect(boton).toHaveAttribute('aria-pressed', 'false')

    await userEvent.click(boton)

    // El NOMBRE no cambia: es un botón de alternancia, y su estado va en `aria-pressed`. La
    // primera versión daba vuelta el texto Y ponía `aria-pressed`, con lo que un lector de
    // pantalla anunciaba "Modo claro, presionado" con el oscuro activo, o sea lo contrario de
    // lo que pasaba. Esta prueba fijaba ese defecto; lo levantó la revisión.
    const mismo = await screen.findByRole('button', { name: 'Modo oscuro' })
    expect(mismo).toHaveAttribute('aria-pressed', 'true')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })

  /**
   * El ícono es lo único del interruptor que sí cambia con el estado, y por eso es la única
   * señal visual de en qué tema está: con el oscuro activo muestra el sol, que es a dónde
   * lleva el clic. Se afirma porque dar vuelta el ternario no lo notaba nadie: es `aria-hidden`
   * a propósito, así que ninguna aserción por nombre accesible puede verlo. Lo levantó la
   * revisión de este PR.
   */
  it('el ícono muestra a dónde lleva el clic', async () => {
    const { container } = renderFooter()
    const boton = await screen.findByRole('button', { name: 'Modo oscuro' })
    expect(container.querySelector('.lucide-moon'), 'en claro ofrece la luna').toBeInTheDocument()
    expect(container.querySelector('.lucide-sun')).toBeNull()

    await userEvent.click(boton)

    expect(container.querySelector('.lucide-sun'), 'en oscuro ofrece el sol').toBeInTheDocument()
    expect(container.querySelector('.lucide-moon')).toBeNull()
  })

  it('se puede accionar con el teclado', async () => {
    renderFooter()
    const boton = await screen.findByRole('button', { name: 'Modo oscuro' })
    boton.focus()
    await userEvent.keyboard('{Enter}')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
})
