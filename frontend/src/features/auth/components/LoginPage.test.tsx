import { LOGIN_PATH, OPERATIONS_BASE, QUOTATIONS_BASE, WAREHOUSE_BASE } from '../../../shared/paths'
import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { LoginPage } from './LoginPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { server } from '../../../test/mocks/server'
import { loginAsRoleResponse, loginErrorResponse } from '../../../test/mocks/handlers/auth'

function renderLogin(initialPath: string | { pathname: string; state: { from: string } } = LOGIN_PATH) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path={LOGIN_PATH} element={<LoginPage />} />
            <Route path={QUOTATIONS_BASE} element={<div>HOME</div>} />
            <Route path={WAREHOUSE_BASE} element={<div>ALMACEN</div>} />
            <Route path={OPERATIONS_BASE} element={<div>OPERACIONES</div>} />
            <Route path="/clients" element={<div>CLIENTS</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // Limpieza explicita por si el polyfill de setup.ts no actuo a tiempo
    // entre tests que setean sesion (race con el unmount de RTL).
    tokenStorage.clear()
  })

  // Si un test que stubea globals (ej. window.location) falla antes de su
  // propio unstub, que no contamine a los siguientes.
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // ----- Render -----
  it('renderiza el form con campos de usuario y contraseña', () => {
    renderLogin()
    // level: 1 para distinguir del botón que también dice "Iniciar sesión".
    expect(screen.getByRole('heading', { name: /iniciar sesión/i, level: 1 })).toBeInTheDocument()
    expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/contraseña/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /iniciar sesión/i })).toBeInTheDocument()
  })

  it('hace focus en el campo usuario al montar', async () => {
    renderLogin()
    await waitFor(() => {
      expect(screen.getByLabelText(/usuario/i)).toHaveFocus()
    })
  })

  // ----- Validación -----
  it('muestra error cuando usuario es muy corto', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'ab')
    await user.type(screen.getByLabelText(/contraseña/i), 'Password123')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    expect(await screen.findByText(/mínimo 3 caracteres/i)).toBeInTheDocument()
  })

  it('muestra error cuando usuario contiene caracteres inválidos', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'user@name')
    await user.type(screen.getByLabelText(/contraseña/i), 'Password123')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    expect(await screen.findByText(/letras, números, puntos/i)).toBeInTheDocument()
  })

  it('muestra error cuando contraseña es muy corta', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'admin')
    await user.type(screen.getByLabelText(/contraseña/i), 'corta')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    expect(await screen.findByText(/mínimo 8 caracteres/i)).toBeInTheDocument()
  })

  it('NO llama al backend si el form es inválido', async () => {
    const user = userEvent.setup()
    const spy = vi.fn()
    server.events.on('request:start', spy)
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'ab')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    await waitFor(() => expect(screen.getByText(/mínimo 3 caracteres/i)).toBeInTheDocument())
    expect(spy).not.toHaveBeenCalled()
    server.events.removeListener('request:start', spy)
  })

  // ----- API integration -----
  it('login exitoso navega a la home', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'admin')
    await user.type(screen.getByLabelText(/contraseña/i), 'Admin1234')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  // ----- Landing por rol (unificación v1+v2) -----
  it('login como dispatcher aterriza en operaciones sin salir de la SPA', async () => {
    server.use(loginAsRoleResponse('dispatcher'))
    const assignSpy = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign: assignSpy })

    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'jdiaz')
    await user.type(screen.getByLabelText(/contraseña/i), 'Dispatch1234')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))

    expect(await screen.findByText('OPERACIONES')).toBeInTheDocument()
    // Antes salía a v1 con un full page load; ahora el control de viajes vive
    // en esta SPA y lo navega el router.
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it.each(['finance_manager', 'warehouse_keeper'] as const)(
    'login como %s aterriza en almacén sin salir de la SPA',
    async (role) => {
      server.use(loginAsRoleResponse(role))
      const assignSpy = vi.fn()
      vi.stubGlobal('location', { ...window.location, assign: assignSpy })

      const user = userEvent.setup()
      renderLogin()
      await user.type(screen.getByLabelText(/usuario/i), 'almacenera')
      await user.type(screen.getByLabelText(/contraseña/i), 'Almacen1234')
      await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))

      expect(await screen.findByText('ALMACEN')).toBeInTheDocument()
      // Almacén vive en esta SPA: navega el router, no un full page load.
      expect(assignSpy).not.toHaveBeenCalled()
    },
  )

  it('login como operations_manager aterriza en cotizaciones (confirmado 2026-06-12)', async () => {
    server.use(loginAsRoleResponse('operations_manager'))
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'omanager')
    await user.type(screen.getByLabelText(/contraseña/i), 'Manager1234')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  // Los dos casos que siguen fijan la regla del destino guardado: el enlace
  // directo gana si el rol puede abrirlo, y si no, cae en su principal.
  // Estuvieron apagados mientras el componente hacía dos navegaciones, la del
  // envío y la del render, que corrían juntas y volvían el destino no
  // determinista; con una sola, la del render, dejaron de ser intermitentes.
  it('con un destino guardado que su rol NO puede abrir, aterriza en su principal', async () => {
    // El despachador que llega por un enlace a una cotización: antes veía "Sin
    // acceso", que es un error de permisos donde correspondía su pantalla de
    // trabajo. El enlace directo gana, pero solo si el rol puede abrirlo.
    server.use(loginAsRoleResponse('dispatcher'))
    const user = userEvent.setup()
    renderLogin({ pathname: LOGIN_PATH, state: { from: `${QUOTATIONS_BASE}/12` } })
    await user.type(screen.getByLabelText(/usuario/i), 'jdiaz')
    await user.type(screen.getByLabelText(/contraseña/i), 'Dispatch1234')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))

    expect(await screen.findByText('OPERACIONES')).toBeInTheDocument()
  })

  it('login exitoso navega a `from` si vino redireccionado', async () => {
    server.use(loginAsRoleResponse('admin'))
    const user = userEvent.setup()
    render(
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
          })
        }
      >
        <AuthProvider>
          <MemoryRouter
            initialEntries={[{ pathname: LOGIN_PATH, state: { from: '/clients' } }]}
          >
            <Routes>
              <Route path={LOGIN_PATH} element={<LoginPage />} />
              <Route path={QUOTATIONS_BASE} element={<div>HOME</div>} />
              <Route path="/clients" element={<div>CLIENTS</div>} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    )
    await user.type(screen.getByLabelText(/usuario/i), 'admin')
    await user.type(screen.getByLabelText(/contraseña/i), 'Admin1234')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))

    expect(await screen.findByText('CLIENTS')).toBeInTheDocument()
  })

  it('muestra toast con detail del backend en 401 AUTH-001', async () => {
    const user = userEvent.setup()
    const toastSpy = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    server.use(
      loginErrorResponse(401, {
        type: 'urn:tms:error:auth-001',
        title: 'Invalid credentials',
        status: 401,
        detail: 'Usuario o contraseña incorrectos',
        code: 'AUTH-001',
        traceId: 'trace-1',
      }),
    )
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'admin')
    await user.type(screen.getByLabelText(/contraseña/i), 'WrongPass1')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    await waitFor(() =>
      expect(toastSpy).toHaveBeenCalledWith('Usuario o contraseña incorrectos'),
    )
  })

  it('muestra toast con detail del backend en 401 AUTH-002 (usuario inactivo)', async () => {
    const user = userEvent.setup()
    const toastSpy = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    server.use(
      loginErrorResponse(401, {
        type: 'urn:tms:error:auth-002',
        title: 'User inactive',
        status: 401,
        detail: 'El usuario está desactivado',
        code: 'AUTH-002',
        traceId: 'trace-2',
      }),
    )
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'inactivo')
    await user.type(screen.getByLabelText(/contraseña/i), 'Inactivo1234')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    await waitFor(() => expect(toastSpy).toHaveBeenCalledWith('El usuario está desactivado'))
  })

  it('en 400 COM-001 con errors[], asigna errores a los campos correspondientes', async () => {
    const user = userEvent.setup()
    server.use(
      loginErrorResponse(400, {
        type: 'urn:tms:error:com-001',
        title: 'Validation failed',
        status: 400,
        detail: 'La solicitud contiene errores de validación',
        code: 'COM-001',
        traceId: 'trace-3',
        errors: [
          { field: 'username', message: 'Backend dice: usuario inválido', code: 'INVALID' },
        ],
      }),
    )
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'validuser')
    await user.type(screen.getByLabelText(/contraseña/i), 'Password123')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    expect(await screen.findByText(/backend dice: usuario inválido/i)).toBeInTheDocument()
  })

  // ----- A11y -----
  it('los inputs tienen labels asociados (accesibilidad)', () => {
    renderLogin()
    expect(screen.getByLabelText(/usuario/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/contraseña/i)).toBeInTheDocument()
  })

  it('los mensajes de error tienen role="alert"', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.type(screen.getByLabelText(/usuario/i), 'ab')
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }))
    const alerts = await screen.findAllByRole('alert')
    expect(alerts.length).toBeGreaterThan(0)
  })
})
