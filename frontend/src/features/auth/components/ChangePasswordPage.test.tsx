import { CHANGE_PASSWORD_PATH, OPERATIONS_BASE, QUOTATIONS_BASE } from '../../../shared/paths'
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

function renderPage(initialPath = CHANGE_PASSWORD_PATH) {
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
            <Route path={CHANGE_PASSWORD_PATH} element={<ChangePasswordPage />} />
            <Route path={QUOTATIONS_BASE} element={<div>HOME</div>} />
            <Route path={OPERATIONS_BASE} element={<div>OPERACIONES</div>} />
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

  it('rechaza una contraseña actual de solo espacios, sin llamar al servidor', async () => {
    const user = userEvent.setup()
    const spy = vi.fn()
    server.use(http.post(`${API}/auth/change-password`, () => {
      spy()
      return new HttpResponse(null, { status: 204 })
    }))
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), '        ')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    await waitFor(() =>
      expect(screen.getByLabelText(/^contraseña actual$/i)).toHaveAccessibleDescription('No puede tener solo espacios'),
    )
    expect(spy).not.toHaveBeenCalled()
  })

  it('rechaza una nueva contraseña de solo espacios, sin llamar al servidor', async () => {
    const user = userEvent.setup()
    const spy = vi.fn()
    server.use(http.post(`${API}/auth/change-password`, () => {
      spy()
      return new HttpResponse(null, { status: 204 })
    }))
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), '         ')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), '         ')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    await waitFor(() =>
      expect(screen.getByLabelText(/^nueva contraseña$/i)).toHaveAccessibleDescription('No puede tener solo espacios'),
    )
    expect(spy).not.toHaveBeenCalled()
  })

  // BCrypt procesa hasta 72 bytes: 37 eñes son 37 caracteres, pero 74 bytes.
  it.each([
    ['la actual', /^contraseña actual$/i, 'ñ'.repeat(37), 'NuevoPassword456'],
    ['la nueva', /^nueva contraseña$/i, 'Password123', 'ñ'.repeat(37)],
  ])('rechaza %s de más de 72 bytes, sin llamar al servidor', async (_campo, etiqueta, actual, nueva) => {
    const user = userEvent.setup()
    const spy = vi.fn()
    server.use(http.post(`${API}/auth/change-password`, () => {
      spy()
      return new HttpResponse(null, { status: 204 })
    }))
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), actual)
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), nueva)
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), nueva)
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    await waitFor(() =>
      expect(screen.getByLabelText(etiqueta)).toHaveAccessibleDescription(
        'Es demasiado larga: máximo 72 bytes; las tildes y los emojis cuentan doble o más',
      ),
    )
    expect(spy).not.toHaveBeenCalled()
  })

  it('manda una nueva de 72 bytes con tildes, tal cual', async () => {
    const user = userEvent.setup()
    const cuerpos: unknown[] = []
    server.use(http.post(`${API}/auth/change-password`, async ({ request }) => {
      cuerpos.push(await request.json())
      return new HttpResponse(null, { status: 204 })
    }))
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'á'.repeat(36))
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'á'.repeat(36))
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
    expect(cuerpos).toEqual([{ currentPassword: 'Password123', newPassword: 'á'.repeat(36) }])
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

  it('manda la contraseña actual y la nueva, cada una en su lugar y sin la confirmación', async () => {
    const user = userEvent.setup()
    const cuerpos: unknown[] = []
    server.use(http.post(`${API}/auth/change-password`, async ({ request }) => {
      cuerpos.push(await request.json())
      return new HttpResponse(null, { status: 204 })
    }))
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
    expect(cuerpos).toEqual([{ currentPassword: 'Password123', newPassword: 'NuevoPassword456' }])
  })

  it('manda las claves tal cual, con sus espacios', async () => {
    const user = userEvent.setup()
    const cuerpos: unknown[] = []
    server.use(http.post(`${API}/auth/change-password`, async ({ request }) => {
      cuerpos.push(await request.json())
      return new HttpResponse(null, { status: 204 })
    }))
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), ' Clave con espacios ')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), ' Nueva clave 2 ')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), ' Nueva clave 2 ')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
    expect(cuerpos).toEqual([{ currentPassword: ' Clave con espacios ', newPassword: ' Nueva clave 2 ' }])
  })

  it('un doble clic manda un solo pedido', async () => {
    const user = userEvent.setup()
    let soltar!: () => void
    const suelta = new Promise<void>((resolve) => (soltar = resolve))
    let pedidos = 0
    server.use(http.post(`${API}/auth/change-password`, async () => {
      pedidos++
      await suelta
      return new HttpResponse(null, { status: 204 })
    }))
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.dblClick(screen.getByRole('button', { name: /cambiar contraseña/i }))
    await waitFor(() => expect(pedidos).toBe(1))
    soltar()
    expect(await screen.findByText('HOME')).toBeInTheDocument()
    expect(pedidos).toBe(1)
  })

  // ----- Landing por rol al salir (unificación v1+v2) -----
  it('dispatcher: cambio exitoso lo devuelve a operaciones, dentro de la SPA', async () => {
    // El dispatcher es el caso que motivó goToLanding: navegar al módulo
    // comercial lo dejaba en la vista "Sin acceso" (no tiene rol para él). Su
    // landing dejó de estar en v1, pero sigue sin ser el módulo comercial.
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

    expect(await screen.findByText('OPERACIONES')).toBeInTheDocument()
    // Operaciones vive en esta SPA: navega el router, sin full page load.
    expect(assignSpy).not.toHaveBeenCalled()
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
    await waitFor(() =>
      expect(screen.getByLabelText(/^contraseña actual$/i)).toHaveAccessibleDescription(
        'La contraseña actual proporcionada es incorrecta',
      ),
    )
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

  it.each([
    [
      'AUTH-007',
      'El token de acceso expiró. Use /auth/refresh para renovarlo.',
      'Tu sesión venció. Cierra sesión y vuelve a ingresar.',
    ],
    [
      'AUTH-008',
      'El token de acceso es inválido o está mal formado',
      'Tu sesión no es válida. Cierra sesión y vuelve a ingresar.',
    ],
  ])('el 401 %s avisa con un texto propio, no con el del servidor', async (code, detail, propio) => {
    const user = userEvent.setup()
    const toastSpy = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    server.use(
      changePasswordErrorResponse(401, {
        type: `urn:tms:error:${code.toLowerCase()}`,
        title: 'Unauthorized',
        status: 401,
        detail,
        code,
        traceId: 'test-3',
      }),
    )
    renderPage()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    await waitFor(() => expect(toastSpy).toHaveBeenCalledWith(propio))
    expect(toastSpy).toHaveBeenCalledTimes(1)
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
