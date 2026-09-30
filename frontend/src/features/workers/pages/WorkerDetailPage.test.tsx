import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { WorkerDetailPage } from './WorkerDetailPage'
import { WORKERS_BASE, workerDetailPath } from '../../../shared/paths'
import { server } from '../../../test/mocks/server'
import type { WorkerDetailResponse } from '../../../api'
import {
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
} from '../../../test/mocks/handlers/workers'

/** El id de la URL (42) no es el del fixture (1): un id escrito fijo no pasaría. */
function renderPage(id = 42) {
  // Sin espera entre intentos: el hook decide si reintenta, y los tests cuentan los pedidos.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const router = createMemoryRouter(
    [
      { path: `${WORKERS_BASE}/:id`, element: <WorkerDetailPage /> },
      { path: WORKERS_BASE, element: <p>Pantalla de búsqueda</p> },
    ],
    { initialEntries: [workerDetailPath(id)] },
  )
  const vista = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
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

  /** Se afirma el conjunto entero: cualquier botón que se cuele, se llame como se llame. */
  it('es de solo lectura', async () => {
    await renderWorker(fakeWorkerDetail({ driver: fakeDriverProfile() }))

    expect(screen.queryAllByRole('button')).toEqual([])
    expect(screen.getAllByRole('link').map((enlace) => enlace.textContent)).toEqual(['Volver a trabajadores'])
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
