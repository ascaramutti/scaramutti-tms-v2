import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { UserAccountDetailPage } from './UserAccountDetailPage'
import { USERS_BASE, userAccountDetailPath, workerDetailPath } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { SESSION_WINDOW_NOTICE } from '../../../shared/auth/sessionNotice'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { listRolesSlow } from '../../../test/mocks/handlers/workers'
import { workerKeys } from '../../workers/queryKeys'
import { userAccountKeys } from '../queryKeys'
import {
  changeAccountStatusCapture,
  changeAccountStatusEmpty,
  changeAccountStatusNetworkError,
  changeAccountStatusProblem,
  fakeUserAccountDetail,
  getUserAccountCapture,
  getUserAccountEmpty,
  getUserAccountError,
  getUserAccountForbidden,
  getUserAccountNotFound,
  getUserAccountNotFoundWithoutBody,
  getUserAccountOk,
  getUserAccountSlow,
} from '../../../test/mocks/handlers/users'
import type { SessionUser, UserAccountDetailResponse, UserRole } from '../../../api'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

afterEach(() => {
  vi.mocked(toast.error).mockClear()
  vi.mocked(toast.success).mockClear()
})

const RAYA = '\u2014'

/** La cuenta de la sesión es la 1 y su trabajador el 29119: son ids de tablas distintas. */
function sesion(role: UserRole = 'admin', overrides: Partial<SessionUser> = {}): SessionUser {
  return {
    id: 1,
    username: `user-${role}`,
    fullName: `Usuario ${role}`,
    position: 'Cargo de prueba',
    role,
    isActive: true,
    workerId: 29119,
    ...overrides,
  }
}

/** El id de la URL (42) no es el del fixture (12): un id escrito fijo no pasaría. */
function renderPage(id: number | string = 42, user: SessionUser = sesion()) {
  // Frescura alta a propósito: el detalle tiene que pedirse de nuevo al volver aunque la general no lo haga.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0, staleTime: 30_000 } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData<SessionUser>(currentUserQueryKey, user)
  const router = createMemoryRouter(
    [
      { path: `${USERS_BASE}/:id`, element: <UserAccountDetailPage /> },
      { path: USERS_BASE, element: <p>Lista de usuarios</p> },
      { path: '/otra', element: <Link to={userAccountDetailPath(id)}>Volver al detalle</Link> },
    ],
    { initialEntries: [userAccountDetailPath(id)] },
  )
  const vista = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { router, queryClient, container: vista.container, unmount: vista.unmount }
}

/** Espera también los cargos: sin ellos no hay nivel y las acciones no se mostrarían por otra razón. */
async function renderAccount(account: UserAccountDetailResponse, user = sesion(), sink?: { ids?: number[] }) {
  server.use(getUserAccountOk(account, sink))
  const vista = renderPage(42, user)
  await screen.findByRole('heading', { level: 1 })
  await waitFor(() => expect(vista.queryClient.getQueryState(workerKeys.roles())?.status).toBe('success'))
  return vista
}

function tarjeta(titulo: string) {
  return screen.getByRole('heading', { level: 2, name: titulo }).closest('section') as HTMLElement
}

/** El par rótulo-valor: cada dato se afirma bajo su rótulo, nunca suelto. */
function valor(rotulo: string, dentro: HTMLElement = document.body) {
  const par = within(dentro).getByText(rotulo, { selector: 'dt' }).parentElement as HTMLElement
  return within(par).getByRole('definition')
}

function pie() {
  return screen.getByText(/^Creada/).closest('footer') as HTMLElement
}

