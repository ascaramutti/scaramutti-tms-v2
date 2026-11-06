import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { delay, http, HttpResponse } from 'msw'
import { axe } from 'vitest-axe'
import { UsersListPage } from './UsersListPage'
import { USERS_BASE, userAccountDetailPath } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import { fakeUserAccount } from '../../../test/mocks/handlers/users'
import type { UserAccountSummaryResponse } from '../../../api'

const API = 'http://localhost:8080/api/v1'

const JPEREZ = fakeUserAccount()
const MRIOS = fakeUserAccount({
  id: 13,
  username: 'mrios',
  worker: { id: 40, fullName: 'María Ríos Vega', isActive: true },
  role: { name: 'sales', description: 'Ejecutivo de Ventas', level: 1, canLogin: true, driverProfile: 'NONE' },
  isActive: false,
})

/** Responde por filtro y guarda el `isActive` de cada pedido; `null` es "sin el parámetro". */
function usersByStatus(byStatus: Record<'true' | 'false' | 'none', UserAccountSummaryResponse[]>) {
  const pedidos: (string | null)[] = []
  server.use(
    http.get(`${API}/users`, ({ request }) => {
      const isActive = new URL(request.url).searchParams.get('isActive')
      pedidos.push(isActive)
      return HttpResponse.json(byStatus[(isActive ?? 'none') as 'true' | 'false' | 'none'])
    }),
  )
  return pedidos
}

function usersProblem(status: number, body: Record<string, unknown>) {
  const pedidos: (string | null)[] = []
  server.use(
    http.get(`${API}/users`, ({ request }) => {
      pedidos.push(new URL(request.url).searchParams.get('isActive'))
      return HttpResponse.json(body, { status, headers: { 'Content-Type': 'application/problem+json' } })
    }),
  )
  return pedidos
}

function newQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0, staleTime: 30_000 } } })
}

function renderPage(queryClient = newQueryClient()) {
  const router = createMemoryRouter(
    [
      { path: USERS_BASE, element: <UsersListPage /> },
      { path: `${USERS_BASE}/:id`, element: <p>Detalle de la cuenta</p> },
      { path: '/otra', element: <Link to={USERS_BASE}>Volver</Link> },
    ],
    { initialEntries: [USERS_BASE] },
  )
  const vista = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...vista, router }
}

function selectEstado() {
  return screen.getByLabelText(/^estado$/i)
}

function regionViva(container: HTMLElement) {
  return container.querySelector('p[aria-live="polite"]') as HTMLElement
}

function filas() {
  return screen.queryAllByRole('button', { name: /^ver la cuenta /i })
}

async function esperarFilas(n: number) {
  await waitFor(() => expect(filas()).toHaveLength(n))
}

