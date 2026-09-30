import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { WorkerCreatePage } from './WorkerCreatePage'
import { WORKERS_BASE, workerDetailPath } from '../../../shared/paths'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { server } from '../../../test/mocks/server'
import type { UserResponse, UserRole, WorkerRequest } from '../../../api'
import { operationsKeys } from '../../operations/queryKeys'
import { warehouseKeys } from '../../warehouse/queryKeys'
import { workerKeys } from '../queryKeys'
import {
  CE,
  DNI,
  ROLES,
  createWorkerCapture,
  createWorkerNetworkError,
  createWorkerProblem,
  createWorkerValidation,
  fakeWorkerDetail,
  listDocumentTypesError,
  listDocumentTypesOk,
  listDocumentTypesSequence,
  listRolesError,
  listRolesOk,
  listRolesSlow,
  listRolesSequence,
  listRolesFailing,
  catalogsCounting,
  createWorkerFailingOnce,
  createWorkerErrorsInSequence,
  listDocumentTypesFailing,
} from '../../../test/mocks/handlers/workers'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const NUEVO = `${WORKERS_BASE}/nuevo`

function buildUser(role: UserRole, position = 'Cargo de prueba'): UserResponse {
  return { id: 1, username: `user-${role}`, fullName: `Usuario ${role}`, position, role, isActive: true }
}

