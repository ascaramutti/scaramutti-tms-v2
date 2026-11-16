import { afterEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { UserCreatePage } from './UserCreatePage'
import { USERS_BASE, userAccountCreatePath, userAccountDetailPath } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { workerKeys } from '../../workers/queryKeys'
import { userAccountKeys } from '../queryKeys'
import {
  accountCandidatesCapture,
  accountCandidatesEmpty,
  accountCandidatesError,
  createUserAccountCapture,
  createUserAccountEmpty,
  createUserAccountNetworkError,
  createUserAccountProblem,
  fakeAccountCandidate,
  fakeTemporaryPasswordResponse,
  fakeUserAccountDetail,
  TEMPORARY_PASSWORD,
} from '../../../test/mocks/handlers/users'
import { browserLeftovers } from '../../../test/browserLeftovers'
import { hidePageAndReadText } from '../../../test/pagehide'
import type { SessionUser, UserAccountCandidateResponse, UserRole } from '../../../api'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

afterEach(() => {
  vi.mocked(toast.error).mockClear()
  vi.mocked(toast.success).mockClear()
})

const RAYA = '\u2014'
const GRUPOS = ['AbCd', 'EfGh', 'JkMn']
/** Lo que no salió en este tiempo no iba a salir: los pedidos de los tests responden al instante. */
const UN_RATO_MS = 300
const AYUDA_CON_NIVEL = 'Solo aparecen los activos, con cargo que inicia sesión y de un nivel menor al tuyo.'
const AYUDA_ADMIN = 'Solo aparecen los activos y con cargo que inicia sesión.'

function sesion(role: UserRole = 'general_manager'): SessionUser {
  return {
    id: 1,
    username: `user-${role}`,
    fullName: `Usuario ${role}`,
    position: 'Cargo de prueba',
    role,
    isActive: true,
    workerId: 29119,
  }
}

const JUAN = fakeAccountCandidate()
const ANA = fakeAccountCandidate({
  workerId: 47,
  fullName: 'Ana Rojas',
  firstName: 'Ana',
  paternalLastName: 'Rojas',
  maternalLastName: null,
  role: { name: 'sales', description: 'Ventas', level: 2, canLogin: true, driverProfile: 'NONE' },
  proposedUsername: 'arojas',
})
const LUIS = fakeAccountCandidate({ workerId: 58, fullName: 'Luis Perez', firstName: 'Luis', paternalLastName: 'Perez', proposedUsername: 'lperez' })
const ROSA = fakeAccountCandidate({ workerId: 63, fullName: 'Rosa Díaz', firstName: 'Rosa', paternalLastName: 'Díaz', proposedUsername: 'rdiaz' })

/** Entra desde la lista: "Ver la cuenta" reemplaza el alta, y volver atrás tiene que caer en la lista. */
function renderPage(user: SessionUser = sesion()) {
  // La frescura de la app: la lista tiene que volver a pedirse aunque la general no lo haga.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0, staleTime: 30_000 } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData<SessionUser>(currentUserQueryKey, user)
  const router = createMemoryRouter(
    [
      { path: userAccountCreatePath(), element: <UserCreatePage /> },
      { path: USERS_BASE, element: <p>Lista de usuarios</p> },
      { path: `${USERS_BASE}/:id`, element: <p>Detalle de la cuenta</p> },
    ],
    { initialEntries: [USERS_BASE, userAccountCreatePath()], initialIndex: 1 },
  )
  const vista = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { router, queryClient, container: vista.container }
}

type Usuario = ReturnType<typeof userEvent.setup>

const crearDe = (nombre: string) => screen.findByRole('button', { name: `Crear usuario de ${nombre}` })
const dialogo = () => screen.getByRole('dialog', { name: 'Crear usuario' })
const confirmarEn = () => within(dialogo()).getByRole('button', { name: 'Crear usuario' })
const cancelarEn = () => within(dialogo()).getByRole('button', { name: 'Cancelar' })
const unRato = () => new Promise((resolve) => setTimeout(resolve, UN_RATO_MS))

async function abrir(user: Usuario, nombre = 'Juan Pérez Quispe') {
  await user.click(await crearDe(nombre))
  return dialogo()
}

async function crearCuenta(
  user: Usuario,
  respuesta = fakeTemporaryPasswordResponse(),
  candidatos: UserAccountCandidateResponse[] = [JUAN],
) {
  const pedidos: { searches?: string[] } = {}
  const envios: { bodies?: string[] } = {}
  server.use(accountCandidatesCapture(pedidos, [candidatos]), createUserAccountCapture(envios, respuesta))
  const vista = renderPage()
  await abrir(user)
  await user.click(confirmarEn())
  await screen.findByRole('heading', { name: 'Cuenta creada' })
  return { ...vista, envios, pedidos }
}

function fueraDeLaPantalla(queryClient: QueryClient, router: ReturnType<typeof renderPage>['router']) {
  return browserLeftovers(queryClient, router.state.location, [
    vi.mocked(toast.success).mock.calls,
    vi.mocked(toast.error).mock.calls,
  ])
}

describe('UserCreatePage', () => {
  describe('al abrir', () => {
    it('tiene título, descripción y vuelve a la lista', () => {
      renderPage()
      expect(screen.getByRole('heading', { level: 1, name: 'Nuevo usuario' })).toBeInTheDocument()
      expect(
        screen.getByText(
          'Crea la cuenta de un trabajador. El sistema genera el usuario y una contraseña temporal; la persona la cambia desde "Cambiar contraseña".',
        ),
      ).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Volver a usuarios' })).toHaveAttribute('href', USERS_BASE)
    })

    it('pide la lista una sola vez y sin filtro', async () => {
      const pedidos: { searches?: string[] } = {}
      server.use(accountCandidatesCapture(pedidos))
      renderPage()
      await crearDe('Juan Pérez Quispe')
      await unRato()
      expect(pedidos.searches).toEqual([''])
    })

    it('mientras carga, lo dice y no muestra la tabla ni la ayuda', async () => {
      server.use(accountCandidatesCapture({}, [[JUAN]], 500))
      renderPage()
      expect(await screen.findByRole('status', { name: 'Cargando trabajadores' })).toBeInTheDocument()
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
      expect(screen.queryByText(AYUDA_CON_NIVEL)).not.toBeInTheDocument()
      await crearDe('Juan Pérez Quispe')
    })

    it('no tiene campos: ni de usuario, ni de contraseña, ni de búsqueda', async () => {
      const { container } = renderPage()
      await crearDe('Juan Pérez Quispe')
      expect(container.querySelectorAll('input, select, textarea')).toHaveLength(0)
      expect(container.querySelector('form')).toBeNull()
    })
  })

  describe('la lista', () => {
    it('trae a cada pendiente en el orden en que llega, con su nombre completo y su cargo', async () => {
      server.use(accountCandidatesCapture({}, [[ANA, JUAN]]))
      renderPage()
      await crearDe('Ana Rojas')
      const tabla = within(screen.getByRole('region', { name: 'Trabajadores sin cuenta' })).getByRole('table', {
        name: 'Trabajadores sin cuenta',
      })
      expect(within(tabla).getAllByRole('columnheader').map((celda) => celda.textContent)).toEqual([
        'Nombre completo',
        'Cargo',
        'Acciones',
      ])
      const filas = within(tabla).getAllByRole('row').slice(1)
      expect(filas.map((fila) => within(fila).getAllByRole('cell').map((celda) => celda.textContent))).toEqual([
        ['Ana Rojas', 'Ventas', 'Crear usuario'],
        ['Juan Pérez Quispe', 'Almacenero', 'Crear usuario'],
      ])
    })

    it('el botón de cada fila nombra a su trabajador', async () => {
      server.use(accountCandidatesCapture({}, [[ANA, JUAN]]))
      renderPage()
      await crearDe('Ana Rojas')
      expect(
        screen.getAllByRole('button', { name: /^Crear usuario/ }).map((boton) => boton.getAttribute('aria-label')),
      ).toEqual(['Crear usuario de Ana Rojas', 'Crear usuario de Juan Pérez Quispe'])
    })

    it('las filas no se abren: la única acción es su botón', async () => {
      renderPage()
      await crearDe('Juan Pérez Quispe')
      expect(screen.getAllByRole('row').slice(1)[0]).not.toHaveAttribute('tabindex')
      expect(screen.queryByRole('button', { name: /^Ver/ })).not.toBeInTheDocument()
    })

    it.each(['general_manager', 'operations_manager', 'finance_manager'] as const)(
      'con %s, la ayuda bajo la lista habla del nivel',
      async (role) => {
        renderPage(sesion(role))
        await crearDe('Juan Pérez Quispe')
        expect(screen.getByText(AYUDA_CON_NIVEL)).toBeInTheDocument()
        expect(screen.queryByText(AYUDA_ADMIN)).not.toBeInTheDocument()
      },
    )

    it('con admin, la ayuda no habla de niveles', async () => {
      renderPage(sesion('admin'))
      await crearDe('Juan Pérez Quispe')
      expect(screen.getByText(AYUDA_ADMIN)).toBeInTheDocument()
      expect(screen.queryByText(AYUDA_CON_NIVEL)).not.toBeInTheDocument()
    })

    it('la ayuda va bajo la tabla', async () => {
      renderPage()
      await crearDe('Juan Pérez Quispe')
      const tabla = screen.getByRole('table')
      const ayuda = screen.getByText(AYUDA_CON_NIVEL)
      expect(tabla.compareDocumentPosition(ayuda) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('sin pendientes, lo dice con la ayuda y sin tabla', async () => {
      server.use(accountCandidatesCapture({}, [[]]))
      renderPage()
      expect(await screen.findByText('No hay trabajadores pendientes de cuenta.')).toBeInTheDocument()
      expect(screen.getAllByText(AYUDA_CON_NIVEL)).toHaveLength(1)
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Volver a usuarios' })).toBeInTheDocument()
    })

    it('con admin, sin pendientes, la ayuda no habla de niveles', async () => {
      server.use(accountCandidatesCapture({}, [[]]))
      renderPage(sesion('admin'))
      await screen.findByText('No hay trabajadores pendientes de cuenta.')
      expect(screen.getAllByText(AYUDA_ADMIN)).toHaveLength(1)
      expect(screen.queryByText(AYUDA_CON_NIVEL)).not.toBeInTheDocument()
    })

    it('si falla, lo avisa con Reintentar, tras reintentar una vez sola', async () => {
      const fallidos: { count?: number } = {}
      server.use(accountCandidatesError(fallidos, 500, ''))
      renderPage()
      expect(await screen.findByRole('alert')).toHaveTextContent(/^No se pudieron cargar los trabajadores\. Intenta de nuevo\.Reintentar$/)
      expect(screen.queryByText(AYUDA_CON_NIVEL)).not.toBeInTheDocument()
      await unRato()
      expect(fallidos.count).toBe(2)
    })

    it('si falla con un detail, muestra el detail', async () => {
      server.use(accountCandidatesError({}, 500, 'La base no responde.'))
      renderPage()
      expect(await screen.findByRole('alert')).toHaveTextContent(/^La base no responde\.Reintentar$/)
    })

    it('Reintentar vuelve a pedirla y la muestra', async () => {
      const user = userEvent.setup()
      const fallidos: { count?: number } = {}
      server.use(accountCandidatesError(fallidos, 500, 'detail', 2))
      renderPage()
      await user.click(await screen.findByRole('button', { name: 'Reintentar' }))
      expect(await crearDe('Juan Pérez Quispe')).toBeInTheDocument()
      expect(fallidos.count).toBe(3)
    })

    it('con un 403, el detail sin Reintentar y sin reintentar', async () => {
      const fallidos: { count?: number } = {}
      server.use(accountCandidatesError(fallidos, 403, 'Tu rol no puede hacer esto.'))
      renderPage()
      expect(await screen.findByRole('alert')).toHaveTextContent(/^Tu rol no puede hacer esto\.$/)
      expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument()
      await unRato()
      expect(fallidos.count).toBe(1)
    })

    it('con un 403 sin detail, dice que no hay permiso', async () => {
      server.use(accountCandidatesError({}, 403, ''))
      renderPage()
      expect(await screen.findByRole('alert')).toHaveTextContent(/^No tienes permiso para ver los trabajadores sin cuenta\.$/)
    })

    it('un 200 sin cuerpo es un fallo', async () => {
      server.use(accountCandidatesEmpty())
      renderPage()
      expect(await screen.findByRole('alert')).toHaveTextContent(/^No se pudieron cargar los trabajadores\. Intenta de nuevo\./)
    })

    it('fuera de la pantalla no queda en la caché: al volver, se carga de nuevo', async () => {
      const pedidos: { searches?: string[] } = {}
      server.use(accountCandidatesCapture(pedidos, [[JUAN], [ANA]], 200))
      const { router, queryClient } = renderPage()
      await crearDe('Juan Pérez Quispe')
      await router.navigate(USERS_BASE)
      await waitFor(() => expect(queryClient.getQueryCache().find({ queryKey: userAccountKeys.candidates() })).toBeUndefined())
      await router.navigate(userAccountCreatePath())
      expect(await screen.findByRole('status', { name: 'Cargando trabajadores' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Crear usuario de Juan Pérez Quispe' })).not.toBeInTheDocument()
      await crearDe('Ana Rojas')
      expect(pedidos.searches).toHaveLength(2)
    })
  })

  describe('la confirmación', () => {
    it('es la del trabajador de su fila: nombre, cargo, sus nombres y la propuesta', async () => {
      const user = userEvent.setup()
      server.use(accountCandidatesCapture({}, [[JUAN, ANA]]))
      renderPage()
      const dialog = await abrir(user, 'Ana Rojas')

      expect(within(dialog).getByText('Vas a crear la cuenta de Ana Rojas (Ventas).')).toBeInTheDocument()
      const nombre = within(dialog).getByLabelText('Nombre')
      expect(within(nombre).getAllByRole('term').map((rotulo) => rotulo.textContent)).toEqual([
        'Nombres',
        'Apellido paterno',
        'Apellido materno',
      ])
      expect(within(nombre).getAllByRole('definition').map((valor) => valor.textContent)).toEqual(['Ana', 'Rojas', RAYA])
      const propuesta = within(dialog).getByText('arojas')
      expect(propuesta).toHaveClass('font-mono')
      expect(propuesta.parentElement).toHaveTextContent(
        /^Se generará el usuario arojas y una contraseña temporal, que se muestra una sola vez\.$/,
      )
    })

    it('con materno, sus tres nombres', async () => {
      const user = userEvent.setup()
      renderPage()
      const dialog = await abrir(user)
      expect(within(within(dialog).getByLabelText('Nombre')).getAllByRole('definition').map((valor) => valor.textContent)).toEqual([
        'Juan',
        'Pérez',
        'Quispe',
      ])
    })

    it('abrirla no envía nada', async () => {
      const user = userEvent.setup()
      const envios: { bodies?: string[] } = {}
      server.use(createUserAccountCapture(envios))
      renderPage()
      await abrir(user)
      await unRato()
      expect(envios.bodies).toEqual([])
    })

    it.each([
      ['Cancelar', (user: Usuario) => user.click(cancelarEn())],
      ['Escape', (user: Usuario) => user.keyboard('{Escape}')],
    ])('con %s se cierra sin enviar y el foco vuelve al botón de su fila', async (_, cerrar) => {
      const user = userEvent.setup()
      const envios: { bodies?: string[] } = {}
      server.use(accountCandidatesCapture({}, [[JUAN, ANA]]), createUserAccountCapture(envios))
      renderPage()
      await abrir(user, 'Ana Rojas')
      await cerrar(user)
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Crear usuario de Ana Rojas' })).toHaveFocus()
      expect(envios.bodies).toEqual([])
    })
  })

  describe('el envío', () => {
    it('envía solo el trabajador de la fila, una vez', async () => {
      const user = userEvent.setup()
      const envios: { bodies?: string[] } = {}
      server.use(accountCandidatesCapture({}, [[JUAN, ANA]]), createUserAccountCapture(envios))
      renderPage()
      await abrir(user, 'Ana Rojas')
      await user.click(confirmarEn())
      await screen.findByRole('heading', { name: 'Cuenta creada' })
      expect(envios.bodies).toEqual(['{"workerId":47}'])
    })

    it('en vuelo dice que crea y no deja cancelar, cerrar ni volver', async () => {
      const user = userEvent.setup()
      server.use(createUserAccountCapture({}, fakeTemporaryPasswordResponse(), 300))
      renderPage()
      await abrir(user)
      await user.click(confirmarEn())

      expect(within(dialogo()).getByRole('button', { name: /Creando…/ })).toBeDisabled()
      expect(cancelarEn()).toBeDisabled()
      await user.keyboard('{Escape}')
      expect(dialogo()).toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'Volver a usuarios' })).not.toBeInTheDocument()
      expect(screen.getByText('(no disponible)', { exact: false })).toBeInTheDocument()
      await screen.findByRole('heading', { name: 'Cuenta creada' })
    })
  })

  describe('los rechazos', () => {
    async function rechazar(code: string, status: number, despues: UserAccountCandidateResponse[] = [JUAN]) {
      const user = userEvent.setup()
      const pedidos: { searches?: string[] } = {}
      server.use(accountCandidatesCapture(pedidos, [[JUAN, ANA], despues]), createUserAccountProblem(code, status, 'detail del backend'))
      const vista = renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      await waitFor(() => expect(confirmarEn()).toBeEnabled())
      return { ...vista, user, pedidos }
    }

    it.each([
      [
        'USR-003',
        409,
        'Este trabajador ya tiene cuenta. Si la acabas de crear y no viste la contraseña, restablécela desde el detalle de la cuenta.',
      ],
      ['USR-004', 400, 'Ese trabajador no existe.'],
      ['USR-005', 409, 'Este trabajador está dado de baja.'],
      ['USR-006', 400, 'El cargo de este trabajador no inicia sesión.'],
      ['USR-008', 403, 'No puedes gestionar la cuenta de alguien de tu nivel o superior.'],
    ])('%s queda en la confirmación, con su texto, y vuelve a pedir la lista', async (code, status, texto) => {
      const { pedidos } = await rechazar(code, status)
      expect(within(dialogo()).getByRole('alert')).toHaveTextContent(new RegExp(`^${texto.replace(/[.?]/g, '\\$&')}$`))
      expect(toast.error).not.toHaveBeenCalled()
      await waitFor(() => expect(pedidos.searches).toEqual(['', '']))
      expect(confirmarEn()).toHaveFocus()
    })

    it.each([
      ['COM-003', 403, 'Tu rol no tiene permisos para esta acción.'],
      ['USR-002', 409, 'No se pudo asignar el nombre de usuario. Intenta de nuevo.'],
      ['USR-009', 409, 'Otra operación estaba en curso y no se pudo crear la cuenta. Intenta de nuevo.'],
      ['COM-500', 500, 'detail del backend'],
    ])('%s queda en la confirmación, con su texto, sin pedir la lista', async (code, status, texto) => {
      const { pedidos } = await rechazar(code, status)
      expect(within(dialogo()).getByRole('alert')).toHaveTextContent(new RegExp(`^${texto.replace(/[.?]/g, '\\$&')}$`))
      expect(toast.error).not.toHaveBeenCalled()
      await unRato()
      expect(pedidos.searches).toEqual([''])
      expect(confirmarEn()).toHaveFocus()
    })

    it.each([
      ['la fila que quedó en su lugar', [ANA, JUAN, LUIS, ROSA], [ANA, LUIS, ROSA], 'Luis Perez'],
      ['la nueva última, si era la última', [ANA, LUIS, JUAN], [ANA, LUIS], 'Luis Perez'],
    ])('si el rechazado ya no viene, sale de la lista y, al cerrar, el foco va a %s', async (_, antes, despues, siguiente) => {
      const user = userEvent.setup()
      server.use(accountCandidatesCapture({}, [antes, despues]), createUserAccountProblem('USR-003', 409))
      renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Crear usuario de Juan Pérez Quispe' })).not.toBeInTheDocument())
      expect(dialogo()).toBeInTheDocument()
      await user.click(cancelarEn())
      expect(screen.getByRole('button', { name: `Crear usuario de ${siguiente}` })).toHaveFocus()
    })

    it('si su fila sigue tras la recarga, aunque se corra, el foco vuelve a ella', async () => {
      const user = userEvent.setup()
      server.use(accountCandidatesCapture({}, [[JUAN, ANA], [LUIS, JUAN, ANA]]), createUserAccountProblem('USR-003', 409))
      renderPage()
      await abrir(user, 'Ana Rojas')
      await user.click(confirmarEn())
      await crearDe('Luis Perez')
      expect(dialogo()).toBeInTheDocument()
      await user.click(cancelarEn())
      expect(screen.getByRole('button', { name: 'Crear usuario de Ana Rojas' })).toHaveFocus()
    })

    it('un rechazo del trabajador marca vieja su ficha', async () => {
      const ficha = workerKeys.detail(JUAN.workerId)
      const rechazado = await rechazar('USR-003', 409)
      rechazado.queryClient.setQueryData(ficha, { id: JUAN.workerId })
      await rechazado.user.click(confirmarEn())
      await waitFor(() => expect(rechazado.queryClient.getQueryState(ficha)?.isInvalidated).toBe(true))
    })

    it('un aviso no marca vieja la ficha del trabajador', async () => {
      const ficha = workerKeys.detail(JUAN.workerId)
      const { user, queryClient } = await rechazar('USR-009', 409)
      queryClient.setQueryData(ficha, { id: JUAN.workerId })
      await user.click(confirmarEn())
      await waitFor(() => expect(confirmarEn()).toBeEnabled())
      await unRato()
      expect(queryClient.getQueryState(ficha)?.isInvalidated).toBe(false)
    })

    it('si la recarga tras un rechazo falla, la lista sigue con el aviso y Reintentar', async () => {
      const user = userEvent.setup()
      const pedidos = { count: 0 }
      server.use(
        http.get('http://localhost:8080/api/v1/user-account-candidates', () => {
          pedidos.count += 1
          return pedidos.count === 1 ? HttpResponse.json([JUAN]) : HttpResponse.json({ detail: '' }, { status: 500 })
        }),
        createUserAccountProblem('USR-003', 409),
      )
      renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      await waitFor(() => expect(pedidos.count).toBe(3))
      await user.click(cancelarEn())
      expect(await screen.findByRole('alert')).toHaveTextContent(/^No se pudieron cargar los trabajadores\. Intenta de nuevo\.Reintentar$/)
      expect(screen.getByRole('button', { name: 'Crear usuario de Juan Pérez Quispe' })).toBeInTheDocument()
    })

    it('si era el último, la lista queda vacía con el rechazo a la vista y, al cerrar, el foco va a volver', async () => {
      const { user } = await rechazar('USR-003', 409, [])
      await screen.findByText('No hay trabajadores pendientes de cuenta.')
      expect(within(dialogo()).getByRole('alert')).toHaveTextContent(/^Este trabajador ya tiene cuenta\./)
      await user.click(cancelarEn())
      expect(screen.getByRole('link', { name: 'Volver a usuarios' })).toHaveFocus()
    })

    it('volver a confirmar borra el rechazo anterior mientras envía', async () => {
      const { user } = await rechazar('USR-009', 409)
      server.use(createUserAccountCapture({}, fakeTemporaryPasswordResponse(), 300))
      await user.click(confirmarEn())
      expect(within(dialogo()).queryByRole('alert')).not.toBeInTheDocument()
      await screen.findByRole('heading', { name: 'Cuenta creada' })
    })

    it('sin red, el texto propio del alta', async () => {
      const user = userEvent.setup()
      server.use(createUserAccountNetworkError())
      renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      expect(await within(dialogo()).findByRole('alert')).toHaveTextContent(/^No se pudo crear la cuenta\. Intenta de nuevo\.$/)
      expect(screen.queryByRole('heading', { name: 'Cuenta creada' })).not.toBeInTheDocument()
    })

    it('un 201 sin cuerpo no muestra un panel vacío', async () => {
      const user = userEvent.setup()
      server.use(createUserAccountEmpty())
      renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      expect(await within(dialogo()).findByRole('alert')).toHaveTextContent(/^No se pudo crear la cuenta\. Intenta de nuevo\.$/)
      expect(screen.queryByRole('heading', { name: 'Cuenta creada' })).not.toBeInTheDocument()
    })
  })

  describe('la cuenta creada', () => {
    it('cierra la confirmación y reemplaza la lista con el usuario de la respuesta, no con la propuesta', async () => {
      const user = userEvent.setup()
      await crearCuenta(user, fakeTemporaryPasswordResponse({ account: fakeUserAccountDetail({ id: 42, username: 'jperez1' }) }))

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Trabajadores sin cuenta' })).not.toBeInTheDocument()
      const panel = screen.getByRole('region', { name: 'Cuenta creada' })
      expect(within(panel).getAllByRole('term').map((rotulo) => rotulo.textContent)).toEqual(['Usuario', 'Trabajador'])
      expect(within(panel).getByText('jperez1')).toHaveClass('font-mono')
      expect(within(panel).queryByText('jperez')).not.toBeInTheDocument()
      expect(within(panel).getByText('Juan Pérez Quispe · Gerente de Operaciones')).toBeInTheDocument()
    })

    it('pide entregarla y cambiarla, sin decir que el sistema obliga', async () => {
      const user = userEvent.setup()
      await crearCuenta(user)
      const panel = screen.getByRole('region', { name: 'Cuenta creada' })
      expect(
        within(panel).getByText(
          'Entrégale el usuario y la contraseña temporal a Juan Pérez Quispe y pídele que la cambie desde "Cambiar contraseña" al entrar.',
        ),
      ).toBeInTheDocument()
      expect(panel.textContent).not.toMatch(/oblig|deberá|tendrá que cambiar/i)
    })

    it('muestra la temporal de la respuesta en tres grupos, con Copiar y el aviso', async () => {
      const user = userEvent.setup()
      await crearCuenta(user)
      const panel = screen.getByRole('region', { name: 'Cuenta creada' })
      expect(within(panel).getByText('AbCd').parentElement).toHaveTextContent(TEMPORARY_PASSWORD)
      expect(within(panel).getByText('Contraseña temporal')).toBeInTheDocument()
      expect(within(panel).getByRole('button', { name: 'Copiar' })).toBeInTheDocument()
      expect(
        within(panel).getByText('No se vuelve a mostrar. Cópiala ahora y entrégala a la persona en privado; se escribe sin espacios.'),
      ).toBeInTheDocument()
    })

    it('el foco va al título del panel', async () => {
      const user = userEvent.setup()
      await crearCuenta(user)
      const titulo = screen.getByRole('heading', { name: 'Cuenta creada' })
      expect(titulo).toHaveFocus()
      // El entorno de los tests enfoca cualquier elemento; un navegador, solo uno con tabindex.
      expect(titulo).toHaveAttribute('tabindex', '-1')
    })

    it('"Ver la cuenta" es su única acción y reemplaza el alta por el detalle, sin estado', async () => {
      const user = userEvent.setup()
      const { router } = await crearCuenta(user, fakeTemporaryPasswordResponse({ account: fakeUserAccountDetail({ id: 42 }) }))
      const panel = screen.getByRole('region', { name: 'Cuenta creada' })
      expect(within(panel).getAllByRole('link').map((enlace) => enlace.textContent)).toEqual(['Ver la cuenta'])

      await user.click(within(panel).getByRole('link', { name: 'Ver la cuenta' }))
      expect(await screen.findByText('Detalle de la cuenta')).toBeInTheDocument()
      expect(router.state.location.pathname).toBe(userAccountDetailPath(42))
      expect(router.state.location.state).toBeNull()
      expect(router.state.historyAction).toBe('REPLACE')

      await router.navigate(-1)
      expect(await screen.findByText('Lista de usuarios')).toBeInTheDocument()
    })

    it('al ocultar la página la borra en ese mismo instante', async () => {
      const user = userEvent.setup()
      await crearCuenta(user)
      const texto = hidePageAndReadText()
      expect(texto).not.toContain(TEMPORARY_PASSWORD)
      expect(texto).not.toContain('AbCd')
      expect(screen.getByText('Ya no se muestra. Si no la copiaste, restablécela desde el detalle de la cuenta.')).toBeInTheDocument()
    })

    it('con el foco en "Ver la cuenta", el borrado no lo mueve', async () => {
      const user = userEvent.setup()
      await crearCuenta(user)
      const enlace = screen.getByRole('link', { name: 'Ver la cuenta' })
      enlace.focus()
      hidePageAndReadText()
      await screen.findByText('Ya no se muestra. Si no la copiaste, restablécela desde el detalle de la cuenta.')
      expect(enlace).toHaveFocus()
    })

    it('si el foco estaba en Copiar al borrarla, vuelve al título del panel', async () => {
      const user = userEvent.setup()
      await crearCuenta(user)
      screen.getByRole('button', { name: 'Copiar' }).focus()
      hidePageAndReadText()
      await waitFor(() => expect(screen.getByRole('heading', { name: 'Cuenta creada' })).toHaveFocus())
    })
  })

  describe('la temporal fuera de la pantalla', () => {
    it('con el panel abierto, solo está en la pantalla', async () => {
      const user = userEvent.setup()
      const { queryClient, router } = await crearCuenta(user)
      expect(screen.getByText('AbCd')).toBeInTheDocument()
      await waitFor(() => expect(queryClient.getMutationCache().getAll()).toEqual([]))
      for (const grupo of [TEMPORARY_PASSWORD, ...GRUPOS]) {
        expect(fueraDeLaPantalla(queryClient, router)).not.toContain(grupo)
      }
    })

    it('al aparecer el panel, la caché de mutaciones ya no la tiene', async () => {
      const user = userEvent.setup()
      server.use(createUserAccountCapture({}))
      const { queryClient } = renderPage()
      await abrir(user)
      let alAparecer: string | null = null
      const observador = new MutationObserver(() => {
        if (alAparecer === null && document.querySelector('h2[tabindex]')?.textContent === 'Cuenta creada') {
          alAparecer = JSON.stringify(queryClient.getMutationCache().getAll().map((mutation) => mutation.state))
        }
      })
      observador.observe(document.body, { childList: true, subtree: true, characterData: true })
      await user.click(confirmarEn())
      await screen.findByRole('heading', { name: 'Cuenta creada' })
      observador.disconnect()
      expect(alAparecer).not.toBeNull()
      expect(alAparecer).not.toContain(TEMPORARY_PASSWORD)
    })

    it('si se vuelve atrás con el alta en vuelo, la respuesta no queda en la caché de mutaciones', async () => {
      const user = userEvent.setup()
      const envios: { bodies?: string[] } = {}
      server.use(createUserAccountCapture(envios, fakeTemporaryPasswordResponse(), 150))
      const { queryClient, router } = renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      await router.navigate(-1)
      await screen.findByText('Lista de usuarios')
      const mutaciones = queryClient.getMutationCache()
      expect(mutaciones.getAll().map((mutation) => mutation.state.status)).toEqual(['pending'])
      await waitFor(() => expect(mutaciones.getAll().every((mutation) => mutation.state.status !== 'pending')).toBe(true))
      await waitFor(() => expect(mutaciones.getAll()).toEqual([]))
      expect(fueraDeLaPantalla(queryClient, router)).not.toContain(TEMPORARY_PASSWORD)
      expect(envios.bodies).toEqual([JSON.stringify({ workerId: JUAN.workerId })])
    })

    it('tras "Ver la cuenta", no queda en ningún lado', async () => {
      const user = userEvent.setup()
      const { queryClient, router } = await crearCuenta(user)
      await user.click(screen.getByRole('link', { name: 'Ver la cuenta' }))
      await screen.findByText('Detalle de la cuenta')
      expect(fueraDeLaPantalla(queryClient, router)).not.toContain(TEMPORARY_PASSWORD)
      expect(document.body.textContent).not.toContain(TEMPORARY_PASSWORD)
    })

    it('tras un rechazo, tampoco', async () => {
      const user = userEvent.setup()
      server.use(createUserAccountProblem('USR-003', 409))
      const { queryClient, router } = renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      expect(await within(dialogo()).findByRole('alert')).toHaveTextContent(/^Este trabajador ya tiene cuenta\./)
      expect(fueraDeLaPantalla(queryClient, router)).not.toContain(TEMPORARY_PASSWORD)
    })

    it('siembra el detalle con la cuenta, marca viejas la lista y la ficha del trabajador, y no vuelve a pedir los pendientes', async () => {
      const user = userEvent.setup()
      const cuenta = fakeUserAccountDetail({ id: 42, username: 'jperez1' })
      const { queryClient, pedidos } = await (async () => {
        const pedidos: { searches?: string[] } = {}
        server.use(accountCandidatesCapture(pedidos), createUserAccountCapture({}, fakeTemporaryPasswordResponse({ account: cuenta })))
        const vista = renderPage()
        vista.queryClient.setQueryData(userAccountKeys.list({ isActive: true }), [])
        vista.queryClient.setQueryData(workerKeys.detail(cuenta.worker.id), { id: cuenta.worker.id })
        return { ...vista, pedidos }
      })()
      await abrir(user)
      await user.click(confirmarEn())
      await screen.findByRole('heading', { name: 'Cuenta creada' })

      expect(queryClient.getQueryData(userAccountKeys.detail(42))).toEqual(cuenta)
      expect(queryClient.getQueryState(userAccountKeys.list({ isActive: true }))?.isInvalidated).toBe(true)
      expect(queryClient.getQueryState(workerKeys.detail(cuenta.worker.id))?.isInvalidated).toBe(true)
      await unRato()
      expect(pedidos.searches).toEqual([''])
    })
  })

  describe('decisiones de layout', () => {
    it('crear es verde: el botón de cada fila y el de confirmar', async () => {
      const user = userEvent.setup()
      renderPage()
      expect(await crearDe('Juan Pérez Quispe')).toHaveClass('bg-transition')
      await abrir(user)
      expect(confirmarEn()).toHaveClass('bg-transition')
    })

    it('la columna del botón no muestra su rótulo', async () => {
      renderPage()
      await crearDe('Juan Pérez Quispe')
      expect(screen.getByText('Acciones')).toHaveClass('sr-only')
    })

    it('ocupa el ancho del alta de trabajadores', () => {
      renderPage()
      const titulo = screen.getByRole('heading', { level: 1 })
      expect(titulo.closest('.max-w-\\[860px\\]')).not.toBeNull()
    })

    it('el botón de la fila va en la última columna, a la derecha', async () => {
      renderPage()
      const boton = await crearDe('Juan Pérez Quispe')
      const celda = boton.closest('td') as HTMLElement
      expect(celda.nextElementSibling).toBeNull()
      expect(celda.className).toContain('text-right')
    })

    it('la propuesta y los nombres viven en la confirmación, no en la página', async () => {
      renderPage()
      await crearDe('Juan Pérez Quispe')
      expect(screen.queryByLabelText('Nombre')).not.toBeInTheDocument()
      expect(screen.queryByText(/Se generará el usuario/)).not.toBeInTheDocument()
    })

    it('el panel sigue al encabezado', async () => {
      const user = userEvent.setup()
      await crearCuenta(user)
      const titulo = screen.getByRole('heading', { level: 1 })
      const panel = screen.getByRole('region', { name: 'Cuenta creada' })
      expect(titulo.compareDocumentPosition(panel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })
  })

  describe('accesibilidad', () => {
    it('sin violaciones con la lista', async () => {
      server.use(accountCandidatesCapture({}, [[JUAN, ANA]]))
      const { container } = renderPage()
      await crearDe('Ana Rojas')
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones mientras carga', async () => {
      server.use(accountCandidatesCapture({}, [[JUAN]], 1000))
      const { container } = renderPage()
      await screen.findByRole('status', { name: 'Cargando trabajadores' })
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones sin pendientes', async () => {
      server.use(accountCandidatesCapture({}, [[]]))
      const { container } = renderPage()
      await screen.findByText('No hay trabajadores pendientes de cuenta.')
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con la carga fallida', async () => {
      server.use(accountCandidatesError({}, 500, ''))
      const { container } = renderPage()
      await screen.findByRole('button', { name: 'Reintentar' })
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con la confirmación abierta', async () => {
      const user = userEvent.setup()
      const { container } = renderPage()
      await abrir(user)
      // El diálogo vive en un portal, fuera del contenedor.
      expect(await axe(dialogo())).toHaveNoViolations()
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con un rechazo en la confirmación', async () => {
      const user = userEvent.setup()
      server.use(createUserAccountProblem('USR-003', 409))
      renderPage()
      await abrir(user)
      await user.click(confirmarEn())
      await within(dialogo()).findByRole('alert')
      expect(await axe(dialogo())).toHaveNoViolations()
    })

    it('sin violaciones con el panel y con la temporal ya borrada', async () => {
      const user = userEvent.setup()
      const { container } = await crearCuenta(user)
      expect(await axe(container)).toHaveNoViolations()
      hidePageAndReadText()
      await screen.findByText('Ya no se muestra. Si no la copiaste, restablécela desde el detalle de la cuenta.')
      expect(await axe(container)).toHaveNoViolations()
    })
  })
})
