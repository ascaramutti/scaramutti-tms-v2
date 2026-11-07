import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { UserAccountDetailPage } from './UserAccountDetailPage'
import { USERS_BASE, userAccountDetailPath, workerDetailPath } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import {
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
import type { UserAccountDetailResponse } from '../../../api'

const RAYA = '\u2014'

/** El id de la URL (42) no es el del fixture (12): un id escrito fijo no pasaría. */
function renderPage(id: number | string = 42) {
  // Frescura alta a propósito: el detalle tiene que pedirse de nuevo al volver aunque la general no lo haga.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0, staleTime: 30_000 } } })
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
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient, container: vista.container }
}

async function renderAccount(account: UserAccountDetailResponse) {
  server.use(getUserAccountOk(account))
  const vista = renderPage()
  await screen.findByRole('heading', { level: 1 })
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
      expect(screen.queryAllByRole('button')).toEqual([])
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
