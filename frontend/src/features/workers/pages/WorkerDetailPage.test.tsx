import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { render, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { WorkerDetailPage } from './WorkerDetailPage'
import { WORKERS_BASE, workerDetailPath, workerEditPath } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import type { UserRole, WorkerDetailResponse } from '../../../api'
import { useService } from '../../operations/hooks/useService'
import { useServicesList } from '../../operations/hooks/useServicesList'
import { operationsKeys } from '../../operations/queryKeys'
import { EMPTY_SERVICE_FILTERS } from '../../operations/schemas/service-filters.schema'
import { pageOfServices } from '../../../test/mocks/handlers/operations'
import { warehouseKeys } from '../../warehouse/queryKeys'
import { workerKeys } from '../queryKeys'
import {
  changeWorkerStatusCapture,
  changeWorkerStatusEmpty,
  changeWorkerStatusNetworkError,
  changeWorkerStatusProblem,
  fakeDriverProfile,
  fakeWorkerDetail,
  getWorkerCapture,
  getWorkerEmpty,
  getWorkerError,
  getWorkerForbidden,
  getWorkerNotFound,
  getWorkerNotFoundWithoutBody,
  getWorkerOk,
  getWorkerSlow,
  listRolesSlow,
} from '../../../test/mocks/handlers/workers'

const API = 'http://localhost:8080/api/v1'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

afterEach(() => {
  vi.mocked(toast.error).mockClear()
  vi.mocked(toast.success).mockClear()
})

/** El id de la URL (42) no es el del fixture (1): un id escrito fijo no pasaría. */
function renderPage(id = 42, role: UserRole = 'operations_manager', client?: QueryClient) {
  // Sin espera entre intentos: el hook decide si reintenta, y los tests cuentan los pedidos.
  const queryClient = client ?? new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, {
    id: 1,
    username: `user-${role}`,
    fullName: `Usuario ${role}`,
    position: 'Cargo de prueba',
    role,
    isActive: true,
  })
  const router = createMemoryRouter(
    [
      { path: `${WORKERS_BASE}/:id`, element: <WorkerDetailPage /> },
      { path: WORKERS_BASE, element: <p>Pantalla de búsqueda</p> },
    ],
    { initialEntries: [workerDetailPath(id)] },
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

async function renderWorker(worker: WorkerDetailResponse) {
  server.use(getWorkerOk(worker))
  const vista = renderPage()
  await screen.findByRole('heading', { level: 1 })
  return vista
}

/** El par rótulo-valor: cada dato se afirma bajo su rótulo, nunca suelto. */
function par(rotulo: string, dentro: HTMLElement = document.body) {
  return within(dentro).getByText(rotulo, { selector: 'dt' }).parentElement as HTMLElement
}

function tarjetaLicencia() {
  return screen.getByRole('heading', { level: 2, name: 'Licencia de conducir' }).closest('section') as HTMLElement
}

describe('WorkerDetailPage', () => {
  it('muestra la carga mientras trae al trabajador', async () => {
    server.use(getWorkerSlow(fakeWorkerDetail(), 80))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Cargando trabajador')).toBeInTheDocument())
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('el encabezado es el nombre completo, con el cargo debajo', async () => {
    await renderWorker(fakeWorkerDetail())

    const titulo = screen.getByRole('heading', { level: 1, name: 'Ana Torres Ruiz' })
    // En el encabezado y no en cualquier lado: la tarjeta también dice el cargo.
    expect(titulo.closest('header')).toHaveTextContent('Jefe de Operaciones')
  })

  it('cada dato va bajo su rótulo, en este orden', async () => {
    await renderWorker(fakeWorkerDetail())

    const datos = screen.getByRole('heading', { level: 2, name: 'Datos del trabajador' }).closest('section') as HTMLElement
    const rotulos = within(datos).getAllByRole('term').map((dt) => dt.textContent)
    expect(rotulos).toEqual([
      'Nombre',
      'Apellido',
      'Tipo de documento',
      'Número de documento',
      'Cargo',
      'Fecha de ingreso',
      'Teléfono',
      'Tiene usuario',
      'Estado',
    ])
    const valores: Array<[string, string]> = [
      ['Nombre', 'Ana'],
      ['Apellido', 'Torres Ruiz'],
      ['Tipo de documento', 'Carné de extranjería'],
      ['Número de documento', '001234567'],
      ['Cargo', 'Jefe de Operaciones'],
      ['Fecha de ingreso', '01/03/2026'],
      ['Teléfono', '987654321'],
      ['Tiene usuario', 'Sí'],
      ['Estado', 'Activo'],
    ]
    for (const [rotulo, valor] of valores) {
      expect(within(par(rotulo)).getByRole('definition')).toHaveTextContent(new RegExp(`^${valor}$`))
    }
  })

  it('el tipo de documento es su nombre y el cargo su nombre visible, no los de sistema', async () => {
    await renderWorker(fakeWorkerDetail())

    expect(screen.queryByText('CE', { exact: true })).not.toBeInTheDocument()
    expect(screen.queryByText('operations_manager')).not.toBeInTheDocument()
  })

  /**
   * Es un día sin zona: leído como instante, en Lima retrocede al mes anterior. Se mide
   * en Lima porque en la zona de la suite (positiva) ese error no se ve.
   */
  it('la fecha de ingreso no se corre un día', async () => {
    const zona = process.env.TZ
    process.env.TZ = 'America/Lima'
    try {
      // Si el cambio de zona no toma (otro pool de la suite), el test tiene que caer y no pasar vacío.
      expect(new Date(2026, 2, 1).getTimezoneOffset()).toBe(300)
      await renderWorker(fakeWorkerDetail({ hireDate: '2026-03-01' }))

      expect(within(par('Fecha de ingreso')).getByRole('definition')).toHaveTextContent(/^01\/03\/2026$/)
    } finally {
      if (zona === undefined) delete process.env.TZ
      else process.env.TZ = zona
    }
  })

  it('sin teléfono muestra una raya', async () => {
    await renderWorker(fakeWorkerDetail({ phone: null }))

    expect(within(par('Teléfono')).getByRole('definition')).toHaveTextContent(/^—$/)
  })

  it('un trabajador inactivo se ve como tal', async () => {
    await renderWorker(fakeWorkerDetail({ isActive: false }))

    expect(within(par('Estado')).getByRole('definition')).toHaveTextContent(/^Inactivo$/)
  })

  it.each([
    [true, 'Sí'],
    [false, 'No'],
  ])('con hasUser %s dice %s', async (hasUser, texto) => {
    await renderWorker(fakeWorkerDetail({ hasUser }))

    expect(within(par('Tiene usuario')).getByRole('definition')).toHaveTextContent(new RegExp(`^${texto}$`))
  })

  describe('licencia de conducir', () => {
    it('sin ficha no hay tarjeta', async () => {
      await renderWorker(fakeWorkerDetail({ driver: null }))

      expect(screen.queryByRole('heading', { name: 'Licencia de conducir' })).not.toBeInTheDocument()
      expect(screen.queryByText('N.° de licencia')).not.toBeInTheDocument()
    })

    it('con ficha muestra licencia, categoría y disponibilidad, sin Deshabilitada', async () => {
      await renderWorker(fakeWorkerDetail({ driver: fakeDriverProfile() }))

      const tarjeta = tarjetaLicencia()
      expect(within(par('N.° de licencia', tarjeta)).getByRole('definition')).toHaveTextContent(/^Q12345678$/)
      expect(within(par('Categoría', tarjeta)).getByRole('definition')).toHaveTextContent(/^A-IIIb$/)
      expect(within(par('Disponibilidad', tarjeta)).getByRole('definition')).toHaveTextContent(/^Disponible$/)
      expect(within(tarjeta).queryByText('Deshabilitada')).not.toBeInTheDocument()
    })

    it.each([
      ['AVAILABLE', 'Disponible'],
      ['MAINTENANCE', 'No disponible'],
      ['NOT_AVAILABLE', 'No disponible'],
    ] as const)('la disponibilidad %s se lee %s', async (status, texto) => {
      await renderWorker(fakeWorkerDetail({ driver: fakeDriverProfile({ status }) }))

      expect(within(par('Disponibilidad')).getByRole('definition')).toHaveTextContent(new RegExp(`^${texto}$`))
    })

    it('sin categoría muestra una raya', async () => {
      await renderWorker(fakeWorkerDetail({ driver: fakeDriverProfile({ licenseCategory: null }) }))

      expect(within(par('Categoría')).getByRole('definition')).toHaveTextContent(/^—$/)
    })

    /** Trabajador activo con la ficha apagada: "Deshabilitada" es de la ficha, no del trabajador. */
    it('la ficha apagada dice Deshabilitada y sigue mostrando sus datos', async () => {
      await renderWorker(fakeWorkerDetail({ driver: fakeDriverProfile({ isActive: false }) }))

      const tarjeta = tarjetaLicencia()
      expect(within(tarjeta).getByText('Deshabilitada')).toBeInTheDocument()
      expect(within(par('N.° de licencia', tarjeta)).getByRole('definition')).toHaveTextContent(/^Q12345678$/)
      // Apagada, su disponibilidad no dice nada: raya, aunque el dato diga disponible.
      expect(within(par('Disponibilidad', tarjeta)).getByRole('definition')).toHaveTextContent(/^—$/)
      expect(screen.queryByText('Inactivo')).not.toBeInTheDocument()
    })

    it('un trabajador inactivo con la ficha encendida no dice Deshabilitada', async () => {
      await renderWorker(fakeWorkerDetail({ isActive: false, driver: fakeDriverProfile() }))

      expect(screen.getByText('Inactivo')).toBeInTheDocument()
      expect(screen.queryByText('Deshabilitada')).not.toBeInTheDocument()
    })
  })

  /**
   * La pantalla no mira el cargo: la tarjeta sale por tener ficha. Escoltas y ayudantes
   * también llevan licencia, y un ayudante puede no tenerla.
   */
  describe('licencia por cargo', () => {
    const cargo = (name: string, description: string, driverProfile: 'REQUIRED' | 'OPTIONAL') => ({
      name,
      description,
      level: 1,
      canLogin: false,
      driverProfile,
    })

    it('una escolta con ficha muestra su licencia', async () => {
      await renderWorker(
        fakeWorkerDetail({
          role: cargo('escort', 'Escolta', 'REQUIRED'),
          driver: fakeDriverProfile({ licenseNumber: 'E55501' }),
        }),
      )

      expect(within(par('N.° de licencia', tarjetaLicencia())).getByRole('definition')).toHaveTextContent(/^E55501$/)
    })

    it('un ayudante con ficha muestra su licencia', async () => {
      await renderWorker(
        fakeWorkerDetail({
          role: cargo('assistant', 'Ayudante', 'OPTIONAL'),
          driver: fakeDriverProfile({ licenseNumber: 'A77702' }),
        }),
      )

      expect(within(par('N.° de licencia', tarjetaLicencia())).getByRole('definition')).toHaveTextContent(/^A77702$/)
    })

    it('un ayudante sin ficha no tiene tarjeta', async () => {
      await renderWorker(fakeWorkerDetail({ role: cargo('assistant', 'Ayudante', 'OPTIONAL'), driver: null }))

      expect(screen.queryByRole('heading', { name: 'Licencia de conducir' })).not.toBeInTheDocument()
    })

    it('un conductor con la ficha apagada la muestra como Deshabilitada', async () => {
      await renderWorker(
        fakeWorkerDetail({
          role: cargo('driver', 'Conductor', 'REQUIRED'),
          driver: fakeDriverProfile({ isActive: false }),
        }),
      )

      expect(within(tarjetaLicencia()).getByText('Deshabilitada')).toBeInTheDocument()
    })
  })

  describe('pie de auditoría', () => {
    /** 02:00Z del 21/05 son las 21:00 del 20/05 en Lima; 04:30Z del 01/09, las 23:30 del 31/08. */
    it('dice quién creó y quién modificó, en día de Lima', async () => {
      await renderWorker(fakeWorkerDetail())

      expect(screen.getByText('Creado por María López Díaz el 20/05/2026')).toBeInTheDocument()
      expect(screen.getByText('Modificado por Pedro Salas Vega el 31/08/2026')).toBeInTheDocument()
    })

    it('sin creador dice solo la fecha del alta y deja intacta la modificación', async () => {
      await renderWorker(fakeWorkerDetail({ createdBy: null }))

      expect(screen.getByText('Creado el 20/05/2026')).toBeInTheDocument()
      expect(screen.getByText('Modificado por Pedro Salas Vega el 31/08/2026')).toBeInTheDocument()
      expect(screen.queryByText(/—/)).not.toBeInTheDocument()
    })

    /** El alta también firma al modificador, con el mismo instante: eso no es una modificación. */
    it('un alta sin editar no tiene línea de modificación', async () => {
      const alta = '2026-09-27T03:57:35.026225Z'
      await renderWorker(fakeWorkerDetail({ updatedAt: alta, createdAt: alta }))

      expect(screen.queryByText(/^Modificado/)).not.toBeInTheDocument()
      expect(screen.getByText('Creado por María López Díaz el 26/09/2026')).toBeInTheDocument()
    })

    /** Se comparan instantes y no textos: el mismo instante escrito distinto no es una modificación. */
    it('el mismo instante escrito de otra forma no tiene línea', async () => {
      await renderWorker(
        fakeWorkerDetail({ createdAt: '2026-09-27T03:57:35Z', updatedAt: '2026-09-27T03:57:35.000Z' }),
      )

      expect(screen.queryByText(/^Modificado/)).not.toBeInTheDocument()
    })

    /** Se comparan instantes y no días: dos cambios el mismo día son una modificación real. */
    it('una edición el mismo día del alta sí tiene línea', async () => {
      await renderWorker(
        fakeWorkerDetail({ createdAt: '2026-09-27T03:57:35Z', updatedAt: '2026-09-27T04:10:00Z' }),
      )

      expect(screen.getByText('Modificado por Pedro Salas Vega el 26/09/2026')).toBeInTheDocument()
    })

    it('sin modificador no hay línea de modificación y la creación queda intacta', async () => {
      await renderWorker(fakeWorkerDetail({ updatedBy: null }))

      expect(screen.queryByText(/^Modificado/)).not.toBeInTheDocument()
      expect(screen.getByText('Creado por María López Díaz el 20/05/2026')).toBeInTheDocument()
    })

    it('sin creador ni modificador, solo la fecha del alta', async () => {
      await renderWorker(fakeWorkerDetail({ createdBy: null, updatedBy: null }))

      const pie = screen.getByText('Creado el 20/05/2026').closest('footer') as HTMLElement
      expect(within(pie).getAllByText(/./).map((p) => p.textContent)).toEqual(['Creado el 20/05/2026'])
    })
  })

  describe('orden de las secciones', () => {
    it('los datos, después la licencia y al final el pie', async () => {
      await renderWorker(fakeWorkerDetail({ driver: fakeDriverProfile() }))

      const datos = screen.getByRole('heading', { level: 2, name: 'Datos del trabajador' })
      const conductor = screen.getByRole('heading', { level: 2, name: 'Licencia de conducir' })
      const pie = screen.getByText(/^Creado por/)
      expect(datos.compareDocumentPosition(conductor) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(conductor.compareDocumentPosition(pie) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('los datos van en una columna en móvil y dos desde el ancho chico', async () => {
      await renderWorker(fakeWorkerDetail())

      const lista = screen.getByText('Nombre', { selector: 'dt' }).closest('dl') as HTMLElement
      expect(lista.className).toContain('grid-cols-1')
      expect(lista.className).toContain('sm:grid-cols-2')
    })
  })

  it('pide al servidor el trabajador de la URL, una sola vez', async () => {
    const sink: { ids?: number[] } = {}
    server.use(getWorkerCapture(sink, fakeWorkerDetail({ id: 42 })))
    renderPage(42)

    await screen.findByRole('heading', { level: 1 })
    expect(sink.ids).toEqual([42])
  })

  it('guarda la respuesta bajo la clave del detalle, con el id como número', async () => {
    server.use(getWorkerOk(fakeWorkerDetail({ id: 42 })))
    const { queryClient } = renderPage(42)

    await screen.findByRole('heading', { level: 1 })
    expect(queryClient.getQueryData<WorkerDetailResponse>(['workers', 'detail', 42])?.id).toBe(42)
  })

  it('ofrece volver a la búsqueda', async () => {
    await renderWorker(fakeWorkerDetail())

    expect(screen.getByRole('link', { name: /volver a trabajadores/i })).toHaveAttribute('href', WORKERS_BASE)
  })

  /**
   * Sobre un cargo de su nivel, la ficha es de solo lectura. Se afirma el conjunto entero, cualquier
   * botón que se cuele, y recién con los cargos cargados: sin ellos Editar faltaría por otra razón.
   */
  it('sobre un cargo de su nivel es de solo lectura', async () => {
    server.use(getWorkerOk(fakeWorkerDetail({ driver: fakeDriverProfile() })))
    const { queryClient } = renderPage()
    await screen.findByRole('heading', { level: 1 })
    await waitFor(() => expect(queryClient.getQueryState(workerKeys.roles())?.status).toBe('success'))

    expect(screen.queryAllByRole('button')).toEqual([])
    expect(screen.getAllByRole('link').map((enlace) => enlace.textContent)).toEqual(['Volver a trabajadores'])
  })

  describe('editar', () => {
    const OPERADOR = { name: 'operator', description: 'Operador', level: 1, canLogin: false, driverProfile: 'NONE' } as const
    const ADMIN = { name: 'admin', description: 'Administrador', level: 4, canLogin: true, driverProfile: 'NONE' } as const

    /** Espera también los cargos: sin ellos no hay nivel y Editar no se mostraría por otra razón. */
    async function abrirComo(worker: WorkerDetailResponse, role: UserRole) {
      server.use(getWorkerOk(worker))
      const { queryClient } = renderPage(42, role)
      await screen.findByRole('heading', { level: 1 })
      await waitFor(() => expect(queryClient.getQueryState(workerKeys.roles())?.status).toBe('success'))
    }

    it('sobre un cargo de nivel menor ofrece Editar, hacia la edición de este trabajador', async () => {
      await abrirComo(fakeWorkerDetail({ id: 42, role: OPERADOR }), 'operations_manager')
      expect(await screen.findByRole('link', { name: 'Editar' })).toHaveAttribute('href', workerEditPath(42))
    })

    /** Sin los cargos no hay nivel: mientras llegan, Editar no se ofrece de más. */
    it('mientras llegan los cargos no ofrece Editar', async () => {
      server.use(getWorkerOk(fakeWorkerDetail({ id: 42 })), listRolesSlow(300))
      renderPage(42, 'operations_manager')
      await screen.findByRole('heading', { level: 1 })
      expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
    })

    it('un inactivo también ofrece Editar', async () => {
      await abrirComo(fakeWorkerDetail({ id: 42, role: OPERADOR, isActive: false }), 'finance_manager')
      expect(await screen.findByRole('link', { name: 'Editar' })).toBeInTheDocument()
    })

    it('el admin lo ve siempre, también sobre otro admin', async () => {
      await abrirComo(fakeWorkerDetail({ id: 42, role: ADMIN }), 'admin')
      expect(await screen.findByRole('link', { name: 'Editar' })).toBeInTheDocument()
    })

    it.each([['operations_manager'], ['finance_manager']] as const)(
      '%s no lo ve sobre un cargo de su nivel o mayor',
      async (role) => {
        await abrirComo(fakeWorkerDetail({ id: 42 }), role)
        expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
      },
    )
  })

  describe('estado', () => {
    const OPERADOR = { name: 'operator', description: 'Operador', level: 1, canLogin: false, driverProfile: 'NONE' } as const
    const CONDUCTOR = { name: 'driver', description: 'Conductor', level: 1, canLogin: false, driverProfile: 'REQUIRED' } as const
    const ALMACEN = { name: 'warehouse_keeper', description: 'Encargado de Almacén', level: 1, canLogin: true, driverProfile: 'NONE' } as const
    const ADMIN = { name: 'admin', description: 'Administrador', level: 4, canLogin: true, driverProfile: 'NONE' } as const

    /** Un activo de nivel menor, sin usuario ni licencia salvo que se diga. */
    function trabajador(overrides: Partial<WorkerDetailResponse> = {}) {
      return fakeWorkerDetail({ id: 42, firstName: 'Juan', lastName: 'Quispe', role: OPERADOR, driver: null, hasUser: false, ...overrides })
    }

    /** Espera también los cargos: sin ellos no hay nivel y las acciones no se mostrarían por otra razón. */
    async function abrirComo(worker: WorkerDetailResponse, role: UserRole = 'operations_manager', client?: QueryClient) {
      server.use(getWorkerOk(worker))
      const vista = renderPage(42, role, client)
      await screen.findByRole('heading', { level: 1 })
      await waitFor(() => expect(vista.queryClient.getQueryState(workerKeys.roles())?.status).toBe('success'))
      return vista
    }

    const dialogo = () => screen.getByRole('dialog')
    const estado = () => within(par('Estado')).getByText(/Activo|Inactivo/).textContent

    async function abrirDialogo(user: ReturnType<typeof userEvent.setup>, accion: 'Desactivar' | 'Reactivar') {
      await user.click(screen.getByRole('button', { name: accion }))
      return dialogo()
    }

    it('un activo ofrece Desactivar y un inactivo Reactivar, al lado de Editar', async () => {
      await abrirComo(trabajador())
      expect(screen.getByRole('button', { name: 'Desactivar' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Reactivar' })).not.toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Editar' })).toBeInTheDocument()
    })

    it('un inactivo ofrece Reactivar', async () => {
      await abrirComo(trabajador({ isActive: false }))
      expect(screen.getByRole('button', { name: 'Reactivar' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Desactivar' })).not.toBeInTheDocument()
    })

    it.each([['operations_manager'], ['finance_manager']] as const)(
      '%s no lo ve sobre un cargo de su nivel o mayor',
      async (role) => {
        await abrirComo(fakeWorkerDetail({ id: 42 }), role)
        expect(screen.queryByRole('button', { name: /Desactivar|Reactivar/ })).not.toBeInTheDocument()
      },
    )

    it('el admin lo ve siempre, también sobre otro admin', async () => {
      await abrirComo(trabajador({ role: ADMIN }), 'admin')
      expect(screen.getByRole('button', { name: 'Desactivar' })).toBeInTheDocument()
    })

    it('mientras llegan los cargos no lo ofrece', async () => {
      server.use(getWorkerOk(trabajador()), listRolesSlow(300))
      renderPage(42, 'operations_manager')
      await screen.findByRole('heading', { level: 1 })
      expect(screen.queryByRole('button', { name: 'Desactivar' })).not.toBeInTheDocument()
    })

    describe('lo que arrastra la baja', () => {
      const VIAJES = 'Si tiene viajes pendientes asignados, quedarán marcados para reasignar en Operaciones.'

      it.each([
        ['sin usuario ni licencia', trabajador(), null, false],
        ['con usuario', trabajador({ role: ALMACEN, hasUser: true }), 'Se desactiva también su usuario del sistema.', false],
        [
          'con licencia activa',
          trabajador({ role: CONDUCTOR, driver: fakeDriverProfile() }),
          'Su licencia de conducir queda deshabilitada.',
          true,
        ],
        [
          'con usuario y licencia activa',
          trabajador({ role: CONDUCTOR, hasUser: true, driver: fakeDriverProfile() }),
          'Se desactiva también su usuario del sistema, y su licencia de conducir queda deshabilitada.',
          true,
        ],
        [
          'con usuario y licencia apagada',
          trabajador({ role: CONDUCTOR, hasUser: true, driver: fakeDriverProfile({ isActive: false }) }),
          'Se desactiva también su usuario del sistema.',
          true,
        ],
      ] as const)('%s', async (_caso, worker, arrastra, viajes) => {
        const user = userEvent.setup()
        await abrirComo(worker)
        const panel = await abrirDialogo(user, 'Desactivar')

        expect(within(panel).getByRole('heading', { name: 'Desactivar trabajador' })).toBeInTheDocument()
        expect(within(panel).getByText('¿Desactivas a Juan Quispe?')).toBeInTheDocument()
        if (arrastra) expect(within(panel).getByText(arrastra)).toBeInTheDocument()
        else expect(within(panel).queryByText(/Se desactiva también|queda deshabilitada/)).not.toBeInTheDocument()
        if (viajes) expect(within(panel).getByText(VIAJES)).toBeInTheDocument()
        else expect(within(panel).queryByText(VIAJES)).not.toBeInTheDocument()
      })
    })

    it('reactivar con usuario aclara que su usuario sigue inactivo', async () => {
      const user = userEvent.setup()
      await abrirComo(trabajador({ role: ALMACEN, hasUser: true, isActive: false }))
      const panel = await abrirDialogo(user, 'Reactivar')
      expect(within(panel).getByText('¿Reactivas a Juan Quispe?')).toBeInTheDocument()
      expect(
        within(panel).getByText('Su usuario sigue inactivo: reactivar al trabajador no le devuelve el acceso.'),
      ).toBeInTheDocument()
    })

    it('reactivar sin usuario no habla de usuarios ni de licencias', async () => {
      const user = userEvent.setup()
      await abrirComo(trabajador({ isActive: false }))
      const panel = await abrirDialogo(user, 'Reactivar')
      expect(within(panel).queryByText(/usuario|licencia/)).not.toBeInTheDocument()
    })

    /** Como el backend: la licencia vuelve solo si estaba apagada y el cargo la lleva. */
    it.each([
      ['apagada y con un cargo que la lleva', CONDUCTOR, fakeDriverProfile({ isActive: false }), true],
      ['apagada y con un cargo que ya no la lleva', OPERADOR, fakeDriverProfile({ isActive: false }), false],
      ['ya encendida', CONDUCTOR, fakeDriverProfile({ isActive: true }), false],
      ['sin licencia', CONDUCTOR, null, false],
    ] as const)('reactivar con la licencia %s', async (_caso, role, driver, vuelve) => {
      const user = userEvent.setup()
      await abrirComo(trabajador({ role, driver, isActive: false }))
      const panel = await abrirDialogo(user, 'Reactivar')
      const aviso = within(panel).queryByText('Su licencia de conducir vuelve a quedar activa.')
      if (vuelve) expect(aviso).toBeInTheDocument()
      else expect(aviso).not.toBeInTheDocument()
    })

    it('Desactivar va con el relleno de peligro, como rechazar una cotización', async () => {
      await abrirComo(trabajador())
      const desactivar = screen.getByRole('button', { name: 'Desactivar' })
      expect(desactivar.className).toContain('bg-danger')
      expect(desactivar.className).not.toContain('bg-transition')
    })

    it('Reactivar va con el relleno de éxito, como aceptar una cotización', async () => {
      await abrirComo(trabajador({ isActive: false }))
      const reactivar = screen.getByRole('button', { name: 'Reactivar' })
      expect(reactivar.className).toContain('bg-transition')
      expect(reactivar.className).not.toContain('bg-danger')
    })

    it('confirmar la baja la envía una vez y la ficha muestra el estado nuevo', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(changeWorkerStatusCapture('deactivate', sink, trabajador({ isActive: false })))
      await abrirComo(trabajador())
      expect(estado()).toBe('Activo')
      await abrirDialogo(user, 'Desactivar')
      await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(sink.ids).toEqual([42])
      expect(estado()).toBe('Inactivo')
      expect(screen.getByRole('button', { name: 'Reactivar' })).toBeInTheDocument()
      expect(toast.success).toHaveBeenCalledWith('Se desactivó a Juan Quispe.')
    })

    it('confirmar la reactivación la envía a su operación', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(changeWorkerStatusCapture('reactivate', sink))
      await abrirComo(trabajador({ isActive: false }))
      await abrirDialogo(user, 'Reactivar')
      await user.click(within(dialogo()).getByRole('button', { name: 'Reactivar' }))

      await waitFor(() => expect(estado()).toBe('Activo'))
      expect(sink.ids).toEqual([42])
      expect(toast.success).toHaveBeenCalledWith('Se reactivó a Juan Quispe.')
    })

    it('al confirmar invalida las búsquedas y los viajes, y siembra la ficha con lo devuelto', async () => {
      const user = userEvent.setup()
      const devuelto = trabajador({ isActive: false, lastName: 'Quispe Devuelto' })
      server.use(changeWorkerStatusCapture('deactivate', {}, devuelto))
      const { queryClient } = await abrirComo(trabajador())
      queryClient.setQueryData(workerKeys.search({ q: 'juan', isActive: true }), [])
      queryClient.setQueryData(warehouseKeys.workerSearch('juan'), [])
      queryClient.setQueryData(operationsKeys.drivers(), [])
      queryClient.setQueryData(operationsKeys.serviceList({ page: 0 }), [])
      queryClient.setQueryData(operationsKeys.serviceDetail(9), {})
      queryClient.setQueryData(operationsKeys.serviceStats(), {})
      await abrirDialogo(user, 'Desactivar')
      await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))

      await waitFor(() => expect(queryClient.getQueryData(workerKeys.detail(42))).toEqual(devuelto))
      expect(queryClient.getQueryState(workerKeys.search({ q: 'juan', isActive: true }))?.isInvalidated).toBe(true)
      expect(queryClient.getQueryState(warehouseKeys.workerSearch('juan'))?.isInvalidated).toBe(true)
      expect(queryClient.getQueryState(operationsKeys.drivers())?.isInvalidated).toBe(true)
      // Una baja deja sus viajes para reasignar: la lista y el detalle tienen que pedirse de nuevo.
      expect(queryClient.getQueryState(operationsKeys.serviceList({ page: 0 }))?.isInvalidated).toBe(true)
      expect(queryClient.getQueryState(operationsKeys.serviceDetail(9))?.isInvalidated).toBe(true)
      // Y los conductores en ruta de los indicadores, que cuentan solo a los activos.
      expect(queryClient.getQueryState(operationsKeys.serviceStats())?.isInvalidated).toBe(true)
    })

    it('mientras envía, los dos botones quedan deshabilitados y apagados, y no se cierra', async () => {
      const user = userEvent.setup()
      server.use(changeWorkerStatusCapture('deactivate', {}, undefined, 300))
      await abrirComo(trabajador())
      await abrirDialogo(user, 'Desactivar')
      await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))

      const enviando = await within(dialogo()).findByRole('button', { name: /Desactivando…/ })
      const cancelar = within(dialogo()).getByRole('button', { name: 'Cancelar' })
      for (const boton of [enviando, cancelar]) {
        expect(boton).toBeDisabled()
        expect(boton.className).toContain('disabled:opacity-60')
      }
      await user.keyboard('{Escape}')
      expect(dialogo()).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })

    it('confirmar la baja es el botón de peligro', async () => {
      const user = userEvent.setup()
      await abrirComo(trabajador())
      await abrirDialogo(user, 'Desactivar')
      expect(within(dialogo()).getByRole('button', { name: 'Desactivar' }).className).toContain('bg-danger')
    })

    it('la reactivación confirma con el relleno de éxito, el mismo del botón que la abre', async () => {
      const user = userEvent.setup()
      await abrirComo(trabajador({ isActive: false }))
      await abrirDialogo(user, 'Reactivar')
      const confirmar = within(dialogo()).getByRole('button', { name: 'Reactivar' })
      expect(confirmar.className).toContain('bg-transition')
      expect(confirmar.className).not.toContain('bg-danger')
      expect(confirmar.className).not.toContain('bg-accent')
    })

    /**
     * Quien no ve operaciones no pide viajes: la invalidación solo vuelve a pedir lo que está a la
     * vista, tampoco los que dejó en caché una sesión anterior. El caso sin caché es el contraste:
     * la guarda la pone el caso con caché.
     */
    it.each([
      ['con viajes en caché de una sesión anterior', true],
      ['sin viajes en caché', false],
    ] as const)('finance_manager desactiva %s y no pide viajes', async (_caso, sembrar) => {
      const pedidos: string[] = []
      server.use(
        http.get(`${API}/services`, ({ request }) => {
          pedidos.push(request.url)
          return HttpResponse.json(pageOfServices([]))
        }),
        http.get(`${API}/services/:id`, ({ request }) => {
          pedidos.push(request.url)
          return HttpResponse.json({ id: 9 }, { headers: { ETag: '"x"' } })
        }),
      )
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
      if (sembrar) {
        const wrapper = ({ children }: { children: ReactNode }) => (
          <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
        )
        const lista = renderHook(() => useServicesList({ page: 0, size: 20, filters: EMPTY_SERVICE_FILTERS }), { wrapper })
        const detalle = renderHook(() => useService(9), { wrapper })
        await waitFor(() => expect(lista.result.current.isSuccess && detalle.result.current.isSuccess).toBe(true))
        lista.unmount()
        detalle.unmount()
        expect(pedidos).toHaveLength(2)
        pedidos.length = 0
      }
      server.use(changeWorkerStatusCapture('deactivate', {}, trabajador({ isActive: false })))
      const user = userEvent.setup()
      await abrirComo(trabajador(), 'finance_manager', queryClient)
      await abrirDialogo(user, 'Desactivar')
      await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

      // El cierre espera a las invalidaciones y estas a lo que pidan: no hace falta más margen.
      expect(pedidos).toEqual([])
      if (sembrar) {
        // La invalidación sí llegó a los viajes: quedan viejos, sin pedirse.
        expect(queryClient.getQueryState(operationsKeys.serviceList({ page: 0, size: 20 }))?.isInvalidated).toBe(true)
        expect(queryClient.getQueryState(operationsKeys.serviceDetail(9))?.isInvalidated).toBe(true)
      }
    })

    it('cancelar cierra sin enviar', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(changeWorkerStatusCapture('deactivate', sink))
      await abrirComo(trabajador())
      await abrirDialogo(user, 'Desactivar')
      await user.click(within(dialogo()).getByRole('button', { name: 'Cancelar' }))

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(sink.ids).toEqual([])
      expect(estado()).toBe('Activo')
    })

    describe('errores', () => {
      it.each([
        ['WRK-006', 403, 'No puedes modificar a un trabajador de tu nivel o superior.'],
        ['WRK-010', 403, 'No puedes desactivarte a ti mismo.'],
        ['COM-003', 403, 'Tu rol no tiene permisos para esta acción.'],
        ['WRK-013', 409, 'Otra operación estaba en curso y no se pudo cambiar el estado. Intenta de nuevo.'],
      ] as const)('%s se avisa en el diálogo y la ficha no cambia', async (code, status, texto) => {
        const user = userEvent.setup()
        server.use(changeWorkerStatusProblem('deactivate', code, status))
        await abrirComo(trabajador())
        await abrirDialogo(user, 'Desactivar')
        await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))

        expect(await within(dialogo()).findByRole('alert')).toHaveTextContent(texto)
        expect(estado()).toBe('Activo')
        expect(toast.success).not.toHaveBeenCalled()
      })

      it('tras un WRK-013, intentar de nuevo desde el mismo diálogo guarda', async () => {
        const user = userEvent.setup()
        server.use(changeWorkerStatusProblem('deactivate', 'WRK-013', 409, undefined, 100))
        await abrirComo(trabajador())
        await abrirDialogo(user, 'Desactivar')
        await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))
        // El navegador saca el foco de un botón que se deshabilita y jsdom no: se lo saca a mano,
        // fuera del diálogo, que es donde termina en el navegador.
        await within(dialogo()).findByRole('button', { name: /Desactivando…/ })
        screen.getByRole('link', { name: 'Volver a trabajadores' }).focus()
        expect(dialogo()).not.toContainElement(document.activeElement as HTMLElement)
        await within(dialogo()).findByRole('alert')
        // Con el error, el foco vuelve a confirmar y no queda fuera del diálogo.
        await waitFor(() => expect(within(dialogo()).getByRole('button', { name: 'Desactivar' })).toHaveFocus())

        // El reintento borra el aviso viejo mientras envía.
        server.use(changeWorkerStatusCapture('deactivate', {}, undefined, 300))
        await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))
        await within(dialogo()).findByRole('button', { name: /Desactivando…/ })
        expect(within(dialogo()).queryByRole('alert')).not.toBeInTheDocument()
        await waitFor(() => expect(estado()).toBe('Inactivo'))
      })

      it('un código sin texto propio muestra lo que dice el backend', async () => {
        const user = userEvent.setup()
        server.use(changeWorkerStatusProblem('reactivate', 'COM-999', 403, 'No tiene permisos para esta operación'))
        await abrirComo(trabajador({ isActive: false }))
        await abrirDialogo(user, 'Reactivar')
        await user.click(within(dialogo()).getByRole('button', { name: 'Reactivar' }))
        expect(await within(dialogo()).findByRole('alert')).toHaveTextContent('No tiene permisos para esta operación')
      })

      it.each([
        ['una caída de red', () => changeWorkerStatusNetworkError('deactivate')],
        ['una respuesta sin cuerpo', () => changeWorkerStatusEmpty('deactivate')],
      ])('%s se avisa con el texto genérico', async (_caso, handler) => {
        const user = userEvent.setup()
        server.use(handler())
        await abrirComo(trabajador())
        await abrirDialogo(user, 'Desactivar')
        await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))
        expect(await within(dialogo()).findByRole('alert')).toHaveTextContent('No se pudo cambiar el estado. Intenta de nuevo.')
        expect(estado()).toBe('Activo')
      })

      it('WRK-001 dice que no existe y vuelve a la búsqueda', async () => {
        const user = userEvent.setup()
        server.use(changeWorkerStatusProblem('deactivate', 'WRK-001', 404))
        const { router } = await abrirComo(trabajador())
        await abrirDialogo(user, 'Desactivar')
        await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))

        await waitFor(() => expect(router.state.location.pathname).toBe(WORKERS_BASE))
        expect(toast.error).toHaveBeenCalledWith('Este trabajador no existe.')
        expect(router.state.historyAction).toBe('REPLACE')
      })
    })

    it('sin violaciones con el diálogo abierto y con un error', async () => {
      const user = userEvent.setup()
      server.use(changeWorkerStatusProblem('deactivate', 'WRK-010', 403))
      await abrirComo(trabajador({ role: CONDUCTOR, hasUser: true, driver: fakeDriverProfile() }))
      await abrirDialogo(user, 'Desactivar')
      expect(await axe(dialogo())).toHaveNoViolations()

      await user.click(within(dialogo()).getByRole('button', { name: 'Desactivar' }))
      await within(dialogo()).findByRole('alert')
      expect(await axe(dialogo())).toHaveNoViolations()
    })
  })

  describe('errores', () => {
    it('un trabajador que no existe lo dice y ofrece la vuelta', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(getWorkerNotFound(sink))
      const { router } = renderPage()

      expect(await screen.findByText('Este trabajador no existe')).toBeInTheDocument()
      expect(screen.queryByText('Trabajador no encontrado')).not.toBeInTheDocument()
      // No se reintenta: el 404 aparece con un solo pedido.
      expect(sink.ids).toEqual([42])
      expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /volver a trabajadores/i }))
      await waitFor(() => expect(router.state.location.pathname).toBe(WORKERS_BASE))
    })

    it('un error muestra lo que dice el backend y deja reintentar', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(getWorkerError(500, sink))
      const { router } = renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent('Error interno del servidor')
      // Una caída puede ser pasajera: se reintenta una vez antes de mostrarla.
      expect(sink.ids).toEqual([42, 42])
      expect(screen.queryByText('Este trabajador no existe')).not.toBeInTheDocument()
      expect(router.state.location.pathname).toBe(workerDetailPath(42))

      server.use(getWorkerOk(fakeWorkerDetail({ firstName: 'Ana Recuperada' })))
      await user.click(screen.getByRole('button', { name: /reintentar/i }))
      expect(await screen.findByRole('heading', { level: 1, name: 'Ana Recuperada Torres Ruiz' })).toBeInTheDocument()
    })

    it('un 403 del backend no se lee como "no existe"', async () => {
      const sink: { ids?: number[] } = {}
      server.use(getWorkerForbidden(sink))
      renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent('No tiene permisos para acceder a este recurso')
      expect(screen.queryByText(/no existe/i)).not.toBeInTheDocument()
      // El rol no cambia al repetir: no se reintenta.
      expect(sink.ids).toEqual([42])
    })

    it('el 404 sin cuerpo también dice que no existe', async () => {
      server.use(getWorkerNotFoundWithoutBody())
      renderPage()

      expect(await screen.findByText('Este trabajador no existe')).toBeInTheDocument()
    })

    it('una respuesta vacía se muestra como error, no como una ficha en blanco', async () => {
      server.use(getWorkerEmpty())
      renderPage()

      expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo cargar el trabajador. Intenta de nuevo.')
      expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    })
  })

  describe('accesibilidad', () => {
    it('sin violaciones con la licencia apagada', async () => {
      const { container } = await renderWorker(
        fakeWorkerDetail({ driver: fakeDriverProfile({ isActive: false }) }),
      )
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones sin ficha y sin teléfono', async () => {
      const { container } = await renderWorker(fakeWorkerDetail({ driver: null, phone: null }))
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones en el "no existe"', async () => {
      server.use(getWorkerNotFound())
      const { container } = renderPage()
      await screen.findByText('Este trabajador no existe')
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones en el error', async () => {
      server.use(getWorkerError(500))
      const { container } = renderPage()
      await screen.findByRole('alert')
      expect(await axe(container)).toHaveNoViolations()
    })
  })
})
