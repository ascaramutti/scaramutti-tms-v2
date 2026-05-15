import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ChangePasswordPage } from './ChangePasswordPage'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { server } from '../../../test/mocks/server'
import { changePasswordErrorResponse } from '../../../test/mocks/handlers/auth'

function renderPage(initialPath = '/cuenta/cambiar-contrasena') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/cuenta/cambiar-contrasena" element={<ChangePasswordPage />} />
          <Route path="/" element={<div>HOME</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ChangePasswordPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    tokenStorage.clear()
  })

  // ----- Render -----
  it('renderiza el form con los 3 campos', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /cambiar contraseña/i, level: 1 })).toBeInTheDocument()
    expect(screen.getByLabelText(/^contraseña actual$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^nueva contraseña$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^confirmar nueva contraseña$/i)).toBeInTheDocument()
  })

  it('hace focus en el campo "contraseña actual" al montar', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByLabelText(/^contraseña actual$/i)).toHaveFocus()
    })
  })

  // ----- Validation -----
  it('muestra error cuando contraseña actual es muy corta', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'corta')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevaPassword123')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevaPassword123')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    const alerts = await screen.findAllByText(/mínimo 8 caracteres/i)
    expect(alerts.length).toBeGreaterThan(0)
  })

  it('muestra error cuando nueva contraseña es muy corta', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'corta')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'corta')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText(/mínimo 8 caracteres/i)).toBeInTheDocument()
  })

  it('muestra error cuando "confirmar" no coincide con "nueva"', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevaPassword123')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'OtraDistinta456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText(/las contraseñas no coinciden/i)).toBeInTheDocument()
  })

  it('muestra error cuando "nueva" es igual a "actual"', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'Password123')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'Password123')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText(/debe ser diferente a la actual/i)).toBeInTheDocument()
  })

  it('NO llama al backend si el form es inválido', async () => {
    const user = userEvent.setup()
    const spy = vi.fn()
    server.events.on('request:start', spy)
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'corta')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    // Varios campos pueden mostrar el mismo mensaje (vacíos + corto) — basta uno
    await waitFor(() => {
      const errors = screen.getAllByText(/mínimo 8 caracteres/i)
      expect(errors.length).toBeGreaterThan(0)
    })
    expect(spy).not.toHaveBeenCalled()
    server.events.removeListener('request:start', spy)
  })

  // ----- Interaction -----
  it('cancelar navega a la home', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  // ----- API integration -----
  it('cambio exitoso muestra toast y navega a la home', async () => {
    const user = userEvent.setup()
    const toastSpy = vi.spyOn(toast, 'success').mockImplementation(() => 'id')
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
    expect(toastSpy).toHaveBeenCalledWith('Contraseña actualizada')
  })

  it('AUTH-004 (contraseña actual incorrecta) asigna error al campo correspondiente', async () => {
    const user = userEvent.setup()
    server.use(
      changePasswordErrorResponse(400, {
        type: 'urn:tms:error:auth-004',
        title: 'Wrong current password',
        status: 400,
        detail: 'La contraseña actual proporcionada es incorrecta',
        code: 'AUTH-004',
        traceId: 'test-1',
      }),
    )
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'WrongPassword')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText(/la contraseña actual proporcionada es incorrecta/i)).toBeInTheDocument()
  })

  it('COM-001 con errors[] mapea cada error a su campo', async () => {
    const user = userEvent.setup()
    server.use(
      changePasswordErrorResponse(400, {
        type: 'urn:tms:error:com-001',
        title: 'Validation failed',
        status: 400,
        detail: 'La solicitud contiene errores de validación',
        code: 'COM-001',
        traceId: 'test-2',
        errors: [
          { field: 'newPassword', message: 'Backend: política de password', code: 'POLICY' },
        ],
      }),
    )
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText(/backend: política de password/i)).toBeInTheDocument()
  })

  // ----- A11y -----
  it('los inputs tienen labels asociados', () => {
    renderPage()
    expect(screen.getByLabelText(/^contraseña actual$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^nueva contraseña$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^confirmar nueva contraseña$/i)).toBeInTheDocument()
  })

  it('los mensajes de error tienen role="alert"', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'corta')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    const alerts = await screen.findAllByRole('alert')
    expect(alerts.length).toBeGreaterThan(0)
  })
})
