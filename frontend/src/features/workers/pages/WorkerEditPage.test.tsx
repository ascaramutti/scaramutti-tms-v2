import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { WorkerEditPage } from './WorkerEditPage'
import { WORKERS_BASE, workerDetailPath, workerEditPath } from '../../../shared/paths'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { server } from '../../../test/mocks/server'
import type { UserRole, WorkerDetailResponse, WorkerUpdateRequest } from '../../../api'
import { operationsKeys } from '../../operations/queryKeys'
import { workerKeys } from '../queryKeys'
import {
  CE,
  DNI,
  ROLES,
  fakeDriverProfile,
  fakeWorkerDetail,
  getWorkerError,
  getWorkerNotFound,
  getWorkerOk,
  listDocumentTypesError,
  listDocumentTypesOk,
  listDocumentTypesSequence,
  listRolesError,
  listRolesOk,
  listRolesSequence,
  listRolesSlow,
  updateWorkerCapture,
  updateWorkerProblem,
  updateWorkerEmpty,
  updateWorkerNetworkError,
  updateWorkerValidation,
} from '../../../test/mocks/handlers/workers'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const ID = 42

function rol(name: string) {
  return ROLES.find((candidate) => candidate.name === name)!
}

/** Un conductor con todo cargado: cada dato distinto, para que uno en lugar de otro se note. */
function trabajador(overrides: Partial<WorkerDetailResponse> = {}): WorkerDetailResponse {
  return fakeWorkerDetail({
    id: ID,
    firstName: 'Juan',
    lastName: 'Pérez Huamán',
    documentType: CE,
    documentNumber: 'X1234567',
    phone: '987654321',
    role: rol('driver'),
    hireDate: '2024-03-01',
    hasUser: false,
    driver: fakeDriverProfile({ licenseNumber: 'Q11112222', licenseCategory: 'A-IIb', status: 'MAINTENANCE' }),
    ...overrides,
  })
}