describe('UsersListPage', () => {
  it('muestra el título de la pantalla', async () => {
    usersByStatus({ true: [JPEREZ], false: [], none: [] })
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /^usuarios$/i })).toBeInTheDocument()
    expect(
      screen.getByText('Las cuentas con las que se entra al sistema. Cada una es de un trabajador y tiene su cargo.'),
    ).toBeInTheDocument()
    await esperarFilas(1)
    expect(screen.getByRole('table', { name: 'Cuentas del sistema' })).toBeInTheDocument()
  })

  it('al abrir pide las activas sin escribir nada', async () => {
    const pedidos = usersByStatus({ true: [JPEREZ], false: [], none: [] })
    renderPage()
    await esperarFilas(1)
    expect(pedidos).toEqual(['true'])
  })

  it('el filtro abre en Activas y ofrece las tres opciones en orden', async () => {
    usersByStatus({ true: [JPEREZ], false: [], none: [] })
    renderPage()
    expect(selectEstado()).toHaveValue('active')
    expect(within(selectEstado()).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Activas',
      'Inactivas',
      'Todas',
    ])
    await esperarFilas(1)
  })

  it('Inactivas pide isActive=false y Todas no manda el parámetro', async () => {
    const user = userEvent.setup()
    const pedidos = usersByStatus({ true: [JPEREZ], false: [MRIOS], none: [JPEREZ, MRIOS] })
    renderPage()
    await esperarFilas(1)

    await user.selectOptions(selectEstado(), 'inactive')
    await waitFor(() => expect(screen.getByText('mrios')).toBeInTheDocument())
    await user.selectOptions(selectEstado(), 'all')
    await esperarFilas(2)

    expect(pedidos).toEqual(['true', 'false', null])
  })

  it('cada fila muestra el usuario, el trabajador, el cargo visible y el estado', async () => {
    usersByStatus({ true: [], false: [], none: [JPEREZ, MRIOS] })
    const user = userEvent.setup()
    renderPage()
    await user.selectOptions(selectEstado(), 'all')
    await esperarFilas(2)

    const fila = screen.getByText('mrios').closest('tr') as HTMLElement
    expect(within(fila).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'mrios',
      'María Ríos Vega',
      'Ejecutivo de Ventas',
      'Inactiva',
    ])
    expect(screen.queryByText('sales')).not.toBeInTheDocument()
  })

  it('las filas salen en el orden del servidor', async () => {
    const zeta = fakeUserAccount({ id: 1, username: 'zeta' })
    const alfa = fakeUserAccount({ id: 2, username: 'alfa' })
    usersByStatus({ true: [zeta, alfa], false: [], none: [] })
    renderPage()
    await esperarFilas(2)
    const usuarios = filas().map((row) => within(row).getAllByRole('cell')[0].textContent)
    expect(usuarios).toEqual(['zeta', 'alfa'])
  })

  it.each([
    ['active', 'Cambia el filtro de estado para ver las demás.'],
    ['inactive', 'Cambia el filtro de estado para ver las demás.'],
    ['all', null],
  ] as const)('sin cuentas en %s muestra el vacío de la historia', async (status, descripcion) => {
    const user = userEvent.setup()
    usersByStatus({ true: [], false: [], none: [] })
    renderPage()
    if (status !== 'active') await user.selectOptions(selectEstado(), status)
    expect(await screen.findByText('No hay cuentas con este estado')).toBeInTheDocument()
    if (descripcion) {
      expect(screen.getByText(descripcion)).toBeInTheDocument()
    } else {
      expect(screen.queryByText(/cambia el filtro/i)).not.toBeInTheDocument()
    }
  })

  it('mientras pide la lista muestra la carga', async () => {
    server.use(
      http.get(`${API}/users`, async () => {
        await delay('infinite')
        return HttpResponse.json([])
      }),
    )
    renderPage()
    expect(await screen.findByLabelText('Cargando cuentas')).toBeInTheDocument()
  })

  it('al cambiar de filtro conserva las filas anteriores hasta la respuesta', async () => {
    const user = userEvent.setup()
    server.use(
      http.get(`${API}/users`, async ({ request }) => {
        if (new URL(request.url).searchParams.get('isActive') === 'false') await delay('infinite')
        return HttpResponse.json([JPEREZ])
      }),
    )
    renderPage()
    await esperarFilas(1)
    await user.selectOptions(selectEstado(), 'inactive')
    await waitFor(() => expect(screen.getByRole('table').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true'))
    expect(screen.getByText('jperez')).toBeInTheDocument()
  })

  it('desde un vacío, cambiar de filtro muestra la carga y no el vacío', async () => {
    const user = userEvent.setup()
    server.use(
      http.get(`${API}/users`, async ({ request }) => {
        if (new URL(request.url).searchParams.get('isActive') === 'false') await delay('infinite')
        return HttpResponse.json([])
      }),
    )
    renderPage()
    await screen.findByText('No hay cuentas con este estado')
    await user.selectOptions(selectEstado(), 'inactive')
    expect(await screen.findByLabelText('Cargando cuentas')).toBeInTheDocument()
    expect(screen.queryByText('No hay cuentas con este estado')).not.toBeInTheDocument()
  })

  it('si falla otro filtro, no queda la lista anterior', async () => {
    const user = userEvent.setup()
    server.use(
      http.get(`${API}/users`, ({ request }) => {
        if (new URL(request.url).searchParams.get('isActive') === 'false') {
          return HttpResponse.json({ status: 500 }, { status: 500 })
        }
        return HttpResponse.json([JPEREZ])
      }),
    )
    renderPage()
    await esperarFilas(1)
    await user.selectOptions(selectEstado(), 'inactive')
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('jperez')).not.toBeInTheDocument()
  })

  it('un error con detalle muestra el del backend', async () => {
    usersProblem(500, { status: 500, code: 'COM-500', detail: 'El servidor no responde' })
    renderPage()
    expect(await screen.findByText('El servidor no responde')).toBeInTheDocument()
  })

  it('un 500 sin detalle muestra el texto propio, se pide dos veces y ofrece Reintentar', async () => {
    const pedidos = usersProblem(500, { status: 500 })
    renderPage()
    expect(await screen.findByText('No se pudieron cargar las cuentas. Intenta de nuevo.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reintentar/i })).toBeInTheDocument()
    expect(pedidos).toHaveLength(2)
  })

  it('Reintentar vuelve a pedir la lista y muestra las filas', async () => {
    const user = userEvent.setup()
    let pedidos = 0
    server.use(
      http.get(`${API}/users`, () => {
        pedidos += 1
        if (pedidos <= 2) return HttpResponse.json({ status: 500 }, { status: 500 })
        return HttpResponse.json([JPEREZ])
      }),
    )
    renderPage()
    await user.click(await screen.findByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText('jperez')).toBeInTheDocument()
    expect(pedidos).toBe(3)
  })

  it('un 403 se pide una sola vez, con su texto y sin Reintentar', async () => {
    const pedidos = usersProblem(403, { status: 403, code: 'COM-003' })
    renderPage()
    expect(await screen.findByText('No tienes permiso para ver las cuentas.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reintentar/i })).not.toBeInTheDocument()
    expect(pedidos).toHaveLength(1)
  })

  it('al volver a la pantalla la pide de nuevo', async () => {
    const user = userEvent.setup()
    const pedidos = usersByStatus({ true: [JPEREZ], false: [], none: [] })
    const { router } = renderPage()
    await esperarFilas(1)
    await router.navigate('/otra')
    await user.click(await screen.findByRole('link', { name: 'Volver' }))
    await waitFor(() => expect(pedidos).toEqual(['true', 'true']))
  })

  it('con filas no hay enlaces y las filas son los únicos botones', async () => {
    usersByStatus({ true: [JPEREZ, MRIOS], false: [], none: [] })
    renderPage()
    await esperarFilas(2)
    expect(screen.queryAllByRole('link')).toHaveLength(0)
    expect(screen.getAllByRole('button')).toEqual(filas())
  })

  describe('la fila abre el detalle', () => {
    it.each([
      ['jperez', 12],
      ['mrios', 13],
    ])('el clic en la fila de %s abre su cuenta', async (usuario, id) => {
      const user = userEvent.setup()
      usersByStatus({ true: [JPEREZ, MRIOS], false: [], none: [] })
      const { router } = renderPage()

      await user.click(await screen.findByRole('button', { name: new RegExp(`^ver la cuenta ${usuario} `, 'i') }))

      expect(router.state.location.pathname).toBe(userAccountDetailPath(id))
      expect(await screen.findByText('Detalle de la cuenta')).toBeInTheDocument()
    })

    it.each(['{Enter}', ' '])('la fila se abre con el teclado (%j)', async (tecla) => {
      const user = userEvent.setup()
      usersByStatus({ true: [JPEREZ, MRIOS], false: [], none: [] })
      const { router } = renderPage()

      const fila = await screen.findByRole('button', { name: /^ver la cuenta mrios /i })
      fila.focus()
      await user.keyboard(tecla)

      expect(router.state.location.pathname).toBe(userAccountDetailPath(13))
    })

    it('la fila llega con el tabulador desde el filtro', async () => {
      const user = userEvent.setup()
      usersByStatus({ true: [JPEREZ], false: [], none: [] })
      renderPage()

      const fila = await screen.findByRole('button', { name: /^ver la cuenta jperez /i })
      selectEstado().focus()
      await user.tab()

      expect(fila).toHaveFocus()
    })

    it('la fila se anuncia con usuario, trabajador, cargo y estado', async () => {
      usersByStatus({ true: [JPEREZ, MRIOS], false: [], none: [] })
      renderPage()

      expect(
        await screen.findByRole('button', { name: 'Ver la cuenta jperez de Juan Pérez Quispe, Gerente de Operaciones, activa' }),
      ).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: 'Ver la cuenta mrios de María Ríos Vega, Ejecutivo de Ventas, inactiva' }),
      ).toBeInTheDocument()
    })
  })

  describe('el conteo, que es la región viva', () => {
    it.each([
      ['active', [JPEREZ], '1 cuenta activa'],
      ['active', [JPEREZ, fakeUserAccount({ id: 2, username: 'b' }), fakeUserAccount({ id: 3, username: 'c' })], '3 cuentas activas'],
      ['active', [], 'Sin cuentas activas'],
      ['inactive', [MRIOS], '1 cuenta inactiva'],
      ['inactive', [MRIOS, fakeUserAccount({ id: 4, username: 'd', isActive: false })], '2 cuentas inactivas'],
      ['inactive', [], 'Sin cuentas inactivas'],
      ['all', [JPEREZ], '1 cuenta'],
      ['all', [JPEREZ, MRIOS], '2 cuentas'],
      ['all', [], 'Sin cuentas'],
    ] as const)('en %s con %j dice el texto exacto', async (status, accounts, texto) => {
      const user = userEvent.setup()
      usersByStatus({
        true: status === 'active' ? [...accounts] : [],
        false: status === 'inactive' ? [...accounts] : [],
        none: status === 'all' ? [...accounts] : [],
      })
      const { container } = renderPage()
      if (status !== 'active') await user.selectOptions(selectEstado(), status)
      await waitFor(() => expect(regionViva(container).textContent).toMatch(new RegExp(`^${texto}$`)))
    })

    it('está montada desde que abre la pantalla y es siempre el mismo nodo', async () => {
      const user = userEvent.setup()
      server.use(
        http.get(`${API}/users`, async () => {
          await delay(50)
          return HttpResponse.json([JPEREZ])
        }),
      )
      const { container } = renderPage()
      const region = regionViva(container)
      expect(region).not.toBeNull()
      expect(region.textContent).toBe('')
      await waitFor(() => expect(region.textContent).toBe('1 cuenta activa'))
      await user.selectOptions(selectEstado(), 'inactive')
      await waitFor(() => expect(regionViva(container)).toBe(region))
    })

    it('calla mientras las filas son las del filtro anterior', async () => {
      const user = userEvent.setup()
      server.use(
        http.get(`${API}/users`, async ({ request }) => {
          if (new URL(request.url).searchParams.get('isActive') === 'false') await delay('infinite')
          return HttpResponse.json([JPEREZ])
        }),
      )
      const { container } = renderPage()
      await waitFor(() => expect(regionViva(container).textContent).toBe('1 cuenta activa'))
      await user.selectOptions(selectEstado(), 'inactive')
      await waitFor(() => expect(regionViva(container).textContent).toBe(''))
      expect(screen.getByText('jperez')).toBeInTheDocument()
    })

    it('calla con un error', async () => {
      usersProblem(500, { status: 500 })
      const { container } = renderPage()
      await screen.findByRole('alert')
      expect(regionViva(container).textContent).toBe('')
    })
  })

  describe('decisiones de layout', () => {
    it('la pantalla tiene el ancho de Trabajadores', async () => {
      usersByStatus({ true: [JPEREZ], false: [], none: [] })
      const { container } = renderPage()
      await esperarFilas(1)
      expect(container.firstElementChild).toHaveClass('mx-auto', 'max-w-[1024px]')
    })

    it('el filtro va a la izquierda y el conteo a su derecha, alineado abajo', async () => {
      usersByStatus({ true: [JPEREZ], false: [], none: [] })
      const { container } = renderPage()
      await esperarFilas(1)
      const grilla = regionViva(container).parentElement as HTMLElement
      expect(grilla).toHaveClass('sm:grid-cols-[12rem_1fr]', 'sm:items-end')
      expect(grilla.contains(selectEstado())).toBe(true)
      expect(selectEstado().compareDocumentPosition(regionViva(container)) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('las columnas son Usuario, Trabajador, Cargo y Estado, en ese orden', async () => {
      usersByStatus({ true: [JPEREZ], false: [], none: [] })
      renderPage()
      await esperarFilas(1)
      expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
        'Usuario',
        'Trabajador',
        'Cargo',
        'Estado',
      ])
    })

    it('el usuario va en monoespaciada', async () => {
      usersByStatus({ true: [JPEREZ], false: [], none: [] })
      renderPage()
      expect(await screen.findByText('jperez')).toHaveClass('font-mono')
    })

    it('Activa va en el tono de éxito e Inactiva no', async () => {
      usersByStatus({ true: [], false: [], none: [JPEREZ, MRIOS] })
      const user = userEvent.setup()
      renderPage()
      await user.selectOptions(selectEstado(), 'all')
      await esperarFilas(2)
      expect(screen.getByText('Activa').className).toContain('bg-success-soft')
      expect(screen.getByText('Inactiva').className).not.toContain('bg-success-soft')
    })

    it('va en una sola página, sin pie', async () => {
      usersByStatus({ true: [JPEREZ], false: [], none: [] })
      renderPage()
      await esperarFilas(1)
      expect(screen.queryByText(/mostrando/i)).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /página siguiente/i })).not.toBeInTheDocument()
    })
  })

  describe('accesibilidad', () => {
    it('con filas, sin violaciones', async () => {
      usersByStatus({ true: [], false: [], none: [JPEREZ, MRIOS] })
      const user = userEvent.setup()
      const { container } = renderPage()
      await user.selectOptions(selectEstado(), 'all')
      await esperarFilas(2)
      expect(await axe(container)).toHaveNoViolations()
    })

    it('con el vacío, sin violaciones', async () => {
      usersByStatus({ true: [], false: [], none: [] })
      const { container } = renderPage()
      await screen.findByText('No hay cuentas con este estado')
      expect(await axe(container)).toHaveNoViolations()
    })

    it('con el error, sin violaciones', async () => {
      usersProblem(500, { status: 500 })
      const { container } = renderPage()
      await screen.findByRole('alert')
      expect(await axe(container)).toHaveNoViolations()
    })
  })
})