function siguiente(a: Node, b: Node) {
  return (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
}

describe('UserAccountDetailPage', () => {
  it('muestra la carga mientras trae la cuenta', async () => {
    server.use(getUserAccountSlow(fakeUserAccountDetail(), 80))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Cargando cuenta')).toBeInTheDocument())
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('el título es el usuario, con el nombre completo y el cargo debajo', async () => {
    await renderAccount(fakeUserAccountDetail())

    const titulo = screen.getByRole('heading', { level: 1, name: 'jperez' })
    expect(within(titulo.closest('header') as HTMLElement).getByText('Juan Pérez Quispe · Gerente de Operaciones')).toBeInTheDocument()
  })

  it('el encabezado usa el nombre completo tal como llega, aunque las partes den otro', async () => {
    await renderAccount(
      fakeUserAccountDetail({
        worker: { ...fakeUserAccountDetail().worker, fullName: 'Juan Carlos Pérez Quispe' },
      }),
    )

    expect(screen.getByText('Juan Carlos Pérez Quispe · Gerente de Operaciones')).toBeInTheDocument()
  })

  describe('Datos de la cuenta', () => {
    it('trae usuario, cargo y estado, en ese orden', async () => {
      await renderAccount(fakeUserAccountDetail())

      const datos = tarjeta('Datos de la cuenta')
      expect(within(datos).getAllByRole('term').map((dt) => dt.textContent)).toEqual(['Usuario', 'Cargo', 'Estado'])
      expect(valor('Usuario', datos)).toHaveTextContent(/^jperez$/)
      expect(valor('Cargo', datos)).toHaveTextContent(/^Gerente de Operaciones$/)
      expect(valor('Estado', datos)).toHaveTextContent(/^Activa$/)
    })

    it('el cargo es su nombre visible, no el de sistema', async () => {
      await renderAccount(fakeUserAccountDetail())

      expect(screen.queryByText(/operations_manager/)).not.toBeInTheDocument()
    })

    it('una cuenta inactiva se ve como tal', async () => {
      await renderAccount(fakeUserAccountDetail({ isActive: false }))

      expect(valor('Estado', tarjeta('Datos de la cuenta'))).toHaveTextContent(/^Inactiva$/)
    })

    it('el usuario va en monoespaciada, como en la lista', async () => {
      await renderAccount(fakeUserAccountDetail())

      expect(within(valor('Usuario', tarjeta('Datos de la cuenta'))).getByText('jperez')).toHaveClass('font-mono')
    })

    it('va en una columna en angosto y en dos desde el ancho chico', async () => {
      await renderAccount(fakeUserAccountDetail())

      const lista = within(tarjeta('Datos de la cuenta')).getByText('Usuario', { selector: 'dt' }).closest('dl') as HTMLElement
      expect(lista.className.split(/\s+/)).toEqual(expect.arrayContaining(['grid-cols-1', 'sm:grid-cols-2']))
    })

    it('no muestra bloqueo, ni corregir el usuario, ni desbloquear', async () => {
      await renderAccount(fakeUserAccountDetail())

      expect(screen.queryByText(/bloque/i)).not.toBeInTheDocument()
      expect(screen.queryByText(/corregir/i)).not.toBeInTheDocument()
      expect(screen.queryAllByRole('button').map((boton) => boton.textContent)).toEqual(['Desactivar'])
      expect(screen.getAllByRole('link').map((enlace) => enlace.textContent)).toEqual(['Volver a usuarios', 'Ver la ficha'])
    })
  })

  describe('Trabajador', () => {
    it('trae las tres partes del nombre y la ficha, en ese orden y sin cruzar los apellidos', async () => {
      await renderAccount(fakeUserAccountDetail())

      const trabajador = tarjeta('Trabajador')
      expect(within(trabajador).getAllByRole('term').map((dt) => dt.textContent)).toEqual([
        'Nombres',
        'Apellido paterno',
        'Apellido materno',
        'Ficha',
      ])
      const fila = within(trabajador).getByLabelText('Nombre')
      expect(valor('Nombres', fila)).toHaveTextContent(/^Juan$/)
      expect(valor('Apellido paterno', fila)).toHaveTextContent(/^Pérez$/)
      expect(valor('Apellido materno', fila)).toHaveTextContent(/^Quispe$/)
    })

    it('sin materno muestra la raya larga, nunca "null" ni el campo vacío', async () => {
      await renderAccount(
        fakeUserAccountDetail({
          worker: { ...fakeUserAccountDetail().worker, maternalLastName: null, fullName: 'Juan Pérez' },
        }),
      )

      const fila = screen.getByLabelText('Nombre')
      expect(valor('Apellido paterno', fila)).toHaveTextContent(/^Pérez$/)
      expect(valor('Apellido materno', fila).textContent).toBe(RAYA)
      expect(screen.queryByText(/null/)).not.toBeInTheDocument()
    })

    it('"Ver la ficha" lleva a la ficha del trabajador de la cuenta', async () => {
      await renderAccount(fakeUserAccountDetail())

      expect(screen.getByRole('link', { name: 'Ver la ficha' })).toHaveAttribute('href', workerDetailPath(31))
    })

    it('con el trabajador activo no dice "Dado de baja"', async () => {
      await renderAccount(fakeUserAccountDetail())

      expect(screen.queryByText('Dado de baja')).not.toBeInTheDocument()
    })

    it('con el trabajador de baja lo dice al lado de la ficha', async () => {
      await renderAccount(fakeUserAccountDetail({ worker: { ...fakeUserAccountDetail().worker, isActive: false } }))

      expect(within(valor('Ficha', tarjeta('Trabajador'))).getByText('Dado de baja')).toBeInTheDocument()
    })

    it('las tres partes van juntas en una fila de tres columnas desde el ancho chico, antes que la ficha', async () => {
      await renderAccount(fakeUserAccountDetail())

      const trabajador = tarjeta('Trabajador')
      const fila = within(trabajador).getByLabelText('Nombre')
      const ficha = within(trabajador).getByText('Ficha', { selector: 'dt' })
      expect(fila.className.split(/\s+/)).toEqual(expect.arrayContaining(['grid-cols-1', 'sm:grid-cols-3', 'mt-4']))
      expect(fila).not.toContainElement(ficha)
      expect(siguiente(fila, ficha)).toBe(true)
    })
  })

  describe('pie de auditoría', () => {
    it('dice quién la creó y quién la modificó, con fecha y hora de Lima', async () => {
      await renderAccount(fakeUserAccountDetail())

      expect(within(pie()).getAllByText(/./).map((p) => p.textContent)).toEqual([
        'Creada por Ana Quispe Rojas el 03/11/2026, 22:20',
        'Modificada por Luis Rojas Vega el 05/11/2026, 21:10',
      ])
    })

    it('sin creador lo dice y deja intacta la modificación', async () => {
      await renderAccount(fakeUserAccountDetail({ createdBy: null }))

      expect(within(pie()).getAllByText(/./).map((p) => p.textContent)).toEqual([
        'Creada el 03/11/2026, 22:20 · sin registro de quién la creó',
        'Modificada por Luis Rojas Vega el 05/11/2026, 21:10',
      ])
    })

    it('sin modificador y con otro instante lo dice', async () => {
      await renderAccount(fakeUserAccountDetail({ createdBy: null, updatedBy: null }))

      expect(within(pie()).getAllByText(/./).map((p) => p.textContent)).toEqual([
        'Creada el 03/11/2026, 22:20 · sin registro de quién la creó',
        'Modificada el 05/11/2026, 21:10 · sin registro de quién la modificó',
      ])
    })

    it('una cuenta sin cambios no tiene línea de modificación', async () => {
      const alta = '2026-11-04T03:20:00Z'
      await renderAccount(fakeUserAccountDetail({ createdBy: null, updatedAt: alta, updatedBy: null }))

      expect(within(pie()).getAllByText(/./).map((p) => p.textContent)).toEqual([
        'Creada el 03/11/2026, 22:20 · sin registro de quién la creó',
      ])
    })

    /** Se comparan instantes y no textos: el mismo instante escrito distinto no es una modificación. */
    it('el mismo instante escrito de otra forma no tiene línea', async () => {
      await renderAccount(fakeUserAccountDetail({ createdAt: '2026-11-04T03:20:00Z', updatedAt: '2026-11-04T03:20:00.000Z' }))

      expect(screen.queryByText(/^Modificada/)).not.toBeInTheDocument()
    })

    it('un cambio el mismo minuto del alta sí tiene línea', async () => {
      await renderAccount(fakeUserAccountDetail({ createdAt: '2026-11-04T03:20:00Z', updatedAt: '2026-11-04T03:20:30Z' }))

      expect(screen.getByText('Modificada por Luis Rojas Vega el 03/11/2026, 22:20')).toBeInTheDocument()
    })

    it('va en texto chico y apagado', async () => {
      await renderAccount(fakeUserAccountDetail())

      expect(pie().className.split(/\s+/)).toEqual(expect.arrayContaining(['text-xs', 'text-fg-muted']))
    })
  })

  describe('decisiones de layout', () => {
    it('el orden es volver, encabezado, la cuenta, el trabajador y el pie', async () => {
      await renderAccount(fakeUserAccountDetail())

      const orden = [
        screen.getByRole('link', { name: /volver a usuarios/i }),
        screen.getByRole('heading', { level: 1 }),
        screen.getByRole('heading', { level: 2, name: 'Datos de la cuenta' }),
        screen.getByRole('heading', { level: 2, name: 'Trabajador' }),
        screen.getByText(/^Creada/),
      ]
      for (let i = 1; i < orden.length; i++) {
        expect(siguiente(orden[i - 1], orden[i])).toBe(true)
      }
    })

    it('tiene el ancho de la ficha del trabajador', async () => {
      const { container } = await renderAccount(fakeUserAccountDetail())

      expect(container.firstElementChild).toHaveClass('mx-auto', 'max-w-[860px]')
    })
  })

  it('pide al servidor la cuenta de la URL, una sola vez', async () => {
    const sink: { ids?: number[] } = {}
    server.use(getUserAccountCapture(sink))
    renderPage(42)

    await screen.findByRole('heading', { level: 1 })
    expect(sink.ids).toEqual([42])
  })

  it('guarda la respuesta bajo la clave del detalle, con el id como número', async () => {
    server.use(getUserAccountCapture({}))
    const { queryClient } = renderPage('42')

    await screen.findByRole('heading', { level: 1 })
    expect(queryClient.getQueryData<UserAccountDetailResponse>(['users', 'detail', 42])?.id).toBe(42)
  })

  it('al volver a la pantalla la pide de nuevo', async () => {
    const user = userEvent.setup()
    const sink: { ids?: number[] } = {}
    server.use(getUserAccountCapture(sink))
    const { router } = renderPage(42)
    await screen.findByRole('heading', { level: 1 })

    await router.navigate('/otra')
    await user.click(await screen.findByRole('link', { name: 'Volver al detalle' }))
    await waitFor(() => expect(sink.ids).toEqual([42, 42]))
  })

  it('ofrece volver a la lista', async () => {
    await renderAccount(fakeUserAccountDetail())

    expect(screen.getByRole('link', { name: /volver a usuarios/i })).toHaveAttribute('href', USERS_BASE)
  })

  describe('errores', () => {
    it('una cuenta que no existe lo dice, sin reintentar, y ofrece la vuelta a la lista', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(getUserAccountNotFound(sink))
      const { router } = renderPage()

      expect(await screen.findByText('Esta cuenta no existe')).toBeInTheDocument()
      expect(screen.getByText('Revisa el enlace o búscala en la lista de usuarios.')).toBeInTheDocument()
      expect(screen.queryByText('Cuenta no encontrada')).not.toBeInTheDocument()
      expect(sink.ids).toEqual([42])
      expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /volver a usuarios/i }))
      await waitFor(() => expect(router.state.location.pathname).toBe(USERS_BASE))
    })

    it('el 404 sin cuerpo también dice que no existe', async () => {
      server.use(getUserAccountNotFoundWithoutBody())
      renderPage()

      expect(await screen.findByText('Esta cuenta no existe')).toBeInTheDocument()
    })

    it('el 403 del backend muestra su texto, no se lee como "no existe" y no se reintenta', async () => {
      const sink: { ids?: number[] } = {}
      server.use(getUserAccountForbidden(sink))
      renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent(/^No tiene permisos para acceder a este recurso$/)
      expect(screen.queryByText(/no existe/i)).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /reintentar/i })).not.toBeInTheDocument()
      expect(sink.ids).toEqual([42])
    })

    it('un 403 sin detalle dice el texto propio', async () => {
      server.use(getUserAccountForbidden(undefined, ''))
      renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent(/^No tienes permiso para ver esta cuenta\.$/)
      expect(screen.queryByRole('button', { name: /reintentar/i })).not.toBeInTheDocument()
    })

    it('un error muestra lo que dice el backend, se pide dos veces y deja reintentar', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(getUserAccountError(500, sink))
      renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent('Error interno del servidor')
      expect(sink.ids).toEqual([42, 42])
      expect(screen.queryByText('Esta cuenta no existe')).not.toBeInTheDocument()

      server.use(getUserAccountOk(fakeUserAccountDetail({ username: 'jperez2' })))
      await user.click(screen.getByRole('button', { name: /reintentar/i }))
      expect(await screen.findByRole('heading', { level: 1, name: 'jperez2' })).toBeInTheDocument()
    })

    it('un error sin detalle dice el texto propio', async () => {
      server.use(getUserAccountError(500, undefined, ''))
      renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo cargar la cuenta. Intenta de nuevo.')
    })

    it.each(['abc', '0', '1.5'])('un id que no es un entero positivo no se pide y no rompe la pantalla (%s)', async (id) => {
      const sink: { ids?: number[] } = {}
      server.use(getUserAccountCapture(sink))
      renderPage(id)

      expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo cargar la cuenta. Intenta de nuevo.')
      expect(sink.ids).toEqual([])
    })

    it('una respuesta vacía se muestra como error, no como una cuenta en blanco', async () => {
      server.use(getUserAccountEmpty())
      renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo cargar la cuenta. Intenta de nuevo.')
      expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    })
  })

  describe('estado', () => {
    const TRABAJADOR = fakeUserAccountDetail().worker
    const CARGO = fakeUserAccountDetail().role
    const MOTIVO_TRABAJADOR = 'Su trabajador está dado de baja. Primero reactiva al trabajador en Trabajadores.'
    const MOTIVO_CARGO = 'El cargo de su trabajador no inicia sesión. Primero cámbiale el cargo en Trabajadores.'

    /** Una ajena con el id de la URL: lo que devuelve una acción siembra el detalle que se está mirando. */
    function cuenta(overrides: Partial<UserAccountDetailResponse> = {}) {
      return fakeUserAccountDetail({ id: 42, ...overrides })
    }

    const dialogo = () => screen.getByRole('dialog')
    const estado = () => valor('Estado', tarjeta('Datos de la cuenta')).textContent
    const parrafos = () =>
      Array.from((within(dialogo()).getByText(/^¿/).parentElement as HTMLElement).children)
        .filter((hijo) => !hijo.querySelector('button'))
        .map((hijo) => hijo.textContent)

    async function confirmar(user: ReturnType<typeof userEvent.setup>, accion: 'Desactivar' | 'Reactivar') {
      await user.click(screen.getByRole('button', { name: accion }))
      await user.click(within(dialogo()).getByRole('button', { name: accion }))
    }

    it('una activa ofrece Desactivar, con el relleno de peligro', async () => {
      await renderAccount(cuenta())
      const desactivar = screen.getByRole('button', { name: 'Desactivar' })
      expect(desactivar.className).toContain('bg-danger')
      expect(desactivar.className).not.toContain('bg-transition')
      expect(screen.queryByRole('button', { name: 'Reactivar' })).not.toBeInTheDocument()
    })

    it('una inactiva ofrece Reactivar, con el relleno de éxito', async () => {
      await renderAccount(cuenta({ isActive: false }))
      const reactivar = screen.getByRole('button', { name: 'Reactivar' })
      expect(reactivar).toBeEnabled()
      expect(reactivar.className).toContain('bg-transition')
      expect(reactivar.className).not.toContain('bg-danger')
      expect(screen.queryByRole('button', { name: 'Desactivar' })).not.toBeInTheDocument()
    })

    it.each([
      ['activa', true],
      ['inactiva', false],
    ])('la propia %s no ofrece ninguno y dice que es tuya, sin enlaces', async (_estado, isActive) => {
      await renderAccount(cuenta({ id: 1, isActive }))
      expect(screen.queryAllByRole('button')).toEqual([])
      const aviso = screen.getByText(/^Es tu cuenta/)
      expect(aviso).toHaveTextContent(/^Es tu cuenta: no puedes desactivarla\.$/)
      expect(aviso).not.toHaveAttribute('role')
      expect(aviso.className).toContain('bg-accent-soft')
      expect(within(aviso).queryAllByRole('link')).toEqual([])
      expect(screen.queryByText(/de tu nivel o superior/)).not.toBeInTheDocument()
    })

    it('la propia de un gerente no dice que está fuera de rango', async () => {
      await renderAccount(cuenta({ id: 1 }), sesion('general_manager'))
      expect(screen.queryAllByRole('button')).toEqual([])
      expect(screen.getByText(/^Es tu cuenta/)).toBeInTheDocument()
      expect(screen.queryByText(/de tu nivel o superior/)).not.toBeInTheDocument()
    })

    it('fuera de rango no ofrece ninguno y dice que se ve sin modificarse', async () => {
      await renderAccount(cuenta(), sesion('general_manager'))
      expect(screen.queryAllByRole('button')).toEqual([])
      const aviso = screen.getByText(/de tu nivel o superior/)
      expect(aviso).toHaveTextContent(/^Es de un cargo de tu nivel o superior: puedes verla, no modificarla\.$/)
      expect(aviso).not.toHaveAttribute('role')
      expect(aviso.className).toContain('bg-accent-soft')
      expect(screen.queryByText(/^Es tu cuenta/)).not.toBeInTheDocument()
    })

    it('en rango no dice que es la propia ni que está fuera de rango', async () => {
      await renderAccount(cuenta())
      expect(screen.queryByText(/^Es tu cuenta/)).not.toBeInTheDocument()
      expect(screen.queryByText(/de tu nivel o superior/)).not.toBeInTheDocument()
    })

    it('mientras llegan los cargos no ofrece ninguno ni dice que está fuera de rango', async () => {
      server.use(getUserAccountOk(cuenta()), listRolesSlow(150))
      const { queryClient } = renderPage(42, sesion('general_manager'))
      await screen.findByRole('heading', { level: 1 })
      expect(queryClient.getQueryState(workerKeys.roles())?.status).toBe('pending')
      expect(screen.queryAllByRole('button')).toEqual([])
      expect(screen.queryByText(/de tu nivel o superior/)).not.toBeInTheDocument()
      expect(await screen.findByText(/de tu nivel o superior/)).toBeInTheDocument()
    })

    it.each([
      ['con el trabajador de baja', { worker: { ...TRABAJADOR, isActive: false } }, MOTIVO_TRABAJADOR],
      ['con el cargo sin sesión', { role: { ...CARGO, canLogin: false } }, MOTIVO_CARGO],
    ])('%s, Reactivar no se muestra y el motivo dice por qué, con el enlace a la ficha', async (_caso, overrides, motivo) => {
      await renderAccount(cuenta({ isActive: false, ...overrides }))
      expect(screen.queryAllByRole('button')).toEqual([])
      const aviso = screen.getByText(new RegExp(`^${motivo}`))
      expect(aviso).toHaveTextContent(new RegExp(`^${motivo} Ver la ficha de Juan Pérez Quispe$`))
      expect(aviso).not.toHaveAttribute('role')
      expect(aviso.className).toContain('bg-warning-soft')
      expect(aviso).toHaveClass('text-fg-body')
      expect(within(aviso).getByRole('link', { name: 'Ver la ficha de Juan Pérez Quispe' })).toHaveAttribute(
        'href',
        workerDetailPath(31),
      )
    })

    it('con la reactivación libre no dice ningún motivo', async () => {
      await renderAccount(cuenta({ isActive: false }))
      expect(screen.getByRole('button', { name: 'Reactivar' })).toBeInTheDocument()
      expect(screen.queryByText(/Primero/)).not.toBeInTheDocument()
    })

    it.each([
      ['del motivo', cuenta({ isActive: false, worker: { ...TRABAJADOR, isActive: false } }), sesion(), /^Su trabajador/],
      ['de la propia', cuenta({ id: 1 }), sesion(), /^Es tu cuenta/],
      ['de fuera de rango', cuenta(), sesion('general_manager'), /^Es de un cargo/],
    ] as const)('el aviso %s va entre el encabezado y los datos de la cuenta', async (_caso, account, user, texto) => {
      await renderAccount(account, user)
      const aviso = screen.getByText(texto)
      expect(siguiente(screen.getByRole('heading', { level: 1 }), aviso)).toBe(true)
      expect(siguiente(aviso, screen.getByRole('heading', { level: 2, name: 'Datos de la cuenta' }))).toBe(true)
    })

    it('la barra baja de línea y el botón de estado va último', async () => {
      await renderAccount(cuenta())
      const desactivar = screen.getByRole('button', { name: 'Desactivar' })
      const barra = desactivar.parentElement as HTMLElement
      expect(barra.className.split(/\s+/)).toContain('flex-wrap')
      expect(barra.lastElementChild).toBe(desactivar)
    })

    it('el diálogo de desactivar nombra la cuenta, dice que su trabajador sigue activo y avisa de la sesión abierta', async () => {
      const user = userEvent.setup()
      await renderAccount(cuenta())
      await user.click(screen.getByRole('button', { name: 'Desactivar' }))
      expect(screen.getByRole('dialog', { name: 'Desactivar cuenta' })).toBeInTheDocument()
      expect(parrafos()).toEqual([
        '¿Desactivas la cuenta jperez de Juan Pérez Quispe?',
        'Ya no podrá iniciar sesión. Su trabajador sigue activo.',
        SESSION_WINDOW_NOTICE,
      ])
      expect(within(dialogo()).getByText('jperez')).toHaveClass('font-mono')
    })

    it('con el trabajador de baja, el diálogo de desactivar no dice que sigue activo', async () => {
      const user = userEvent.setup()
      await renderAccount(cuenta({ worker: { ...TRABAJADOR, isActive: false } }))
      await user.click(screen.getByRole('button', { name: 'Desactivar' }))
      expect(parrafos()).toEqual([
        '¿Desactivas la cuenta jperez de Juan Pérez Quispe?',
        'Ya no podrá iniciar sesión.',
        SESSION_WINDOW_NOTICE,
      ])
    })

    it('el diálogo de reactivar dice que vuelve con su contraseña y que la sesión de antes no se renueva', async () => {
      const user = userEvent.setup()
      await renderAccount(cuenta({ isActive: false }))
      await user.click(screen.getByRole('button', { name: 'Reactivar' }))
      expect(screen.getByRole('dialog', { name: 'Reactivar cuenta' })).toBeInTheDocument()
      expect(parrafos()).toEqual([
        '¿Reactivas la cuenta jperez de Juan Pérez Quispe?',
        'Podrá volver a iniciar sesión con la contraseña que tenía.',
        'Si tenía una sesión abierta antes de la baja, no se renueva: tendrá que ingresar de nuevo.',
      ])
    })

    it.each([
      ['desactivar', cuenta(), 'Desactivar', 'bg-danger', 'bg-transition'],
      ['reactivar', cuenta({ isActive: false }), 'Reactivar', 'bg-transition', 'bg-danger'],
    ] as const)('el confirmar de %s repite el relleno del botón que lo abre', async (_caso, account, accion, si, no) => {
      const user = userEvent.setup()
      await renderAccount(account)
      await user.click(screen.getByRole('button', { name: accion }))
      const confirmar = within(dialogo()).getByRole('button', { name: accion })
      expect(confirmar.className).toContain(si)
      expect(confirmar.className).not.toContain(no)
    })

    it.each([
      ['desactivar', 'deactivate', cuenta(), 'Desactivar', /Desactivando…/],
      ['reactivar', 'reactivate', cuenta({ isActive: false }), 'Reactivar', /Reactivando…/],
    ] as const)('mientras envía, el confirmar de %s dice que envía', async (_caso, action, account, accion, enviando) => {
      const user = userEvent.setup()
      server.use(changeAccountStatusCapture(action, {}, undefined, 100))
      await renderAccount(account)
      await confirmar(user, accion)
      expect(await within(dialogo()).findByRole('button', { name: enviando })).toBeDisabled()
    })

    it('Cancelar cierra el diálogo sin enviar', async () => {
      const user = userEvent.setup()
      const envios: { ids?: number[] } = {}
      server.use(changeAccountStatusCapture('deactivate', envios))
      await renderAccount(cuenta())
      await user.click(screen.getByRole('button', { name: 'Desactivar' }))
      await user.click(within(dialogo()).getByRole('button', { name: 'Cancelar' }))

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(envios.ids).toEqual([])
    })

    it('el diálogo conserva la acción con la que se abrió aunque el detalle cambie, y la envía', async () => {
      const user = userEvent.setup()
      const envios: { ids?: number[] } = {}
      server.use(changeAccountStatusCapture('deactivate', envios))
      const { queryClient } = await renderAccount(cuenta())
      await user.click(screen.getByRole('button', { name: 'Desactivar' }))
      act(() => queryClient.setQueryData(userAccountKeys.detail(42), cuenta({ isActive: false })))

      await waitFor(() => expect(estado()).toBe('Inactiva'))
      expect(screen.getByRole('dialog', { name: 'Desactivar cuenta' })).toBeInTheDocument()
      await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))
      await waitFor(() => expect(envios.ids).toEqual([42]))
    })

    it('confirmar la baja la envía una vez, muestra el estado nuevo sin otro pedido y avisa', async () => {
      const user = userEvent.setup()
      const pedidos: { ids?: number[] } = {}
      const envios: { ids?: number[] } = {}
      server.use(changeAccountStatusCapture('deactivate', envios))
      await renderAccount(cuenta(), sesion(), pedidos)
      expect(estado()).toBe('Activa')
      await confirmar(user, 'Desactivar')

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(envios.ids).toEqual([42])
      expect(estado()).toBe('Inactiva')
      expect(screen.getByRole('button', { name: 'Reactivar' })).toBeInTheDocument()
      expect(pedidos.ids).toEqual([42])
      expect(toast.success).toHaveBeenCalledWith('Se desactivó la cuenta jperez.')
    })

    it('confirmar la reactivación la envía a su operación', async () => {
      const user = userEvent.setup()
      const envios: { ids?: number[] } = {}
      server.use(changeAccountStatusCapture('reactivate', envios))
      await renderAccount(cuenta({ isActive: false }))
      await confirmar(user, 'Reactivar')

      await waitFor(() => expect(estado()).toBe('Activa'))
      expect(envios.ids).toEqual([42])
      expect(toast.success).toHaveBeenCalledWith('Se reactivó la cuenta jperez.')
    })

    it('al confirmar siembra el detalle con lo devuelto, marca vieja la lista y no toca la ficha del trabajador', async () => {
      const user = userEvent.setup()
      const devuelto = cuenta({ isActive: false, username: 'jperez.devuelto' })
      server.use(changeAccountStatusCapture('deactivate', {}, devuelto))
      const { queryClient } = await renderAccount(cuenta())
      queryClient.setQueryData(userAccountKeys.list({ isActive: true }), [])
      queryClient.setQueryData(workerKeys.detail(31), {})
      await confirmar(user, 'Desactivar')

      await waitFor(() => expect(queryClient.getQueryData(userAccountKeys.detail(42))).toEqual(devuelto))
      expect(queryClient.getQueryState(userAccountKeys.list({ isActive: true }))?.isInvalidated).toBe(true)
      expect(queryClient.getQueryState(workerKeys.detail(31))?.isInvalidated).toBe(false)
    })

    describe('foco', () => {
      it('tras desactivar, el foco queda en Reactivar', async () => {
        const user = userEvent.setup()
        server.use(changeAccountStatusCapture('deactivate', {}))
        await renderAccount(cuenta())
        await confirmar(user, 'Desactivar')

        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
        expect(screen.getByRole('button', { name: 'Reactivar' })).toHaveFocus()
      })

      it.each([
        ['con el trabajador de baja', { worker: { ...TRABAJADOR, isActive: false } }],
        ['de un cargo sin sesión', { role: { ...CARGO, canLogin: false } }],
      ])('tras desactivar una cuenta %s, Reactivar no se muestra y el foco va a la vuelta a la lista', async (_caso, overrides) => {
        const user = userEvent.setup()
        server.use(changeAccountStatusCapture('deactivate', {}, cuenta({ isActive: false, ...overrides })))
        await renderAccount(cuenta(overrides))
        await confirmar(user, 'Desactivar')

        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
        expect(estado()).toBe('Inactiva')
        expect(screen.queryByRole('button', { name: 'Reactivar' })).not.toBeInTheDocument()
        expect(screen.getByRole('link', { name: 'Volver a usuarios' })).toHaveFocus()
      })
    })

    describe('errores', () => {
      it.each([
        ['USR-007', 'Desactivar', 'deactivate', 403, 'No puedes desactivar tu propia cuenta.'],
        ['USR-008', 'Desactivar', 'deactivate', 403, 'No puedes gestionar la cuenta de alguien de tu nivel o superior.'],
        ['COM-003', 'Desactivar', 'deactivate', 403, 'Tu rol no tiene permisos para esta acción.'],
        ['USR-009', 'Desactivar', 'deactivate', 409, 'Otra operación estaba en curso y no se pudo cambiar el estado. Intenta de nuevo.'],
        ['USR-005', 'Reactivar', 'reactivate', 409, MOTIVO_TRABAJADOR],
        ['USR-006', 'Reactivar', 'reactivate', 400, MOTIVO_CARGO],
      ] as const)('%s al confirmar %s se avisa en el diálogo y la cuenta no cambia', async (code, accion, action, status, texto) => {
        const user = userEvent.setup()
        server.use(changeAccountStatusProblem(action, code, status))
        await renderAccount(cuenta({ isActive: action === 'deactivate' }))
        const antes = estado()
        await confirmar(user, accion)

        expect((await within(dialogo()).findByRole('alert')).textContent).toBe(texto)
        expect(estado()).toBe(antes)
        expect(toast.success).not.toHaveBeenCalled()
      })

      it('tras un USR-009, intentar de nuevo desde el mismo diálogo guarda', async () => {
        const user = userEvent.setup()
        server.use(changeAccountStatusProblem('deactivate', 'USR-009', 409))
        await renderAccount(cuenta())
        await confirmar(user, 'Desactivar')
        await within(dialogo()).findByRole('alert')

        server.use(changeAccountStatusCapture('deactivate', {}, undefined, 100))
        await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))
        await within(dialogo()).findByRole('button', { name: /Desactivando…/ })
        expect(within(dialogo()).queryByRole('alert')).not.toBeInTheDocument()
        await waitFor(() => expect(estado()).toBe('Inactiva'))
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      })

      it('un código sin texto propio muestra lo que dice el backend', async () => {
        const user = userEvent.setup()
        server.use(changeAccountStatusProblem('reactivate', 'COM-999', 403, 'No tiene permisos para esta operación'))
        await renderAccount(cuenta({ isActive: false }))
        await confirmar(user, 'Reactivar')
        expect((await within(dialogo()).findByRole('alert')).textContent).toBe('No tiene permisos para esta operación')
      })

      it.each([
        ['un problema sin detalle', () => changeAccountStatusProblem('deactivate', 'COM-999', 500, '')],
        ['una caída de red', () => changeAccountStatusNetworkError('deactivate')],
        ['una respuesta sin cuerpo', () => changeAccountStatusEmpty('deactivate')],
      ])('%s se avisa con el texto genérico', async (_caso, handler) => {
        const user = userEvent.setup()
        server.use(handler())
        await renderAccount(cuenta())
        await confirmar(user, 'Desactivar')
        expect((await within(dialogo()).findByRole('alert')).textContent).toBe(
          'No se pudo cambiar el estado de la cuenta. Intenta de nuevo.',
        )
        expect(estado()).toBe('Activa')
      })

      it('USR-001 avisa que no existe y vuelve a la lista reemplazando la entrada', async () => {
        const user = userEvent.setup()
        server.use(changeAccountStatusProblem('deactivate', 'USR-001', 404))
        const { router } = await renderAccount(cuenta())
        await confirmar(user, 'Desactivar')

        await waitFor(() => expect(router.state.location.pathname).toBe(USERS_BASE))
        expect(toast.error).toHaveBeenCalledWith('Esta cuenta no existe.')
        expect(router.state.historyAction).toBe('REPLACE')
      })
    })
  })

  describe('accesibilidad', () => {
    it('sin violaciones con la cuenta inactiva y el trabajador de baja', async () => {
      const { container } = await renderAccount(
        fakeUserAccountDetail({ isActive: false, worker: { ...fakeUserAccountDetail().worker, isActive: false } }),
      )
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones sin autores y sin materno', async () => {
      const { container } = await renderAccount(
        fakeUserAccountDetail({
          createdBy: null,
          updatedBy: null,
          worker: { ...fakeUserAccountDetail().worker, maternalLastName: null, fullName: 'Juan Pérez' },
        }),
      )
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con los avisos de la propia y de fuera de rango', async () => {
      const propia = await renderAccount(fakeUserAccountDetail({ id: 1 }))
      expect(screen.getByText(/^Es tu cuenta/)).toBeInTheDocument()
      expect(await axe(propia.container)).toHaveNoViolations()
      propia.unmount()

      const ajena = await renderAccount(fakeUserAccountDetail(), sesion('general_manager'))
      expect(screen.getByText(/^Es de un cargo de tu nivel/)).toBeInTheDocument()
      expect(await axe(ajena.container)).toHaveNoViolations()
    })

    it('sin violaciones en el "no existe"', async () => {
      server.use(getUserAccountNotFound())
      const { container } = renderPage()
      await screen.findByText('Esta cuenta no existe')
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones en el error con Reintentar', async () => {
      server.use(getUserAccountError(500))
      const { container } = renderPage()
      await screen.findByRole('button', { name: /reintentar/i })
      expect(await axe(container)).toHaveNoViolations()
    })
  })
})