function renderPage(worker: WorkerDetailResponse | null, role: UserRole = 'general_manager') {
  // Sin espera entre intentos: el hook decide si reintenta, y los tests cuentan los pedidos.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, retryDelay: 0 }, mutations: { retry: false } },
  })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, {
    id: 1,
    username: `user-${role}`,
    fullName: `Usuario ${role}`,
    position: 'Cargo de prueba',
    role,
    isActive: true,
  })
  queryClient.setQueryData(workerKeys.search({ q: 'juan', isActive: true }), [])
  queryClient.setQueryData(operationsKeys.drivers(), [])
  queryClient.setQueryData(operationsKeys.serviceList({ page: 0 }), [])
  queryClient.setQueryData(operationsKeys.serviceDetail(9), {})
  if (worker) server.use(getWorkerOk(worker))
  const router = createMemoryRouter(
    [
      { path: `${WORKERS_BASE}/:id/editar`, element: <WorkerEditPage /> },
      { path: `${WORKERS_BASE}/:id`, element: <p>Pantalla de ficha</p> },
      { path: WORKERS_BASE, element: <p>Pantalla de búsqueda</p> },
    ],
    { initialEntries: [workerEditPath(ID)] },
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

const listo = () => screen.findByLabelText('Nombre')
const cargo = () => screen.getByLabelText('Cargo')
const guardar = () => screen.getByRole('button', { name: 'Guardar' })

/** Sin un cambio real Guardar no se habilita: los casos que no miden eso cambian el apellido. */
async function cambiarApellido(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Apellido'), 'a')
}

function capturar() {
  const sink: { ids?: number[]; bodies?: WorkerUpdateRequest[] } = {}
  server.use(updateWorkerCapture(sink))
  return sink
}

afterEach(() => {
  vi.mocked(toast.error).mockClear()
  vi.mocked(toast.success).mockClear()
})

describe('WorkerEditPage', () => {
  describe('apertura', () => {
    it('abre con lo guardado, licencia incluida, y muestra estado y auditoría sin campo', async () => {
      renderPage(trabajador())
      await listo()

      expect(screen.getByRole('heading', { level: 1, name: 'Editar trabajador' })).toBeInTheDocument()
      expect(screen.getByText('Juan Pérez Huamán')).toBeInTheDocument()
      expect(screen.getByLabelText('Nombre')).toHaveValue('Juan')
      expect(screen.getByLabelText('Apellido')).toHaveValue('Pérez Huamán')
      expect(screen.getByLabelText('Tipo de documento')).toHaveValue(String(CE.id))
      expect(screen.getByLabelText('Número de documento')).toHaveValue('X1234567')
      expect(screen.getByLabelText('Teléfono (opcional)')).toHaveValue('987654321')
      expect(cargo()).toHaveValue('driver')
      expect(screen.getByLabelText('Fecha de ingreso')).toHaveValue('2024-03-01')
      expect(screen.getByLabelText('N.° de licencia')).toHaveValue('Q11112222')
      expect(screen.getByLabelText('Categoría (opcional)')).toHaveValue('A-IIb')
      expect(screen.getByText('Activo')).toBeInTheDocument()
      expect(screen.queryByRole('checkbox', { name: /activo/i })).not.toBeInTheDocument()
      expect(screen.getByText(/María López Díaz/)).toBeInTheDocument()
      expect(screen.queryByLabelText('Motivo del cambio')).not.toBeInTheDocument()
    })

    it('un inactivo también se edita, y se ve como tal', async () => {
      const user = userEvent.setup()
      renderPage(trabajador({ isActive: false }))
      await listo()
      expect(screen.getByText('Inactivo')).toBeInTheDocument()
      await cambiarApellido(user)
      expect(guardar()).toBeEnabled()
    })

    it('un trabajador que no existe lo dice y ofrece la vuelta a la búsqueda', async () => {
      server.use(getWorkerNotFound())
      renderPage(null)
      expect(await screen.findByText('Este trabajador no existe')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Volver a trabajadores' })).toBeInTheDocument()
      expect(screen.queryByLabelText('Nombre')).not.toBeInTheDocument()
      // Sin trabajador no hay ficha: el enlace de arriba también lleva a la búsqueda.
      expect(screen.getByRole('link', { name: 'Volver a trabajadores' })).toHaveAttribute('href', WORKERS_BASE)
      expect(screen.queryByRole('link', { name: 'Volver a la ficha' })).not.toBeInTheDocument()
    })

    it('ofrece volver a la ficha', async () => {
      renderPage(trabajador())
      await listo()
      expect(screen.getByRole('link', { name: 'Volver a la ficha' })).toHaveAttribute('href', workerDetailPath(ID))
    })
  })

  describe('carga', () => {
    it('si falla el trabajador, muestra lo que dice el backend y Reintentar lo vuelve a pedir', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[] } = {}
      server.use(getWorkerError(500, sink))
      renderPage(null)
      expect(await screen.findByRole('alert')).toHaveTextContent('Error interno del servidor')
      // El hook reintenta una vez un 500 antes de mostrar el error.
      expect(sink.ids).toEqual([ID, ID])

      // El formulario solo aparece si Reintentar vuelve a pedir al trabajador (ahora responde bien).
      server.use(getWorkerOk(trabajador()))
      await user.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await listo()).toBeInTheDocument()
    })

    it('si fallan los cargos, Reintentar los vuelve a pedir', async () => {
      const user = userEvent.setup()
      server.use(listRolesError(500))
      renderPage(trabajador())
      expect(await screen.findByRole('alert')).toHaveTextContent('Error interno del servidor')

      server.use(listRolesOk(ROLES))
      await user.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await listo()).toBeInTheDocument()
    })

    it('si fallan los tipos de documento, Reintentar los vuelve a pedir', async () => {
      const user = userEvent.setup()
      server.use(listDocumentTypesError(500))
      renderPage(trabajador())
      expect(await screen.findByRole('alert')).toHaveTextContent('Error interno del servidor')

      server.use(listDocumentTypesOk([DNI, CE]))
      await user.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await listo()).toBeInTheDocument()
    })

    it('mientras llegan los cargos muestra la carga y no el formulario', async () => {
      server.use(listRolesSlow(500))
      const { queryClient } = renderPage(trabajador())
      // Con el trabajador y los tipos ya llegados, lo único que falta son los cargos.
      await waitFor(() => {
        expect(queryClient.getQueryState(workerKeys.detail(ID))?.status).toBe('success')
        expect(queryClient.getQueryState(workerKeys.documentTypes())?.status).toBe('success')
      })
      expect(screen.getByLabelText('Cargando trabajador')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Nombre')).not.toBeInTheDocument()
      expect(await listo()).toBeInTheDocument()
    })

    it('desde el "no existe", el botón vuelve a la búsqueda', async () => {
      const user = userEvent.setup()
      server.use(getWorkerNotFound())
      const { router } = renderPage(null)
      await user.click(await screen.findByRole('button', { name: 'Volver a trabajadores' }))
      expect(router.state.location.pathname).toBe(WORKERS_BASE)
    })
  })

  describe('permisos', () => {
    it('sobre un cargo de su nivel no ofrece el formulario', async () => {
      renderPage(trabajador({ role: rol('operations_manager'), driver: null }), 'general_manager')
      expect(await screen.findByText('No puedes modificar a un trabajador de tu nivel o superior.')).toBeInTheDocument()
      expect(screen.queryByLabelText('Nombre')).not.toBeInTheDocument()
    })

    it('el admin edita a cualquiera, también a otro admin', async () => {
      renderPage(trabajador({ role: rol('admin'), driver: null, hasUser: true }), 'admin')
      expect(await listo()).toBeInTheDocument()
      expect(cargo()).toHaveValue('admin')
    })

    it('a un trabajador con usuario no le ofrece cargos que no inician sesión', async () => {
      renderPage(trabajador({ role: rol('sales'), driver: null, hasUser: true }), 'general_manager')
      await listo()
      const opciones = within(cargo())
        .getAllByRole('option')
        .map((option) => option.textContent)
      expect(opciones).toContain('Ejecutivo de Ventas')
      expect(opciones).toContain('Encargado de Almacén')
      expect(opciones).not.toContain('Conductor')
      expect(opciones).not.toContain('Operador')
    })
  })

  describe('lo que viaja', () => {
    /** Es un reemplazo: lo que no viaja se borra. Cambiar solo el apellido reenvía todo lo demás. */
    it('editar solo el apellido reenvía el teléfono y la licencia tal como estaban', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador())
      await listo()
      await user.clear(screen.getByLabelText('Apellido'))
      await user.type(screen.getByLabelText('Apellido'), 'Quispe')
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.ids).toEqual([ID])
      expect(sink.bodies?.[0]).toEqual({
        firstName: 'Juan',
        lastName: 'Quispe',
        documentTypeId: CE.id,
        documentNumber: 'X1234567',
        phone: '987654321',
        role: 'driver',
        hireDate: '2024-03-01',
        // La disponibilidad no viaja: ausente, el backend conserva la guardada (MAINTENANCE).
        driver: { licenseNumber: 'Q11112222', licenseCategory: 'A-IIb' },
        reason: null,
      })
      expect(sink.bodies?.[0].driver).not.toHaveProperty('status')
    })

    it('un cambio guarda, avisa y vuelve a la ficha', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      const { router } = renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(router.state.location.pathname).toBe(workerDetailPath(ID)))
      expect(sink.bodies).toHaveLength(1)
      expect(toast.success).toHaveBeenCalledWith('Se guardaron los cambios de Juan Pérez Huamána.')
    })

    it('una licencia apagada aparece precargada con un cargo que la exige, y viaja', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador({ driver: fakeDriverProfile({ licenseNumber: 'Q99990000', isActive: false }) }))
      await listo()
      expect(screen.getByLabelText('N.° de licencia')).toHaveValue('Q99990000')
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0].driver?.licenseNumber).toBe('Q99990000')
    })

    it('con cargo opcional y la licencia activa, la casilla nace marcada', async () => {
      renderPage(trabajador({ role: rol('assistant') }))
      await listo()
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toBeChecked()
      expect(screen.getByLabelText('N.° de licencia')).toHaveValue('Q11112222')
    })

    /** Una licencia apagada no se vuelve a encender por abrir la edición: hace falta marcarla. */
    it('con cargo opcional y la licencia apagada, la casilla nace sin marcar y la licencia no viaja', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador({ role: rol('assistant'), driver: fakeDriverProfile({ isActive: false }) }))
      await listo()
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).not.toBeChecked()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0]).not.toHaveProperty('driver')
    })

    /** La casilla se marca sola al volver a un cargo opcional con la licencia escrita, también tras una ida y vuelta. */
    it('de opcional a uno que la exige y de vuelta, con la licencia escrita, la casilla queda marcada', async () => {
      const user = userEvent.setup()
      renderPage(trabajador({ role: rol('assistant'), driver: fakeDriverProfile({ isActive: false }) }))
      await listo()
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).not.toBeChecked()

      await user.selectOptions(cargo(), 'Conductor')
      await user.selectOptions(cargo(), 'Ayudante')
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toBeChecked()
    })

    it('desmarcar la casilla apaga la licencia: no viaja', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador({ role: rol('assistant') }))
      await listo()
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0]).not.toHaveProperty('driver')
    })

    it('pasar a un cargo sin licencia la apaga: no viaja', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador())
      await listo()
      await user.selectOptions(cargo(), 'Operador')
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0]).not.toHaveProperty('driver')
      expect(sink.bodies?.[0].role).toBe('operator')
    })

    it('una licencia que nace en la edición va disponible', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador({ role: rol('operator'), driver: null }))
      await listo()
      await user.selectOptions(cargo(), 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q55556666')
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      // Sin disponibilidad en el cuerpo, el backend la crea disponible.
      expect(sink.bodies?.[0].driver).toEqual({ licenseNumber: 'Q55556666', licenseCategory: null })
    })

    it('el teléfono borrado viaja como null', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador())
      await listo()
      await user.clear(screen.getByLabelText('Teléfono (opcional)'))
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0].phone).toBeNull()
    })

    /** El backend admite fechas futuras para corregir a mano: una ya guardada no bloquea la edición. */
    it('una fecha de ingreso futura ya guardada no bloquea guardar', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador({ hireDate: '2099-01-01' }))
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0].hireDate).toBe('2099-01-01')
    })
  })

  describe('sin cambios no se guarda', () => {
    /** Así un guardado vacío no mueve "Modificado por". */
    it('abrir y no tocar deja Guardar deshabilitado y no envía nada', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador())
      await listo()

      expect(guardar()).toBeDisabled()
      await user.click(guardar())
      expect(sink.bodies).toHaveLength(0)
    })

    /** Un botón deshabilitado no recibe foco: el motivo se dice en texto, como en la edición de clientes. */
    it('mientras no hay cambios lo dice en texto, y el texto se va con el primer cambio', async () => {
      const user = userEvent.setup()
      renderPage(trabajador())
      await listo()
      expect(screen.getByText('Cambia algún dato para guardar.')).toBeInTheDocument()
      // Deshabilitado tiene que verse apagado: la consulta mira el atributo, no el color.
      expect(guardar()).toBeDisabled()
      expect(guardar().className).toContain('disabled:bg-accent-disabled')

      await cambiarApellido(user)
      expect(screen.queryByText('Cambia algún dato para guardar.')).not.toBeInTheDocument()
    })

    it('un cambio lo habilita y volver al valor original lo deshabilita otra vez', async () => {
      const user = userEvent.setup()
      renderPage(trabajador())
      await listo()
      await user.type(screen.getByLabelText('Nombre'), 'o')
      expect(guardar()).toBeEnabled()

      await user.type(screen.getByLabelText('Nombre'), '{Backspace}')
      expect(guardar()).toBeDisabled()
    })

    it.each([
      ['espacios en los bordes del nombre', 'Nombre', ' José Juan '],
      ['la tilde del apellido escrita como letra más marca', 'Apellido', 'Pe\u0301rez Huama\u0301n'],
      ['la tilde del nombre escrita como letra más marca', 'Nombre', 'Jose\u0301 Juan'],
      ['espacios en los bordes del número', 'Número de documento', ' X1234567 '],
    ])('%s no cuentan como cambio', async (_caso, campo, valor) => {
      const user = userEvent.setup()
      renderPage(trabajador({ firstName: 'José Juan' }))
      await listo()
      await user.clear(screen.getByLabelText(campo))
      await user.click(screen.getByLabelText(campo))
      await user.paste(valor)

      expect(guardar()).toBeDisabled()
    })

    it('un teléfono guardado como nulo abre vacío y no cuenta como cambio', async () => {
      renderPage(trabajador({ phone: null }))
      await listo()
      expect(screen.getByLabelText('Teléfono (opcional)')).toHaveValue('')
      expect(guardar()).toBeDisabled()
    })

    it('la licencia apagada de un cargo opcional no cuenta como cambio hasta marcar la casilla', async () => {
      const user = userEvent.setup()
      renderPage(trabajador({ role: rol('assistant'), driver: fakeDriverProfile({ isActive: false }) }))
      await listo()
      expect(guardar()).toBeDisabled()

      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      expect(guardar()).toBeEnabled()
    })

    it('cambiar la licencia o el cargo cuenta como cambio', async () => {
      const user = userEvent.setup()
      renderPage(trabajador())
      await listo()
      await user.type(screen.getByLabelText('Categoría (opcional)'), 'x')
      expect(guardar()).toBeEnabled()

      await user.type(screen.getByLabelText('Categoría (opcional)'), '{Backspace}')
      await user.selectOptions(cargo(), 'Escolta')
      expect(guardar()).toBeEnabled()
    })
  })

  describe('motivo del cambio', () => {
    it('aparece al cambiar el número y desaparece si vuelve al original', async () => {
      const user = userEvent.setup()
      renderPage(trabajador())
      await listo()
      const numero = screen.getByLabelText('Número de documento')
      await user.type(numero, '9')
      expect(screen.getByLabelText('Motivo del cambio')).toBeInTheDocument()

      await user.type(numero, '{Backspace}')
      expect(screen.queryByLabelText('Motivo del cambio')).not.toBeInTheDocument()
    })

    it('con el número cambiado, el motivo es obligatorio y de al menos 10 caracteres', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador())
      await listo()
      await user.type(screen.getByLabelText('Número de documento'), '9')
      await user.type(screen.getByLabelText('Motivo del cambio'), 'corto')
      await user.click(guardar())

      expect(await screen.findByText('Indica el motivo del cambio, de al menos 10 caracteres.')).toBeInTheDocument()
      expect(sink.bodies).toHaveLength(0)
    })

    it('el motivo viaja recortado con el número nuevo', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador())
      await listo()
      await user.type(screen.getByLabelText('Número de documento'), '9')
      await user.type(screen.getByLabelText('Motivo del cambio'), '  Corrección del carné mal digitado  ')
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0].documentNumber).toBe('X12345679')
      expect(sink.bodies?.[0].reason).toBe('Corrección del carné mal digitado')
    })

    it('un motivo escrito y abandonado no viaja si el número volvió al original', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      renderPage(trabajador())
      await listo()
      const numero = screen.getByLabelText('Número de documento')
      await user.type(numero, '9')
      await user.type(screen.getByLabelText('Motivo del cambio'), 'Un motivo largo de sobra')
      await user.type(numero, '{Backspace}')
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(sink.bodies).toHaveLength(1))
      expect(sink.bodies?.[0].reason).toBeNull()
    })

    it('un 400 de forma que nombra el motivo pone el mensaje en su campo', async () => {
      const user = userEvent.setup()
      server.use(updateWorkerValidation([{ field: 'reason', message: 'Motivo raro' }]))
      renderPage(trabajador())
      await listo()
      await user.type(screen.getByLabelText('Número de documento'), '9')
      await user.type(screen.getByLabelText('Motivo del cambio'), 'Un motivo largo de sobra')
      await user.click(guardar())

      await waitFor(() =>
        expect(screen.getByLabelText('Motivo del cambio')).toHaveAccessibleDescription(expect.stringContaining('Motivo raro')),
      )
    })

    it('WRK-009 va en el campo del motivo', async () => {
      const user = userEvent.setup()
      server.use(updateWorkerProblem('WRK-009', 400))
      renderPage(trabajador())
      await listo()
      await user.type(screen.getByLabelText('Número de documento'), '9')
      await user.type(screen.getByLabelText('Motivo del cambio'), 'Un motivo largo de sobra')
      await user.click(guardar())

      await waitFor(() =>
        expect(screen.getByLabelText('Motivo del cambio')).toHaveAccessibleDescription(
          expect.stringContaining('Indica el motivo del cambio, de al menos 10 caracteres.'),
        ),
      )
    })
  })

  describe('aviso de usuario', () => {
    it('con usuario, cambiar el cargo avisa que cambian sus permisos; volver al suyo lo quita', async () => {
      const user = userEvent.setup()
      renderPage(trabajador({ role: rol('sales'), driver: null, hasUser: true }))
      await listo()
      expect(screen.queryByText(/cambian sus permisos/)).not.toBeInTheDocument()

      await user.selectOptions(cargo(), 'Encargado de Almacén')
      expect(screen.getByRole('status')).toHaveTextContent(
        'Este trabajador tiene usuario: al cambiar su cargo de Ejecutivo de Ventas a Encargado de Almacén, cambian sus permisos en el sistema.',
      )

      await user.selectOptions(cargo(), 'Ejecutivo de Ventas')
      expect(screen.queryByText(/cambian sus permisos/)).not.toBeInTheDocument()
    })

    it('el aviso va arriba del formulario, antes de los datos', async () => {
      const user = userEvent.setup()
      renderPage(trabajador({ role: rol('sales'), driver: null, hasUser: true }))
      await listo()
      await user.selectOptions(cargo(), 'Encargado de Almacén')

      const aviso = screen.getByText(/cambian sus permisos/)
      expect(aviso.compareDocumentPosition(screen.getByLabelText('Nombre'))).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    })

    it('sin usuario, cambiar el cargo no avisa nada', async () => {
      const user = userEvent.setup()
      renderPage(trabajador({ role: rol('operator'), driver: null }))
      await listo()
      await user.selectOptions(cargo(), 'Encargado de Almacén')
      expect(screen.queryByText(/cambian sus permisos/)).not.toBeInTheDocument()
    })
  })

  describe('errores del backend', () => {
    it('WRK-006 se avisa sin campo y el formulario queda como estaba', async () => {
      const user = userEvent.setup()
      server.use(updateWorkerProblem('WRK-006', 403))
      const { router } = renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() =>
        expect(toast.error).toHaveBeenCalledWith('No puedes modificar a un trabajador de tu nivel o superior.'),
      )
      expect(router.state.location.pathname).toBe(workerEditPath(ID))
      expect(screen.getByLabelText('Apellido')).toHaveValue('Pérez Huamána')
    })

    it('WRK-001 al guardar dice que no existe y vuelve a la búsqueda', async () => {
      const user = userEvent.setup()
      server.use(updateWorkerProblem('WRK-001', 404))
      const { router } = renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(router.state.location.pathname).toBe(WORKERS_BASE))
      expect(toast.error).toHaveBeenCalledWith('Este trabajador no existe.')
      // Reemplaza la entrada: Atrás no vuelve a la edición de un trabajador que no existe.
      expect(router.state.historyAction).toBe('REPLACE')
    })

    it.each([
      ['WRK-011', 'Este cargo no inicia sesión y el trabajador tiene usuario. Elige otro.'],
      ['WRK-012', 'No puedes cambiar tu propio cargo.'],
    ])('%s va en el campo del cargo', async (code, texto) => {
      const user = userEvent.setup()
      server.use(updateWorkerProblem(code, code === 'WRK-011' ? 400 : 403))
      renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(cargo()).toHaveAccessibleDescription(texto))
    })

    it.each([
      ['WRK-005', 'cargos'],
      ['WRK-003', 'tipos de documento'],
    ])('%s recarga los %s', async (code) => {
      const user = userEvent.setup()
      const pedidos: { calls?: number } = {}
      server.use(
        code === 'WRK-005' ? listRolesSequence([ROLES], pedidos) : listDocumentTypesSequence([[DNI, CE]], pedidos),
        updateWorkerProblem(code, 400),
      )
      renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(pedidos.calls).toBe(2))
    })

    it('una caída de red se avisa con el texto de la edición', async () => {
      const user = userEvent.setup()
      server.use(updateWorkerNetworkError())
      renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() =>
        expect(toast.error).toHaveBeenCalledWith('No se pudieron guardar los cambios. Intenta de nuevo.'),
      )
    })

    it('un guardado sin cuerpo se avisa como error y no navega', async () => {
      const user = userEvent.setup()
      server.use(updateWorkerEmpty())
      const { router } = renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Error inesperado. Intenta de nuevo.', expect.anything()))
      expect(router.state.location.pathname).toBe(workerEditPath(ID))
    })

    it('WRK-002 va en el número, como en el alta', async () => {
      const user = userEvent.setup()
      server.use(updateWorkerProblem('WRK-002', 409))
      renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() =>
        expect(screen.getByLabelText('Número de documento')).toHaveAccessibleDescription(
          'Ya existe un trabajador con este documento.',
        ),
      )
    })
  })

  describe('al guardar', () => {
    it('vuelve a la ficha con lo devuelto y refresca el padrón, los conductores y los viajes', async () => {
      const user = userEvent.setup()
      const devuelto = trabajador({ lastName: 'Quispe', updatedAt: '2026-10-03T15:00:00Z' })
      const sink: { ids?: number[]; bodies?: WorkerUpdateRequest[] } = {}
      server.use(updateWorkerCapture(sink, devuelto))
      const { router, queryClient } = renderPage(trabajador())
      await listo()
      await cambiarApellido(user)
      await user.click(guardar())

      await waitFor(() => expect(router.state.location.pathname).toBe(workerDetailPath(ID)))
      expect(queryClient.getQueryData(workerKeys.detail(ID))).toEqual(devuelto)
      expect(queryClient.getQueryState(workerKeys.search({ q: 'juan', isActive: true }))?.isInvalidated).toBe(true)
      expect(queryClient.getQueryState(operationsKeys.drivers())?.isInvalidated).toBe(true)
      // Ya pudo estar asignado: su nombre o su licencia se ven en la lista y el detalle de sus viajes.
      expect(queryClient.getQueryState(operationsKeys.serviceList({ page: 0 }))?.isInvalidated).toBe(true)
      expect(queryClient.getQueryState(operationsKeys.serviceDetail(9))?.isInvalidated).toBe(true)
    })

    it('mientras guarda, Cancelar y Volver quedan deshabilitados', async () => {
      const user = userEvent.setup()
      const sink: { ids?: number[]; bodies?: WorkerUpdateRequest[] } = {}
      server.use(updateWorkerCapture(sink, undefined, 150))
      renderPage(trabajador())
      await listo()
      await user.type(screen.getByLabelText('Número de documento'), '9')
      await user.type(screen.getByLabelText('Motivo del cambio'), 'Un motivo largo de sobra')
      await user.click(guardar())

      expect(await screen.findByRole('button', { name: 'Guardando…' })).toBeDisabled()
      expect(screen.getByLabelText('Motivo del cambio')).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
      expect(screen.queryByRole('link', { name: 'Volver a la ficha' })).not.toBeInTheDocument()
    })

    it('cancelar vuelve a la ficha sin enviar', async () => {
      const user = userEvent.setup()
      const sink = capturar()
      const { router } = renderPage(trabajador())
      await listo()
      await user.click(screen.getByRole('button', { name: 'Cancelar' }))

      expect(router.state.location.pathname).toBe(workerDetailPath(ID))
      expect(sink.bodies).toHaveLength(0)
    })
  })

  describe('accesibilidad', () => {
    it('sin violaciones al abrir y con el motivo y el aviso de usuario a la vista', async () => {
      const user = userEvent.setup()
      const { container } = renderPage(trabajador({ role: rol('sales'), driver: null, hasUser: true }))
      await listo()
      expect(await axe(container)).toHaveNoViolations()

      await user.type(screen.getByLabelText('Número de documento'), '9')
      await user.selectOptions(cargo(), 'Encargado de Almacén')
      expect(await axe(container)).toHaveNoViolations()
    })
  })
})
