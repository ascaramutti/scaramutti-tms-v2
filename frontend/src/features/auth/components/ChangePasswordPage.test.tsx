import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { ChangePasswordPage } from './ChangePasswordPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { server } from '../../../test/mocks/server'
import { changePasswordErrorResponse, fakeUser } from '../../../test/mocks/handlers/auth'

const API = 'http://localhost:8080/api/v1'

function renderPage(initialPath = '/cotizaciones/cuenta/cambiar-contrasena') {
  // La página usa useAuth (landing por rol al salir): requiere AuthProvider
  // con sesión. El /auth/me default del server responde admin.
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const renderResult = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/cotizaciones/cuenta/cambiar-contrasena" element={<ChangePasswordPage />} />
            <Route path="/cotizaciones" element={<div>HOME</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { ...renderResult, queryClient }
}

describe('ChangePasswordPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    tokenStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
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

  it('NO llama al endpoint de change-password si el form es inválido', async () => {
    const user = userEvent.setup()
    // Solo nos importa change-password: el AuthProvider sí llama a /auth/me
    // al montar (sesión) y eso es esperado.
    const spy = vi.fn()
    const onRequest = ({ request }: { request: Request }) => {
      if (request.url.includes('/auth/change-password')) spy(request.url)
    }
    server.events.on('request:start', onRequest)
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'corta')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    // Varios campos pueden mostrar el mismo mensaje (vacíos + corto) — basta uno
    await waitFor(() => {
      const errors = screen.getAllByText(/mínimo 8 caracteres/i)
      expect(errors.length).toBeGreaterThan(0)
    })
    expect(spy).not.toHaveBeenCalled()
    server.events.removeListener('request:start', onRequest)
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

  // ----- Landing por rol al salir (unificación v1+v2) -----
  it('dispatcher: cambio exitoso redirige a v1 (raíz del dominio, full page load)', async () => {
    // El dispatcher es el caso que motivó goToLanding: navegar a /cotizaciones
    // lo dejaba en la vista "Sin acceso" (no tiene rol para el módulo).
    server.use(
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json({ ...fakeUser, username: 'jdiaz', role: 'dispatcher' }),
      ),
    )
    const assignSpy = vi.fn()
    // Stub explícito: en happy-dom las props de Location son accessors del
    // prototipo y el spread {...window.location} produce {} — no fingimos
    // preservar el objeto, solo lo que el test usa.
    vi.stubGlobal('location', { assign: assignSpy } as unknown as Location)
    const user = userEvent.setup()
    const { queryClient } = renderPage()
    // goToLanding lee user del render vigente al click: esperar la sesión
    // ANTES de interactuar (sin esto el rol podría no estar cargado).
    await waitFor(() =>
      expect(queryClient.getQueryData(currentUserQueryKey)).toMatchObject({ role: 'dispatcher' }),
    )
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Dispatch1234')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))

    await waitFor(() => expect(assignSpy).toHaveBeenCalledWith('/'))
    // No navegó dentro de la SPA:
    expect(screen.queryByText('HOME')).not.toBeInTheDocument()
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