function renderPage({ role = 'operations_manager' as UserRole, position }: { role?: UserRole; position?: string } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, buildUser(role, position))
  // Cachés sembradas: las que el alta tiene que invalidar y una ajena que no.
  queryClient.setQueryData(workerKeys.search({ q: 'ana', isActive: true }), [])
  queryClient.setQueryData(workerKeys.detail(3), fakeWorkerDetail({ id: 3 }))
  queryClient.setQueryData(warehouseKeys.workerSearch('ana'), [])
  queryClient.setQueryData(warehouseKeys.workerSearch('ros'), [])
  queryClient.setQueryData(operationsKeys.drivers(), [])
  queryClient.setQueryData([...warehouseKeys.all, 'stock'], [])
  const router = createMemoryRouter(
    [
      { path: NUEVO, element: <WorkerCreatePage /> },
      { path: `${WORKERS_BASE}/:id`, element: <p>Pantalla de detalle</p> },
      { path: WORKERS_BASE, element: <p>Pantalla de búsqueda</p> },
    ],
    { initialEntries: [NUEVO] },
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

const listo = () => screen.findByLabelText('Nombre')
const cargo = () => screen.getByLabelText('Cargo')
const invalidada = (queryClient: QueryClient, key: readonly unknown[]) =>
  queryClient.getQueryState(key)?.isInvalidated

async function llenarValido(user: ReturnType<typeof userEvent.setup>, cargoVisible = 'Operador') {
  await user.type(screen.getByLabelText('Nombre'), 'Juan')
  await user.type(screen.getByLabelText('Apellido'), 'Pérez Huamán')
  await user.type(screen.getByLabelText('Número de documento'), '45678912')
  await user.selectOptions(cargo(), cargoVisible)
}

function opcionesDeCargo() {
  return within(cargo())
    .getAllByRole('option')
    .map((option) => option.textContent)
    .filter((texto) => texto !== 'Elige el cargo')
}

afterEach(() => {
  vi.useRealTimers()
  vi.mocked(toast.error).mockClear()
  vi.mocked(toast.success).mockClear()
})

describe('WorkerCreatePage', () => {
  describe('apertura', () => {
    it('muestra la carga mientras llegan los catálogos', async () => {
      server.use(listRolesSlow(80))
      renderPage()

      await waitFor(() => expect(screen.getByLabelText('Cargando formulario')).toBeInTheDocument())
      expect(await listo()).toBeInTheDocument()
    })

    it('el título es "Nuevo trabajador" y ofrece volver a la búsqueda', async () => {
      renderPage()
      await listo()

      expect(screen.getByRole('heading', { level: 1, name: 'Nuevo trabajador' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /volver a trabajadores/i })).toHaveAttribute('href', WORKERS_BASE)
    })

    it('los campos van en el orden de la ficha', async () => {
      renderPage()
      await listo()

      const rotulos = ['Nombre', 'Apellido', 'Tipo de documento', 'Número de documento', 'Cargo', 'Fecha de ingreso', 'Teléfono (opcional)']
      const campos = rotulos.map((rotulo) => screen.getByLabelText(rotulo))
      for (let i = 1; i < campos.length; i++) {
        expect(campos[i - 1].compareDocumentPosition(campos[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      }
    })

    it('abre vacío, con el primer tipo de documento elegido', async () => {
      renderPage()
      await listo()

      expect(screen.getByLabelText('Tipo de documento')).toHaveValue(String(DNI.id))
      expect(screen.getByLabelText('Nombre')).toHaveValue('')
      expect(screen.getByLabelText('Número de documento')).toHaveValue('')
      expect(screen.getByLabelText('Teléfono (opcional)')).toHaveValue('')
      expect(cargo()).toHaveValue('')
    })

    it('los catálogos no vencen solos', async () => {
      const { queryClient } = renderPage()
      await listo()

      expect(queryClient.getQueryCache().find({ queryKey: workerKeys.roles() })?.isStale()).toBe(false)
      expect(queryClient.getQueryCache().find({ queryKey: workerKeys.documentTypes() })?.isStale()).toBe(false)
    })

    /** El orden del catálogo manda: "el primero" no es "el id 1" ni "el DNI". */
    it('el primer tipo sale del catálogo, no de un id fijo', async () => {
      server.use(listDocumentTypesOk([CE, DNI]))
      renderPage()
      await listo()

      expect(screen.getByLabelText('Tipo de documento')).toHaveValue(String(CE.id))
    })

    /** 02:30 UTC del 25: en Lima es el 24 y en Tokio (la zona de la suite) ya el 25. */
    it('la fecha de ingreso arranca en hoy de Lima, y ese es su tope', async () => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))
      renderPage()
      await listo()

      expect(screen.getByLabelText('Fecha de ingreso')).toHaveValue('2026-08-24')
      expect(screen.getByLabelText('Fecha de ingreso')).toHaveAttribute('max', '2026-08-24')
    })

    it('sin cargo no hay sección de licencia', async () => {
      renderPage()
      await listo()

      expect(screen.queryByRole('group', { name: 'Licencia de conducir' })).not.toBeInTheDocument()
    })

    it.each([
      ['roles', listRolesFailing],
      ['tipos de documento', listDocumentTypesFailing],
    ])('un 403 del catálogo de %s no se reintenta y un 500 sí, una vez', async (_nombre, falla) => {
      const prohibido: { calls?: number } = {}
      server.use(falla(403, prohibido))
      const primero = renderPage()
      await screen.findByRole('alert', {}, { timeout: 4000 })
      expect(prohibido.calls).toBe(1)
      primero.unmount()

      const caido: { calls?: number } = {}
      server.use(falla(500, caido))
      renderPage()
      await screen.findByRole('alert', {}, { timeout: 4000 })
      expect(caido.calls).toBe(2)
    })

    it.each([
      ['roles', listRolesError],
      ['tipos de documento', listDocumentTypesError],
    ])('si falla el catálogo de %s, lo explica y deja reintentar', async (_nombre, falla) => {
      const user = userEvent.setup()
      server.use(falla(500))
      renderPage()

      expect(await screen.findByRole('alert', {}, { timeout: 4000 })).toHaveTextContent('Error interno del servidor')
      expect(screen.queryByLabelText('Nombre')).not.toBeInTheDocument()

      server.resetHandlers()
      await user.click(screen.getByRole('button', { name: /reintentar/i }))
      expect(await listo()).toBeInTheDocument()
    })
  })

  describe('validación en vivo', () => {
    it('salir de un campo inválido muestra su error sin guardar, y solo en ese campo', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.click(screen.getByLabelText('Nombre'))
      await user.tab()

      expect(screen.getByLabelText('Nombre')).toHaveAccessibleDescription('Indica el nombre.')
      expect(screen.getByLabelText('Apellido')).toHaveAttribute('aria-invalid', 'false')
      expect(screen.queryByText('Indica el apellido.')).not.toBeInTheDocument()
      expect(screen.queryByText('Elige el cargo.')).not.toBeInTheDocument()
    })

    it('un nombre con dígitos o símbolos se avisa en el campo al salir', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.type(screen.getByLabelText('Nombre'), 'Juan2')
      // Mientras escribe por primera vez no hay aviso: sale al dejar el campo.
      expect(screen.getByLabelText('Nombre')).toHaveAttribute('aria-invalid', 'false')
      expect(screen.queryByText('Solo letras, espacios, apóstrofo o guion.')).not.toBeInTheDocument()
      await user.type(screen.getByLabelText('Apellido'), 'Ana@')
      await user.tab()

      expect(screen.getByLabelText('Nombre')).toHaveAccessibleDescription('Solo letras, espacios, apóstrofo o guion.')
      expect(screen.getByLabelText('Apellido')).toHaveAccessibleDescription('Solo letras, espacios, apóstrofo o guion.')
    })

    it.each([['Cancelar'], ['Guardar']])(
      'presionar %s no le quita el foco al campo: su aviso no corre el botón antes del clic',
      async (boton) => {
        const user = userEvent.setup()
        renderPage()
        await listo()
        await user.click(screen.getByLabelText('Nombre'))
        await user.pointer({ keys: '[MouseLeft>]', target: screen.getByRole('button', { name: boton }) })

        expect(screen.getByLabelText('Nombre')).toHaveFocus()
        expect(screen.queryByText('Indica el nombre.')).not.toBeInTheDocument()
        // En mousedown y no en pointerdown: cancelar este último no evita el foco en el navegador.
        expect(fireEvent.mouseDown(screen.getByRole('button', { name: boton }))).toBe(false)
        await user.pointer({ keys: '[/MouseLeft]', target: screen.getByRole('button', { name: boton }) })
      },
    )

    it('presionar "Tiene licencia" no le quita el foco al campo y el clic la marca igual', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Ayudante')
      await user.click(screen.getByLabelText('Nombre'))
      await user.pointer({ keys: '[MouseLeft>]', target: screen.getByText('Tiene licencia') })

      expect(screen.getByLabelText('Nombre')).toHaveFocus()
      expect(screen.queryByText('Indica el nombre.')).not.toBeInTheDocument()
      expect(fireEvent.mouseDown(screen.getByText('Tiene licencia'))).toBe(false)
      expect(fireEvent.mouseDown(screen.getByRole('checkbox', { name: 'Tiene licencia' }))).toBe(false)
      await user.pointer({ keys: '[/MouseLeft]', target: screen.getByText('Tiene licencia') })
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toBeChecked()
    })

    it.each([['N.° de licencia'], ['Categoría (opcional)']])(
      'desmarcar "Tiene licencia" con el foco en %s deja el foco en la casilla',
      async (campo) => {
        const user = userEvent.setup()
        renderPage()
        await listo()
        await user.selectOptions(cargo(), 'Ayudante')
        await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
        await user.type(screen.getByLabelText(campo), 'Q1')
        await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))

        expect(screen.queryByLabelText('N.° de licencia')).not.toBeInTheDocument()
        expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toHaveFocus()
      },
    )

    it('marcar "Tiene licencia" con un toque sin foco previo deja el foco en la casilla, y Tab sigue a la licencia', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Ayudante')
      ;(document.activeElement as HTMLElement | null)?.blur()
      expect(document.body).toHaveFocus()
      await user.pointer({ keys: '[TouchA]', target: screen.getByRole('checkbox', { name: 'Tiene licencia' }) })

      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toBeChecked()
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toHaveFocus()
      await user.tab()
      expect(screen.getByLabelText('N.° de licencia')).toHaveFocus()
    })

    /** El campo se deja antes de ocultarse, como al tocar el texto: tocado y vacío, guarda su aviso. */
    it('una licencia tocada y vacía muestra su aviso al volver a marcar la casilla', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Ayudante')
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.click(screen.getByLabelText('N.° de licencia'))
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))

      expect(screen.getByLabelText('N.° de licencia')).toHaveAccessibleDescription('Indica el número de licencia.')
    })

    it('con el teclado, Espacio sobre "Tiene licencia" la marca y la desmarca como antes', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Ayudante')
      await user.click(screen.getByLabelText('Teléfono (opcional)'))
      await user.tab()
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toHaveFocus()

      await user.keyboard(' ')
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toBeChecked()
      await user.keyboard(' ')
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).not.toBeChecked()
    })

    it('con el foco en un campo, Cancelar vuelve a la búsqueda', async () => {
      const user = userEvent.setup()
      const { router } = renderPage()
      await listo()
      await user.click(screen.getByLabelText('Nombre'))
      await user.click(screen.getByRole('button', { name: 'Cancelar' }))

      expect(router.state.location.pathname).toBe(WORKERS_BASE)
    })

    it('con el foco en un campo, Guardar marca todo el formulario y no envía', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await user.click(screen.getByLabelText('Nombre'))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByText('Indica el nombre.')).toBeInTheDocument()
      expect(screen.getByText('Indica el apellido.')).toBeInTheDocument()
      expect(screen.getByText('Elige el cargo.')).toBeInTheDocument()
      expect(screen.getByLabelText('Nombre')).toHaveFocus()
      expect(sink.calls).toBe(0)
    })

    it('con el teclado, Cancelar y Guardar responden a Enter como antes', async () => {
      const user = userEvent.setup()
      const { router } = renderPage()
      await listo()
      screen.getByRole('button', { name: 'Guardar' }).focus()
      await user.keyboard('{Enter}')
      expect(await screen.findByText('Indica el nombre.')).toBeInTheDocument()
      expect(screen.getByLabelText('Nombre')).toHaveFocus()

      screen.getByRole('button', { name: 'Cancelar' }).focus()
      await user.keyboard('{Enter}')
      expect(router.state.location.pathname).toBe(WORKERS_BASE)
    })

    it('después de salir, el error se corrige en vivo mientras escribe', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.type(screen.getByLabelText('Número de documento'), '123')
      await user.tab()
      expect(screen.getByLabelText('Número de documento')).toHaveAccessibleDescription(
        'El número no tiene el formato de DNI.',
      )

      await user.type(screen.getByLabelText('Número de documento'), '45678')
      expect(screen.getByLabelText('Número de documento')).toHaveAttribute('aria-invalid', 'false')
      expect(screen.queryByText('El número no tiene el formato de DNI.')).not.toBeInTheDocument()
    })

    it('cambiar el tipo vuelve a medir el número ya tocado', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.type(screen.getByLabelText('Número de documento'), 'AB1234')
      await user.tab()
      expect(screen.getByLabelText('Número de documento')).toHaveAccessibleDescription(
        'El número no tiene el formato de DNI.',
      )

      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'Carné de extranjería')
      await waitFor(() => expect(screen.getByLabelText('Número de documento')).toHaveAttribute('aria-invalid', 'false'))
      // Solo el número: los campos que nadie tocó siguen sin error.
      expect(screen.getByLabelText('Nombre')).toHaveAttribute('aria-invalid', 'false')
      expect(screen.getByLabelText('Apellido')).toHaveAttribute('aria-invalid', 'false')
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'DNI')
      await waitFor(() =>
        expect(screen.getByLabelText('Número de documento')).toHaveAccessibleDescription(
          'El número no tiene el formato de DNI.',
        ),
      )
    })

    it('cambiar el tipo no marca un número que nadie tocó', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'Carné de extranjería')
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'DNI')
      await user.tab()

      expect(screen.getByLabelText('Número de documento')).toHaveAttribute('aria-invalid', 'false')
      expect(screen.queryByText('Indica el número de documento.')).not.toBeInTheDocument()
    })

    it('la licencia aparece sin errores, por el cargo o por la casilla, hasta que se pasa por ella', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')
      expect(screen.getByLabelText('N.° de licencia')).toHaveAttribute('aria-invalid', 'false')

      await user.selectOptions(cargo(), 'Ayudante')
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      expect(screen.getByLabelText('N.° de licencia')).toHaveAttribute('aria-invalid', 'false')
      expect(screen.queryByText('Indica el número de licencia.')).not.toBeInTheDocument()

      await user.click(screen.getByLabelText('N.° de licencia'))
      await user.tab()
      expect(screen.getByLabelText('N.° de licencia')).toHaveAccessibleDescription('Indica el número de licencia.')
    })
  })

  describe('licencia de conducir según el cargo', () => {
    it('con un cargo que la exige, aparece sin casilla y sin elegir disponibilidad', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')

      const seccion = screen.getByRole('group', { name: 'Licencia de conducir' })
      expect(within(seccion).getByLabelText('N.° de licencia')).toBeInTheDocument()
      expect(within(seccion).getByLabelText('Categoría (opcional)')).toBeInTheDocument()
      expect(within(seccion).queryByRole('checkbox')).not.toBeInTheDocument()
      // El alta la crea disponible siempre (decisión del dueño): no se elige.
      expect(within(seccion).queryByLabelText(/disponibilidad/i)).not.toBeInTheDocument()
    })

    /** La modalidad sale del catálogo, no del nombre "driver". */
    it('la escolta también la exige', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Escolta')

      expect(screen.getByLabelText('N.° de licencia')).toBeInTheDocument()
    })

    it('con un cargo de licencia opcional, aparece la casilla sin marcar y sin campos', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Ayudante')

      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).not.toBeChecked()
      expect(screen.queryByLabelText('N.° de licencia')).not.toBeInTheDocument()

      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      expect(screen.getByLabelText('N.° de licencia')).toBeInTheDocument()
      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toHaveAttribute(
        'aria-controls',
        'trabajador-licencia-campos',
      )
    })

    it('con un cargo sin licencia, no hay sección', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Operador')

      expect(screen.queryByRole('group', { name: 'Licencia de conducir' })).not.toBeInTheDocument()
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    })

    it('cambiar de cargo no pierde lo escrito', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.type(screen.getByLabelText('Nombre'), 'Juan')
      await user.selectOptions(cargo(), 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q12345678')
      await user.type(screen.getByLabelText('Categoría (opcional)'), 'A-IIIb')
      await user.selectOptions(cargo(), 'Operador')
      await user.selectOptions(cargo(), 'Conductor')

      expect(screen.getByLabelText('N.° de licencia')).toHaveValue('Q12345678')
      expect(screen.getByLabelText('Categoría (opcional)')).toHaveValue('A-IIIb')
      expect(screen.getByLabelText('Nombre')).toHaveValue('Juan')
    })

    it('de un cargo que la exige a uno opcional, con licencia escrita, la casilla nace marcada', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q12345678')
      await user.selectOptions(cargo(), 'Ayudante')

      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).toBeChecked()
      expect(screen.getByLabelText('N.° de licencia')).toHaveValue('Q12345678')
      expect(cargo()).toHaveFocus()
    })

    it('una licencia de solo espacios no marca la casilla', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), '   ')
      await user.selectOptions(cargo(), 'Ayudante')

      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).not.toBeChecked()
    })

    it('sin licencia escrita, la casilla del cargo opcional nace sin marcar', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')
      await user.selectOptions(cargo(), 'Ayudante')

      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).not.toBeChecked()
    })
  })

  describe('cargos que la sesión puede asignar', () => {
    it('el admin ve los once cargos, incluido el suyo', async () => {
      renderPage({ role: 'admin' })
      await listo()

      expect(opcionesDeCargo()).toEqual(ROLES.map((role) => role.description))
    })

    /** Finanzas es de nivel 2: sus pares (despacho y ventas) quedan fuera. */
    it('finanzas solo ve los de nivel menor al suyo', async () => {
      renderPage({ role: 'finance_manager' })
      await listo()

      expect(opcionesDeCargo()).toEqual(['Encargado de Almacén', 'Conductor', 'Escolta', 'Ayudante', 'Operador'])
    })

    it('el gerente general no ve los de su nivel ni el admin', async () => {
      renderPage({ role: 'general_manager' })
      await listo()

      expect(opcionesDeCargo()).toEqual([
        'Jefe de Finanzas',
        'Ejecutivo de Ventas',
        'Coordinador de Operaciones',
        'Encargado de Almacén',
        'Conductor',
        'Escolta',
        'Ayudante',
        'Operador',
      ])
    })

    /** El nivel sale del nombre de sistema del rol, no del cargo visible de la sesión. */
    it('el nivel se cruza por el rol de la sesión y no por su cargo visible', async () => {
      renderPage({ role: 'finance_manager', position: 'Gerente General' })
      await listo()

      expect(opcionesDeCargo()).toEqual(['Encargado de Almacén', 'Conductor', 'Escolta', 'Ayudante', 'Operador'])
    })

    it('si el rol de la sesión no está en el catálogo, no ofrece nada y no deja guardar', async () => {
      server.use(listRolesOk(ROLES.filter((role) => role.name !== 'finance_manager')))
      renderPage({ role: 'finance_manager' })
      await listo()

      expect(opcionesDeCargo()).toEqual([])
      expect(screen.getByRole('status')).toHaveTextContent('No hay cargos que puedas asignar con tu rol.')
      expect(screen.getByRole('button', { name: 'Guardar' })).toBeDisabled()
    })
  })

  describe('validaciones', () => {
    it('exige nombre, apellido, número y cargo, y no envía', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByLabelText('Nombre')).toHaveAccessibleDescription('Indica el nombre.')
      expect(screen.getByLabelText('Apellido')).toHaveAccessibleDescription('Indica el apellido.')
      expect(screen.getByLabelText('Número de documento')).toHaveAccessibleDescription('Indica el número de documento.')
      expect(cargo()).toHaveAccessibleDescription('Elige el cargo.')
      expect(sink.calls).toBe(0)
    })

    it('el foco va al primer error', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.type(screen.getByLabelText('Nombre'), 'Juan')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(screen.getByLabelText('Apellido')).toHaveFocus())
    })

    it('el número se valida con el patrón del tipo elegido', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.clear(screen.getByLabelText('Número de documento'))
      await user.type(screen.getByLabelText('Número de documento'), '4567891a')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByText('El número no tiene el formato de DNI.')).toBeInTheDocument()
      expect(sink.calls).toBe(0)
    })

    /** Un tope nativo cortaría lo pegado antes de recortar los espacios, y guardaría otro número. */
    it('pegar un número largo no lo corta: avisa el largo del tipo y no envía', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.clear(screen.getByLabelText('Número de documento'))
      await user.click(screen.getByLabelText('Número de documento'))
      await user.paste('456789123')

      expect(screen.getByLabelText('Número de documento')).toHaveValue('456789123')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(screen.getByLabelText('Número de documento')).toHaveAccessibleDescription('Máximo 8 caracteres para DNI.')
      expect(sink.calls).toBe(0)
    })

    it('un número pegado con espacios en los bordes viaja entero', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.clear(screen.getByLabelText('Número de documento'))
      await user.click(screen.getByLabelText('Número de documento'))
      await user.paste(' 45678912 ')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body?.documentNumber).toBe('45678912')
    })

    it.each([
      ['N.° de licencia'],
      ['Categoría (opcional)'],
    ])('pegar una %s de 21 caracteres no la corta: avisa el tope y no envía', async (campo) => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q1')
      await user.clear(screen.getByLabelText(campo))
      await user.click(screen.getByLabelText(campo))
      await user.paste('A'.repeat(21))

      expect(screen.getByLabelText(campo)).toHaveValue('A'.repeat(21))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(screen.getByLabelText(campo)).toHaveAccessibleDescription('Máximo 20 caracteres.')
      expect(sink.calls).toBe(0)
    })

    /** El número ya escrito se vuelve a medir con el tipo nuevo. */
    it('cambiar a un tipo más corto revalida el número ya escrito', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'Carné de extranjería')
      await user.clear(screen.getByLabelText('Número de documento'))
      await user.type(screen.getByLabelText('Número de documento'), 'AB12345678')
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'DNI')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByText('Máximo 8 caracteres para DNI.')).toBeInTheDocument()
      expect(sink.calls).toBe(0)
    })

    it('ningún campo lleva tope nativo: los largos los mide la validación', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')

      const campos = screen.getAllByRole('textbox')
      expect(campos).toHaveLength(6)
      for (const campo of campos) expect(campo).not.toHaveAttribute('maxLength')
      expect(screen.getByLabelText('Teléfono (opcional)')).toHaveAttribute('type', 'tel')
    })

    // En jsdom un tope nativo no corta un input tel: para el teléfono esta fila mide el aviso, y
    // el tope lo mide la de arriba por el atributo.
    it.each([
      ['Nombre', 'A'.repeat(101), 'Máximo 100 caracteres.'],
      ['Apellido', 'B'.repeat(101), 'Máximo 100 caracteres.'],
      ['Teléfono (opcional)', '9876543210', 'El teléfono debe tener 9 dígitos.'],
    ])('pegar un %s largo no lo corta: avisa en el campo y no envía', async (campo, valor, aviso) => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.clear(screen.getByLabelText(campo))
      await user.click(screen.getByLabelText(campo))
      await user.paste(valor)

      expect(screen.getByLabelText(campo)).toHaveValue(valor)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(screen.getByLabelText(campo)).toHaveAccessibleDescription(aviso)
      expect(sink.calls).toBe(0)
    })

    it('el teléfono lleva 9 dígitos', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await llenarValido(user)
      await user.type(screen.getByLabelText('Teléfono (opcional)'), '12345678')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByText('El teléfono debe tener 9 dígitos.')).toBeInTheDocument()
    })

    it('la fecha de ingreso es obligatoria', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await llenarValido(user)
      await user.clear(screen.getByLabelText('Fecha de ingreso'))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByText('Indica la fecha de ingreso.')).toBeInTheDocument()
    })

    /** Guarda solo del cliente: el backend acepta fechas futuras a propósito. */
    it('rechaza una fecha futura en Lima y acepta hoy', async () => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.clear(screen.getByLabelText('Fecha de ingreso'))
      await user.type(screen.getByLabelText('Fecha de ingreso'), '2026-08-25')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByText('La fecha de ingreso no puede ser futura.')).toBeInTheDocument()
      expect(sink.calls).toBe(0)

      await user.clear(screen.getByLabelText('Fecha de ingreso'))
      await user.type(screen.getByLabelText('Fecha de ingreso'), '2026-08-24')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body?.hireDate).toBe('2026-08-24')
    })

    it('con un cargo que la exige, la licencia es obligatoria', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByLabelText('N.° de licencia')).toHaveAccessibleDescription('Indica el número de licencia.')
    })

    it('con la casilla marcada, la licencia es obligatoria', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await llenarValido(user, 'Ayudante')
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByText('Indica el número de licencia.')).toBeInTheDocument()
    })

    it('una licencia oculta no bloquea el envío', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.selectOptions(cargo(), 'Operador')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
    })
  })

  describe('lo que viaja', () => {
    it('sin licencia: la clave driver no viaja y el teléfono vacío va como null', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body).toEqual({
        firstName: 'Juan',
        lastName: 'Pérez Huamán',
        documentTypeId: 1,
        documentNumber: '45678912',
        phone: null,
        role: 'operator',
        hireDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
      })
      expect(sink.body).not.toHaveProperty('driver')
    })

    /** El alta siempre crea la licencia disponible: no se elige y viaja igual. */
    it('con un cargo que la exige, la licencia viaja disponible sin que nadie lo elija', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), ' Q12345678 ')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body?.role).toBe('driver')
      expect(sink.body?.driver).toEqual({ licenseNumber: 'Q12345678', licenseCategory: null, status: 'AVAILABLE' })
    })

    it('opcional sin marcar no manda licencia aunque haya una escrita', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user, 'Ayudante')
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q777')
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body).not.toHaveProperty('driver')
    })

    it('opcional marcado manda la licencia con su categoría', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user, 'Ayudante')
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q777')
      await user.type(screen.getByLabelText('Categoría (opcional)'), 'A-I')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body?.driver).toEqual({ licenseNumber: 'Q777', licenseCategory: 'A-I', status: 'AVAILABLE' })
    })

    it('un cargo sin licencia después de uno que la exige no arrastra la escrita', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q123')
      await user.selectOptions(cargo(), 'Operador')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body).not.toHaveProperty('driver')
    })

    it('un nombre pegado con la tilde aparte viaja en NFC', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.clear(screen.getByLabelText('Nombre'))
      await user.click(screen.getByLabelText('Nombre'))
      await user.paste('Jose\u0301')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      const codePoints = [...(sink.body?.firstName ?? '')].map((char) => char.codePointAt(0))
      expect(codePoints).toEqual([0x4a, 0x6f, 0x73, 0xe9])
    })

    it('el tipo viaja como su id y el cargo como su nombre de sistema', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      renderPage()
      await listo()
      await llenarValido(user, 'Ayudante')
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'Carné de extranjería')
      await user.clear(screen.getByLabelText('Número de documento'))
      await user.type(screen.getByLabelText('Número de documento'), 'AB1234')
      await user.type(screen.getByLabelText('Teléfono (opcional)'), '987654321')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(sink.calls).toBe(1))
      expect(sink.body?.documentTypeId).toBe(3)
      expect(sink.body?.role).toBe('assistant')
      expect(sink.body?.phone).toBe('987654321')
    })
  })

  describe('al guardar', () => {
    it('lleva a la ficha del creado y lo avisa', async () => {
      const user = userEvent.setup()
      const { router } = renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(router.state.location.pathname).toBe(workerDetailPath(57)))
      expect(toast.success).toHaveBeenCalledWith('Se dio de alta a Ana Torres Ruiz.')
    })

    it('invalida el padrón, los combobox de almacén (por prefijo) y los conductores, y nada más', async () => {
      const user = userEvent.setup()
      const { queryClient, router } = renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() => expect(router.state.location.pathname).toBe(workerDetailPath(57)))

      expect(invalidada(queryClient, workerKeys.search({ q: 'ana', isActive: true }))).toBe(true)
      expect(invalidada(queryClient, workerKeys.detail(3))).toBe(true)
      expect(invalidada(queryClient, warehouseKeys.workerSearch('ana'))).toBe(true)
      expect(invalidada(queryClient, warehouseKeys.workerSearch('ros'))).toBe(true)
      expect(invalidada(queryClient, operationsKeys.drivers())).toBe(true)
      expect(invalidada(queryClient, [...warehouseKeys.all, 'stock'])).toBe(false)
      // La ficha del creado queda sembrada y fresca: abre sin pedirla.
      expect(queryClient.getQueryData(workerKeys.detail(57))).toMatchObject({ id: 57 })
      expect(invalidada(queryClient, workerKeys.detail(57))).toBe(false)
    })

    it('un alta fallida no invalida nada', async () => {
      const user = userEvent.setup()
      server.use(createWorkerProblem('WRK-002', 409, 'Ya existe un trabajador con ese numero de documento'))
      const { queryClient } = renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await screen.findByText('Ya existe un trabajador con este documento.')

      expect(invalidada(queryClient, warehouseKeys.workerSearch('ana'))).toBe(false)
      expect(invalidada(queryClient, operationsKeys.drivers())).toBe(false)
      expect(invalidada(queryClient, workerKeys.search({ q: 'ana', isActive: true }))).toBe(false)
      expect(invalidada(queryClient, workerKeys.detail(3))).toBe(false)
    })

    /** Los catálogos se marcan viejos al guardar, pero no se vuelven a pedir en ese momento. */
    it('al guardar no vuelve a pedir los catálogos', async () => {
      const user = userEvent.setup()
      const pedidos: { roles?: number; documentTypes?: number } = {}
      server.use(...catalogsCounting(pedidos))
      const { router } = renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() => expect(router.state.location.pathname).toBe(workerDetailPath(57)))

      expect(pedidos).toEqual({ roles: 1, documentTypes: 1 })
    })

    it('guardar se deshabilita mientras envía y no duplica el alta', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink, fakeWorkerDetail({ id: 57 }), 150))
      const { router } = renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q1')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      expect(await screen.findByRole('button', { name: 'Guardando…' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Guardando…' }).closest('form')).toHaveAttribute('aria-busy', 'true')
      expect(screen.getByLabelText('Nombre')).toBeDisabled()
      expect(screen.getByLabelText('N.° de licencia')).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
      expect(screen.queryByRole('link', { name: 'Volver a trabajadores' })).not.toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Guardando…' }))
      await waitFor(() => expect(router.state.location.pathname).toBe(workerDetailPath(57)))
      expect(sink.calls).toBe(1)
    })

    it('cancelar vuelve a la búsqueda sin enviar', async () => {
      const user = userEvent.setup()
      const sink: { body?: WorkerRequest; calls?: number } = {}
      server.use(createWorkerCapture(sink))
      const { router } = renderPage()
      await listo()
      await user.click(screen.getByRole('button', { name: 'Cancelar' }))

      expect(router.state.location.pathname).toBe(WORKERS_BASE)
      expect(sink.calls).toBe(0)
    })
  })

  describe('errores del backend', () => {
    it.each([
      ['WRK-002', 409, 'Número de documento', 'Ya existe un trabajador con este documento.'],
      ['WRK-004', 400, 'Número de documento', 'El número no tiene el formato de este tipo de documento.'],
      ['WRK-005', 400, 'Cargo', 'Este cargo ya no está vigente. Elige otro.'],
      ['WRK-006', 403, 'Cargo', 'No puedes asignar un cargo de tu nivel o superior.'],
      ['WRK-008', 400, 'Cargo', 'La licencia no corresponde con este cargo. Revisa el cargo elegido.'],
    ] as const)('%s va en su campo con el texto propio', async (code, status, campo, texto) => {
      const user = userEvent.setup()
      server.use(createWorkerProblem(code, status, 'detail del backend'))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(screen.getByLabelText(campo)).toHaveAccessibleDescription(texto))
      expect(screen.getByLabelText('Nombre')).toHaveAttribute('aria-invalid', 'false')
      expect(screen.queryByText('detail del backend')).not.toBeInTheDocument()
    })

    it('WRK-007 va en la licencia', async () => {
      const user = userEvent.setup()
      server.use(createWorkerProblem('WRK-007', 409, 'detail del backend'))
      renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q1')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() =>
        expect(screen.getByLabelText('N.° de licencia')).toHaveAccessibleDescription(
          'Ya existe una licencia registrada con este número.',
        ),
      )
    })

    it('WRK-003 va en el tipo de documento y recarga el catálogo', async () => {
      const user = userEvent.setup()
      const catalogo: { calls?: number } = {}
      server.use(
        listDocumentTypesSequence([[DNI, CE], [DNI]], catalogo),
        createWorkerProblem('WRK-003', 400, 'detail del backend'),
      )
      renderPage()
      await listo()
      await llenarValido(user)
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'Carné de extranjería')
      await user.clear(screen.getByLabelText('Número de documento'))
      await user.type(screen.getByLabelText('Número de documento'), 'AB1234')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() =>
        expect(screen.getByLabelText('Tipo de documento')).toHaveAccessibleDescription(
          'Este tipo de documento ya no está vigente. Elige otro.',
        ),
      )
      await waitFor(() => expect(catalogo.calls).toBe(2))
      await waitFor(() =>
        expect(within(screen.getByLabelText('Tipo de documento')).queryByText('Carné de extranjería')).not.toBeInTheDocument(),
      )
      // El tipo cambió por la recarga: el número se vuelve a medir y el error del backend se queda.
      await waitFor(() =>
        expect(screen.getByLabelText('Número de documento')).toHaveAccessibleDescription(
          'El número no tiene el formato de DNI.',
        ),
      )
      expect(screen.getByLabelText('Tipo de documento')).toHaveAccessibleDescription(
        'Este tipo de documento ya no está vigente. Elige otro.',
      )
    })

    it.each([
      ['COM-003', 403, 'Tu rol no tiene permisos para esta acción.'],
      ['WRK-013', 409, 'Otra operación estaba en curso y no se pudo guardar. Intenta de nuevo.'],
    ] as const)('%s se avisa sin romper el formulario', async (code, status, texto) => {
      const user = userEvent.setup()
      server.use(createWorkerProblem(code, status, 'detail del backend'))
      const { router } = renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith(texto))
      expect(router.state.location.pathname).toBe(NUEVO)
      expect(screen.getByLabelText('Nombre')).toHaveValue('Juan')
      expect(screen.getByRole('button', { name: 'Guardar' })).toBeEnabled()
      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeEnabled()
      expect(screen.getByRole('link', { name: 'Volver a trabajadores' })).toHaveAttribute('href', WORKERS_BASE)
    })

    it('un 400 de forma pone cada mensaje en su campo, incluida la licencia', async () => {
      const user = userEvent.setup()
      server.use(
        createWorkerValidation([
          { field: 'driver.licenseNumber', message: 'Licencia rara' },
          { field: 'driver.licenseCategory', message: 'Categoría rara' },
          { field: 'firstName', message: 'Nombre raro' },
        ]),
      )
      renderPage()
      await listo()
      await llenarValido(user, 'Conductor')
      await user.type(screen.getByLabelText('N.° de licencia'), 'Q1')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(screen.getByLabelText('N.° de licencia')).toHaveAccessibleDescription('Licencia rara'))
      expect(screen.getByLabelText('Nombre')).toHaveAccessibleDescription('Nombre raro')
      expect(screen.getByLabelText('Categoría (opcional)')).toHaveAccessibleDescription('Categoría rara')
    })

    /** Lo que se reenvía es lo que se ve: el tipo retirado no vuelve a viajar. */
    it('tras WRK-003, el reenvío lleva el tipo que queda en pantalla', async () => {
      const user = userEvent.setup()
      const envios: { bodies?: WorkerRequest[] } = {}
      server.use(listDocumentTypesSequence([[DNI, CE], [DNI]], {}), createWorkerFailingOnce('WRK-003', 400, envios))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'Carné de extranjería')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() =>
        expect(within(screen.getByLabelText('Tipo de documento')).queryByText('Carné de extranjería')).not.toBeInTheDocument(),
      )

      expect(screen.getByLabelText('Tipo de documento')).toHaveValue(String(DNI.id))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() => expect(envios.bodies).toHaveLength(2))
      expect(envios.bodies?.[1].documentTypeId).toBe(DNI.id)
    })

    it.each([['WRK-005'], ['WRK-008']])('%s recarga los cargos y el retirado no vuelve a viajar', async (code) => {
      const user = userEvent.setup()
      const catalogo: { calls?: number } = {}
      const envios: { bodies?: WorkerRequest[] } = {}
      server.use(
        listRolesSequence([ROLES, ROLES.filter((role) => role.name !== 'operator')], catalogo),
        createWorkerFailingOnce(code, 400, envios),
      )
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(catalogo.calls).toBe(2))
      await waitFor(() => expect(opcionesDeCargo()).not.toContain('Operador'))
      expect(cargo()).toHaveValue('')
      expect(cargo()).toHaveAccessibleDescription(
        code === 'WRK-005'
          ? 'Este cargo ya no está vigente. Elige otro.'
          : 'La licencia no corresponde con este cargo. Revisa el cargo elegido.',
      )
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(await screen.findByText('Elige el cargo.')).toBeInTheDocument()
      expect(envios.bodies).toHaveLength(1)
    })

    /** Si falla la recarga, lo escrito no se pierde: el catálogo anterior sigue en pantalla. */
    it('si falla la recarga de un catálogo, el formulario sigue con lo escrito', async () => {
      const user = userEvent.setup()
      const recarga: { calls?: number } = {}
      server.use(createWorkerProblem('WRK-005', 400, 'detail del backend'))
      const { queryClient } = renderPage()
      await listo()
      await llenarValido(user)
      server.use(listRolesFailing(500, recarga))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(queryClient.getQueryState(workerKeys.roles())?.status).toBe('error'), { timeout: 4000 })
      expect(recarga.calls).toBe(2)
      expect(screen.getByLabelText('Nombre')).toHaveValue('Juan')
      expect(cargo()).toHaveAccessibleDescription('Este cargo ya no está vigente. Elige otro.')
    })

    it('si el catálogo recargado conserva el tipo elegido, no lo cambia', async () => {
      const user = userEvent.setup()
      const catalogo: { calls?: number } = {}
      const pasaporte = { ...DNI, id: 9, name: 'Pasaporte' }
      server.use(
        listDocumentTypesSequence([[DNI, CE], [pasaporte, CE]], catalogo),
        createWorkerProblem('WRK-003', 400, 'detail'),
      )
      renderPage()
      await listo()
      await llenarValido(user)
      await user.selectOptions(screen.getByLabelText('Tipo de documento'), 'Carné de extranjería')
      await user.clear(screen.getByLabelText('Número de documento'))
      await user.type(screen.getByLabelText('Número de documento'), 'AB1234')
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() =>
        expect(within(screen.getByLabelText('Tipo de documento')).queryByText('DNI')).not.toBeInTheDocument(),
      )
      expect(catalogo.calls).toBe(2)
      expect(screen.getByLabelText('Tipo de documento')).toHaveValue(String(CE.id))
    })

    it('si los cargos recargados conservan el elegido, no lo cambia', async () => {
      const user = userEvent.setup()
      const catalogo: { calls?: number } = {}
      server.use(
        listRolesSequence([ROLES, ROLES.filter((role) => role.name !== 'driver')], catalogo),
        createWorkerProblem('WRK-008', 400, 'detail'),
      )
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(opcionesDeCargo()).not.toContain('Conductor'))
      expect(catalogo.calls).toBe(2)
      expect(cargo()).toHaveValue('operator')
    })

    it('tras un error del backend, el foco va a su campo', async () => {
      const user = userEvent.setup()
      server.use(createWorkerProblem('WRK-002', 409, 'detail del backend'))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(screen.getByLabelText('Número de documento')).toHaveFocus())
    })

    it('tras un error del backend, un envío que no pasa la validación enfoca su propio error', async () => {
      const user = userEvent.setup()
      server.use(createWorkerProblem('WRK-002', 409, 'detail del backend'))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() => expect(screen.getByLabelText('Número de documento')).toHaveFocus())

      await user.clear(screen.getByLabelText('Nombre'))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(await screen.findByText('Indica el nombre.')).toBeInTheDocument()
      expect(screen.getByLabelText('Nombre')).toHaveFocus()
    })

    it('en el segundo intento, el foco va al campo del segundo error', async () => {
      const user = userEvent.setup()
      const envios: { bodies?: WorkerRequest[] } = {}
      server.use(createWorkerErrorsInSequence([['WRK-002', 409], ['WRK-005', 400]], envios))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() => expect(screen.getByLabelText('Número de documento')).toHaveFocus())

      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await waitFor(() => expect(envios.bodies).toHaveLength(2))
      await waitFor(() => expect(cargo()).toHaveFocus())
    })

    it.each([
      ['firstName', 'Nombre'],
      ['hireDate', 'Fecha de ingreso'],
    ])('con el teléfono y %s marcados, el foco va al primero del formulario', async (campo, etiqueta) => {
      const user = userEvent.setup()
      server.use(
        createWorkerValidation([
          { field: 'phone', message: 'Teléfono raro' },
          { field: campo, message: 'Campo raro' },
        ]),
      )
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(screen.getByLabelText('Teléfono (opcional)')).toHaveAccessibleDescription('Teléfono raro'))
      await waitFor(() => expect(screen.getByLabelText(etiqueta)).toHaveFocus())
    })

    it.each([
      ['una caída de red', createWorkerNetworkError()],
      ['un error sin campo', createWorkerProblem('COM-003', 403, 'detail del backend')],
    ])('tras %s, el foco vuelve a Guardar', async (_caso, handler) => {
      const user = userEvent.setup()
      server.use(handler)
      renderPage()
      await listo()
      await llenarValido(user)
      // Se envía con Enter desde un campo: el clic dejaría el foco en Guardar de antemano.
      await user.type(screen.getByLabelText('Nombre'), '{Enter}')

      await waitFor(() => expect(toast.error).toHaveBeenCalled())
      await waitFor(() => expect(screen.getByRole('button', { name: 'Guardar' })).toHaveFocus())
    })

    it('un 400 que nombra un campo ajeno al formulario se avisa y no se pierde', async () => {
      const user = userEvent.setup()
      server.use(createWorkerValidation([{ field: 'driver.status', message: 'Estado raro' }]))
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Datos invalidos'))
      expect(screen.queryByText('Estado raro')).not.toBeInTheDocument()
    })

    it('una caída de red se avisa con el texto genérico', async () => {
      const user = userEvent.setup()
      server.use(createWorkerNetworkError())
      renderPage()
      await listo()
      await llenarValido(user)
      await user.click(screen.getByRole('button', { name: 'Guardar' }))

      await waitFor(() =>
        expect(toast.error).toHaveBeenCalledWith('No se pudo dar de alta al trabajador. Intenta de nuevo.'),
      )
      expect(screen.getByLabelText('Nombre')).toHaveValue('Juan')
    })
  })

  describe('diseño', () => {
    it('los datos van en una columna en móvil y dos desde el ancho chico, sin desbordar', async () => {
      renderPage()
      await listo()

      const grupo = screen.getByRole('group', { name: 'Datos del trabajador' })
      expect(grupo.className).toContain('min-w-0')
      const grilla = screen.getByLabelText('Nombre').closest('.grid') as HTMLElement
      expect(grilla.className).toContain('grid-cols-1')
      expect(grilla.className).toContain('sm:grid-cols-2')
    })

    it('la licencia sigue la misma grilla y cada leyenda va dentro de su tarjeta', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')

      const licencia = screen.getByRole('group', { name: 'Licencia de conducir' })
      expect(licencia.className).toContain('min-w-0')
      const grilla = screen.getByLabelText('N.° de licencia').closest('.grid') as HTMLElement
      expect(grilla.className).toContain('grid-cols-1')
      expect(grilla.className).toContain('sm:grid-cols-2')
      for (const nombre of ['Datos del trabajador', 'Licencia de conducir']) {
        const leyenda = screen.getByRole('group', { name: nombre }).querySelector('legend') as HTMLElement
        expect(leyenda.className).toContain('float-left')
        expect(leyenda.className).toContain('w-full')
      }
    })

    it('la licencia va después de los datos y los botones al final, Cancelar antes que Guardar', async () => {
      const user = userEvent.setup()
      renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Conductor')

      const datos = screen.getByRole('group', { name: 'Datos del trabajador' })
      const licencia = screen.getByRole('group', { name: 'Licencia de conducir' })
      const cancelar = screen.getByRole('button', { name: 'Cancelar' })
      const guardar = screen.getByRole('button', { name: 'Guardar' })
      expect(datos.compareDocumentPosition(licencia) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(licencia.compareDocumentPosition(cancelar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(cancelar.compareDocumentPosition(guardar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect((guardar.parentElement as HTMLElement).className).toContain('justify-end')
    })
  })

  describe('accesibilidad', () => {
    it('sin violaciones al abrir', async () => {
      const { container } = renderPage()
      await listo()
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con la licencia abierta y con errores', async () => {
      const user = userEvent.setup()
      const { container } = renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Ayudante')
      await user.click(screen.getByRole('checkbox', { name: 'Tiene licencia' }))
      await user.click(screen.getByRole('button', { name: 'Guardar' }))
      await screen.findByText('Indica el nombre.')
      expect(await axe(container)).toHaveNoViolations()
    })

    /** La casilla sin marcar no apunta a campos que todavía no existen. */
    it('sin violaciones con un cargo de licencia opcional sin marcar', async () => {
      const user = userEvent.setup()
      const { container } = renderPage()
      await listo()
      await user.selectOptions(cargo(), 'Ayudante')

      expect(screen.getByRole('checkbox', { name: 'Tiene licencia' })).not.toHaveAttribute('aria-controls')
      expect(await axe(container)).toHaveNoViolations()
    })

    it('sin violaciones con el catálogo caído', async () => {
      server.use(listRolesError(500))
      const { container } = renderPage()
      await screen.findByRole('alert', {}, { timeout: 4000 })
      expect(await axe(container)).toHaveNoViolations()
    })
  })
})
