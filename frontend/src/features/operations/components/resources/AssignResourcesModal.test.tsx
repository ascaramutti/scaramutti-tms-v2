import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HttpResponse, delay, http } from 'msw'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { AssignResourcesModal } from './AssignResourcesModal'
import { server } from '../../../../test/mocks/server'
import {
  DRIVERS,
  THREE_CONFLICTS,
  assignConflictThenOk,
  assignResourcesCapture,
  assignResourcesConflict,
  assignResourcesNetworkError,
  assignResourcesOk,
  assignResourcesSlow,
  driversCapture,
  driversError,
  driversList,
  fakeDriver,
  serviceOperationProblem,
  type AssignCaptureSink,
  type ServicesCaptureSink,
} from '../../../../test/mocks/handlers/operations'
import { fakeFleetUnit, fleetUnitsByKind } from '../../../../test/mocks/handlers/shared-catalogs'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

/**
 * Cuatro unidades con placas que empiezan distinto, y ningún id compartido con los
 * conductores: cruzar dos campos del cuerpo cambia el número, así que "elegí el
 * tracto en el casillero de la carreta" no puede pasar verde.
 */
const TRACTOR = fakeFleetUnit({ kind: 'TRACTOR', id: 7, plate: 'T7A-701', brand: 'Volvo' })
const OTHER_TRACTOR = fakeFleetUnit({ kind: 'TRACTOR', id: 11, plate: 'V1B-911', brand: 'Scania' })
const TRAILER = fakeFleetUnit({ kind: 'TRAILER', id: 3, plate: 'R3C-303', brand: 'Randon' })
const OTHER_TRAILER = fakeFleetUnit({ kind: 'TRAILER', id: 9, plate: 'Z9D-909', brand: 'Fameco' })
const FLEET = [TRACTOR, OTHER_TRACTOR, TRAILER, OTHER_TRAILER]

function renderModal(onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const view = render(
    <AssignResourcesModal isOpen onClose={onClose} serviceId={77} serviceCode="SRV-0077" />,
    { wrapper },
  )
  return { ...view, onClose }
}

async function pick(user: UserEvent, label: RegExp, option: string) {
  await user.click(screen.getByLabelText(label))
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(option))
}

/** Elige el conductor, el tracto y la carreta que el cuerpo espera. */
async function fillAll(user: UserEvent) {
  await pick(user, /^conductor$/i, 'Juan Pérez Huamán')
  await pick(user, /^tracto$/i, 'Tracto T7A-701')
  await pick(user, /carreta/i, 'Carreta R3C-303')
}

function submit(user: UserEvent) {
  return user.click(screen.getByRole('button', { name: 'Asignar recursos' }))
}

/**
 * Quita la selección del campo rotulado `fieldLabel`.
 *
 * Se busca DENTRO del campo y no por nombre en toda la pantalla, por dos motivos que
 * hacen ambiguas las dos rutas obvias: los tres campos elegidos tienen un botón
 * "Quitar selección" con el mismo nombre accesible, y el nombre del recurso elegido
 * también aparece en la tabla del conflicto. Las dos ambigüedades son hallazgos de
 * a11y en sí, anotados para la cola.
 */
async function clearSelection(user: UserEvent, fieldLabel: string) {
  const field = screen.getByText(fieldLabel, { selector: 'label' }).parentElement as HTMLElement
  await user.click(within(field).getByRole('button', { name: 'Quitar selección' }))
}

describe('AssignResourcesModal · catálogos', () => {
  it('pide a cada campo su propio subtipo de flota', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET, sink), driversList())
    renderModal()

    await waitFor(() => expect(sink.calls).toHaveLength(2))
    // Sin el historial de llamadas, "no pidió el subtipo" no se distingue de "no se
    // disparó la consulta".
    expect(sink.calls?.map((call) => call.get('kind')).sort()).toEqual(['TRACTOR', 'TRAILER'])
  })

  it('pide solo los conductores vigentes', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversCapture(sink))
    renderModal()

    await waitFor(() => expect(sink.calls).toHaveLength(1))
    // Se mide sobre la consulta y no sobre la lista: un padrón que ya viniera sin
    // bajas se vería igual en pantalla aunque el filtro no se mandara.
    expect(sink.params?.get('isActive')).toBe('true')
  })

  it('el campo de tracto no ofrece carretas, y el de carreta no ofrece tractos', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByLabelText(/^tracto$/i))
    let listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Tracto T7A-701')).toBeInTheDocument()
    // El negativo es el que atrapa que los dos campos compartan entrada de cache.
    expect(within(listbox).queryByText('Carreta R3C-303')).not.toBeInTheDocument()

    await user.keyboard('{Escape}')
    await user.click(screen.getByLabelText(/carreta/i))
    listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Carreta R3C-303')).toBeInTheDocument()
    expect(within(listbox).queryByText('Tracto T7A-701')).not.toBeInTheDocument()
  })

  it('ofrece los conductores no disponibles, con su estado a la vista', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByLabelText(/^conductor$/i))
    const listbox = await screen.findByRole('listbox')
    // El contrato dice que la disponibilidad NO se valida: el catálogo de estados
    // ordena la decisión del despacho, no la prohíbe. Los tres del padrón traen un
    // estado distinto, así que un campo que filtrara por disponible perdería dos.
    for (const driver of DRIVERS) {
      expect(await within(listbox).findByText(driver.fullName)).toBeInTheDocument()
    }
    // Los tres estados del enum, con sus tres etiquetas: los dos que no están
    // disponibles se leen igual a propósito (para una persona significan lo mismo) y
    // sin afirmar los dos, cambiar uno pasaría desapercibido.
    expect(within(listbox).getByText(/Q12345678 · Disponible/)).toBeInTheDocument()
    expect(within(listbox).getByText(/Q22222222 · No disponible/)).toBeInTheDocument()
    expect(within(listbox).getByText(/Q33333333 · No disponible/)).toBeInTheDocument()
  })

  it('avisa cuando el padrón de conductores no carga', async () => {
    server.use(fleetUnitsByKind(FLEET), driversError(500))
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByLabelText(/^conductor$/i))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(
      'No se pudieron cargar los conductores, y el viaje no se puede asignar sin uno.',
    )
  })
})

describe('AssignResourcesModal · validación', () => {
  it('sin conductor ni tracto no manda nada y explica los dos', async () => {
    const sink: AssignCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesCapture(sink))
    const user = userEvent.setup()
    renderModal()

    await submit(user)

    expect(await screen.findByText('Selecciona el conductor')).toBeInTheDocument()
    expect(screen.getByText('Selecciona el tracto')).toBeInTheDocument()
    // Y ninguno sobre la carreta, que es opcional y nadie tocó: ese es el síntoma
    // exacto del campo numérico vacío que llega como 0.
    expect(screen.queryByText(/selecciona la carreta/i)).not.toBeInTheDocument()
    expect(sink.bodies).toEqual([])
  })

  it('el error del conductor se anuncia y queda atado a su campo', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    renderModal()

    await submit(user)

    const field = screen.getByLabelText(/^conductor$/i)
    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'))
    const errorId = field.getAttribute('aria-describedby')
    expect(errorId).toBeTruthy()
    expect(document.getElementById(errorId as string)).toHaveTextContent(
      'Selecciona el conductor',
    )
  })

  it('el error desaparece al elegir el conductor', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    renderModal()
    await submit(user)
    expect(await screen.findByText('Selecciona el conductor')).toBeInTheDocument()

    await pick(user, /^conductor$/i, 'Juan Pérez Huamán')

    await waitFor(() =>
      expect(screen.queryByText('Selecciona el conductor')).not.toBeInTheDocument(),
    )
  })
})

describe('AssignResourcesModal · envío', () => {
  it('manda cada recurso en su propio campo', async () => {
    const sink: AssignCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesCapture(sink))
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await user.type(screen.getByLabelText(/nota/i), 'Sale a las 05:00')
    await submit(user)

    // El objeto entero, con los tres ids disjuntos: cruzar dos campos lo cambia.
    await waitFor(() =>
      expect(sink.bodies?.[0]).toEqual({
        driverId: 4,
        tractorId: 7,
        trailerId: 3,
        note: 'Sale a las 05:00',
        force: false,
      }),
    )
  })

  it('elegir la carreta no pisa el tracto', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    renderModal()

    await pick(user, /^tracto$/i, 'Tracto T7A-701')
    await pick(user, /carreta/i, 'Carreta R3C-303')

    // Las dos placas vivas al mismo tiempo, y distintas: si una pisara a la otra, el
    // texto cambia.
    expect(screen.getByText('Tracto T7A-701')).toBeInTheDocument()
    expect(screen.getByText('Carreta R3C-303')).toBeInTheDocument()
  })

  it('manda el viaje sin carreta cuando no se elige ninguna', async () => {
    const sink: AssignCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesCapture(sink))
    const user = userEvent.setup()
    renderModal()

    await pick(user, /^conductor$/i, 'Juan Pérez Huamán')
    await pick(user, /^tracto$/i, 'Tracto T7A-701')
    await submit(user)

    await waitFor(() => expect(sink.bodies?.[0].trailerId).toBeNull())
  })

  it('no manda dos veces con doble clic', async () => {
    const sink: AssignCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesSlow(sink))
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    const button = screen.getByRole('button', { name: 'Asignar recursos' })
    await user.click(button)
    await user.click(button)

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
  })

  it('al asignar avisa y cierra', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesOk())
    const user = userEvent.setup()
    const { onClose } = renderModal()

    await fillAll(user)
    await submit(user)

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(toast.success).toHaveBeenCalledWith(
      'Recursos asignados a SRV-0077. El viaje quedó pendiente de inicio.',
    )
  })
})

describe('AssignResourcesModal · conflictos', () => {
  it('un conflicto forzable muestra qué choca, en qué viaje, y deja forzar', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesConflict())
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await submit(user)

    const alert = await screen.findByRole('alert')
    // El encabezado NO resume la tabla: dice que hay conflicto y nada más. El resumen
    // del backend cuenta recursos distintos mientras la tabla lleva una fila por
    // conflicto, así que un mismo recurso en dos viajes los desalinea.
    expect(alert).toHaveTextContent('Uno o más recursos ya están asignados a otro viaje.')
    // Y lo que el resumen decía vive AHORA solo en la tabla, que es donde se afirma.
    expect(alert).not.toHaveTextContent('ya está asignado al servicio')
    // Las filas se buscan en la TABLA y no en el aviso: la región viva es solo el
    // párrafo, para que el lector de pantalla no recite las cuatro cabeceras y las N
    // filas de corrido al aparecer el conflicto.
    const table = screen.getByRole('table', { name: 'Recursos en conflicto' })
    // La región viva es SOLO el párrafo: con la tabla y el botón adentro, el lector
    // recita las cuatro cabeceras y las N filas de corrido al aparecer el conflicto.
    // Se afirma por contención y no por texto, porque `toHaveTextContent` es por
    // substring y subir el rol al recuadro entero seguiría pasando.
    expect(alert).not.toContainElement(table)
    expect(alert).not.toContainElement(
      screen.getByRole('button', { name: 'Asignar de todos modos' }),
    )
    const row = within(table).getByRole('row', { name: /Juan Pérez Huamán/ })
    // Texto exacto de la fila: con una coincidencia parcial, que falte el código del
    // viaje o su estado no se notaría.
    expect(row.textContent).toBe('ConductorJuan Pérez HuamánSRV-0042En ruta')
    expect(screen.getByRole('button', { name: 'Asignar de todos modos' })).toBeInTheDocument()
    // El modal sigue abierto: el conflicto avisa, no cancela.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('lista los tres conflictos con sus tres recursos y sus dos estados', async () => {
    server.use(
      fleetUnitsByKind(FLEET),
      driversList(),
      assignResourcesConflict([...THREE_CONFLICTS]),
    )
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await submit(user)

    await screen.findByRole('alert')
    const table = screen.getByRole('table', { name: 'Recursos en conflicto' })
    // Los rótulos se escriben a mano y NO se importan del mapa que se está probando.
    expect(within(table).getByRole('row', { name: /T7A-701/ }).textContent).toBe(
      'TractoT7A-701SRV-0100Pendiente de inicio',
    )
    expect(within(table).getByRole('row', { name: /R3C-303/ }).textContent).toBe(
      'CarretaR3C-303SRV-0311En ruta',
    )
  })

  it('forzar reenvía el MISMO cuerpo con el forzado en true', async () => {
    const sink: AssignCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), assignConflictThenOk(sink))
    const user = userEvent.setup()
    const { onClose } = renderModal()

    await fillAll(user)
    await user.type(screen.getByLabelText(/nota/i), 'Sale a las 05:00')
    await submit(user)
    await user.click(await screen.findByRole('button', { name: 'Asignar de todos modos' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(2))
    // Los dos cuerpos se comparan ENTRE SÍ y no contra literales: así queda fijo que
    // el reintento no perdió la carreta ni la nota, que es el bug real de rearmar el
    // cuerpo dentro del handler del botón.
    expect(sink.bodies?.[1]).toEqual({ ...sink.bodies?.[0], force: true })
    expect(sink.bodies?.[0].force).toBe(false)
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('cambiar de recurso descarta el conflicto y vuelve a enviar sin forzar', async () => {
    const sink: AssignCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET), driversList(), assignConflictThenOk(sink))
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await submit(user)
    expect(await screen.findByRole('button', { name: 'Asignar de todos modos' })).toBeInTheDocument()

    await clearSelection(user, 'Conductor')
    await pick(user, /^conductor$/i, 'Ana Ríos Chávez')

    // El aviso se va con la selección que lo causó: dejarlo ahí ofrecería pisar algo
    // que ya no se está pisando.
    expect(screen.queryByRole('button', { name: 'Asignar de todos modos' })).not.toBeInTheDocument()
    await submit(user)
    await waitFor(() => expect(sink.bodies).toHaveLength(2))
    // Y el segundo envío NO va forzado: el usuario cambió el recurso, no insistió.
    expect(sink.bodies?.[1]).toMatchObject({ driverId: 8, force: false })
  })

  it('un estado que no admite la acción muestra el detalle y NO ofrece forzar', async () => {
    server.use(
      fleetUnitsByKind(FLEET),
      driversList(),
      serviceOperationProblem(
        'assignment',
        'OPS-006',
        'El estado del servicio no admite esta acción',
      ),
    )
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await submit(user)

    const alert = await screen.findByRole('alert')
    // Sin tabla NO se muestra el encabezado genérico sino el texto del servidor: es
    // la contracara de la regla, y acá ese texto es toda la información que hay. Con
    // el genérico siempre, el usuario se quedaría sin saber qué rebotó.
    expect(alert).toHaveTextContent('El estado del servicio no admite esta acción')
    expect(alert).not.toHaveTextContent('Uno o más recursos')
    // El caso NEGATIVO afirmado, no supuesto: el `Problem` viaja pelado, y ofrecer
    // forzar sería ofrecer un camino que el servidor rechaza igual.
    expect(screen.queryByRole('button', { name: /de todos modos/i })).not.toBeInTheDocument()
  })

  it('un viaje cancelado muestra el detalle y tampoco ofrece forzar', async () => {
    server.use(
      fleetUnitsByKind(FLEET),
      driversList(),
      serviceOperationProblem(
        'assignment',
        'OPS-004',
        'El servicio está cancelado o eliminado y no admite cambios',
      ),
    )
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await submit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'El servicio está cancelado o eliminado y no admite cambios',
    )
    expect(screen.queryByRole('button', { name: /de todos modos/i })).not.toBeInTheDocument()
  })

  it('un recurso dado de baja muestra el detalle del backend', async () => {
    server.use(
      fleetUnitsByKind(FLEET),
      driversList(),
      serviceOperationProblem(
        'assignment',
        'COM-001',
        'El conductor indicado no existe o está inactivo',
        400,
      ),
    )
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await submit(user)

    // El 400 de negocio viaja PELADO, sin errores por campo, así que va como aviso y
    // no anclado a un campo: anclarlo exigiría un dato que el backend no manda.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'El conductor indicado no existe o está inactivo',
    )
  })

  it('una caída de red usa el mensaje propio, que es el único caso en que corresponde', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesNetworkError())
    const user = userEvent.setup()
    renderModal()

    await fillAll(user)
    await submit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No se pudieron asignar los recursos. Intenta de nuevo.',
    )
  })
})

describe('AssignResourcesModal · accesibilidad', () => {
  it('se anuncia como diálogo con su título', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    renderModal()

    expect(screen.getByRole('dialog', { name: 'Asignar recursos' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText(/^conductor$/i)).toBeInTheDocument())
  })

  it('avisa que el viaje cambia de estado antes de que lo aprieten', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    renderModal()

    // Es un efecto que el usuario tiene que saber ANTES, no descubrir después.
    expect(screen.getByText('Al asignar, el viaje pasa a pendiente de inicio.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText(/^conductor$/i)).toBeInTheDocument())
  })

  it('el primer Escape cierra el desplegable y deja el modal abierto', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    const { onClose } = renderModal()

    await user.click(screen.getByLabelText(/^conductor$/i))
    expect(await screen.findByRole('listbox')).toBeInTheDocument()
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    // Lo que hay que medir es que el modal SIGA abierto: un caso que solo afirmara
    // que el desplegable se cerró pasaría igual con el modal cerrándose detrás, que
    // es exactamente el defecto, y con él se pierde el formulario a medio llenar.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('el segundo Escape sí cierra el modal', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    const { onClose } = renderModal()

    await user.click(screen.getByLabelText(/^conductor$/i))
    await screen.findByRole('listbox')
    await user.keyboard('{Escape}')
    await user.keyboard('{Escape}')

    // La otra dirección de la guarda: consumir el Escape SIEMPRE dejaría el modal sin
    // salida por teclado.
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('con un recurso ya elegido, un solo Escape cierra el modal', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    const { onClose } = renderModal()

    // Elegido el conductor no queda ningún desplegable abierto, así que no hay capa
    // interna que consumir. (Al abrir el modal SÍ la hay: el foco entra al primer
    // campo y el desplegable se abre solo, que es el comportamiento del combobox
    // compartido.)
    await pick(user, /^conductor$/i, 'Juan Pérez Huamán')
    await user.keyboard('{Escape}')

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('no tiene violaciones mientras los catálogos cargan', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList([fakeDriver()]))
    const { baseElement } = renderModal()

    // El nombre dice lo que mide, y la precondición se AFIRMA en vez de darse por
    // sentada: el spinner del combobox solo existe mientras el catálogo está en
    // vuelo. Sin este ancla, el día que la respuesta llegue un tick antes el caso
    // escanea otro estado y el nombre miente en silencio, que es lo que ya pasó una
    // vez en este archivo.
    await waitFor(() => expect(screen.getByLabelText(/^conductor$/i)).toBeInTheDocument())
    expect(screen.getAllByLabelText('Buscando').length).toBeGreaterThan(0)
    expect(await axe(baseElement)).toHaveNoViolations()
    // Se deja asentar lo que quedó en vuelo, para no resolver tras el desmontaje.
    await screen.findByText('Juan Pérez Huamán')
  })

  it('no tiene violaciones con el desplegable abierto y sin coincidencias', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList([]))
    const user = userEvent.setup()
    const { baseElement } = renderModal()

    await user.click(screen.getByLabelText(/^conductor$/i))
    const empty = await screen.findByText('No se encontraron conductores.')

    // Es el estado que motivó sacar el aviso de adentro del listbox, y hasta acá
    // NINGÚN test del repo escaneaba un desplegable abierto. Se afirma además que el
    // aviso quedó FUERA del listbox y que éste no ofrece opciones: devolverlo adentro
    // reintroduce las dos violaciones que el arreglo mató.
    const listbox = screen.getByRole('listbox')
    expect(listbox).not.toContainElement(empty)
    expect(within(listbox).queryAllByRole('option')).toHaveLength(0)
    // Se anuncia: fuera del listbox no hay ningún camino por el que un lector de
    // pantalla llegue a este texto, así que sin la región viva quien busca y no
    // encuentra nada no se entera.
    expect(empty).toHaveAttribute('role', 'status')
    expect(await axe(baseElement)).toHaveNoViolations()
  })

  it('no dice que no hay conductores mientras los está cargando', async () => {
    server.use(
      fleetUnitsByKind(FLEET),
      http.get('http://localhost:8080/api/v1/drivers', async () => {
        await delay(40)
        return HttpResponse.json(DRIVERS)
      }),
    )
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByLabelText(/^conductor$/i))

    // Con el padrón en vuelo la lista está vacía, pero eso no es "no hay ninguno":
    // afirmarlo mientras carga es mentirle al usuario, y con el padrón real detrás
    // de una VPN la ventana no es instantánea.
    expect(screen.queryByText('No se encontraron conductores.')).not.toBeInTheDocument()
    expect(await screen.findByText('Juan Pérez Huamán')).toBeInTheDocument()
  })

  it('con el desplegable poblado solo queda la deuda conocida del combobox', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList())
    const user = userEvent.setup()
    const { baseElement } = renderModal()

    await user.click(screen.getByLabelText(/^conductor$/i))
    await screen.findByText('Juan Pérez Huamán')

    // `nested-interactive` se apaga a propósito y con nombre: cada opción del
    // `Combobox` compartido envuelve un `button`, que es una deuda preexistente de
    // ese componente y se arregla en su propio cambio (mueve los manejadores al
    // `li` y suma `aria-activedescendant`, y lo consumen diez campos de tres
    // módulos). Es un nodo por opción del desplegable: tres con este padrón.
    //
    // Se apaga UNA regla y se escanea todo lo demás, en vez de no escanear el
    // estado: así el hueco queda a la vista y cualquier violación NUEVA del
    // desplegable poblado rompe.
    expect(
      await axe(baseElement, { rules: { 'nested-interactive': { enabled: false } } }),
    ).toHaveNoViolations()
  })

  it('no tiene violaciones con el conflicto en pantalla', async () => {
    server.use(fleetUnitsByKind(FLEET), driversList(), assignResourcesConflict())
    const user = userEvent.setup()
    const { baseElement } = renderModal()

    await fillAll(user)
    await submit(user)
    await screen.findByRole('alert')

    // El estado de error es el que más HTML nuevo mete y el que nadie mira.
    expect(await axe(baseElement)).toHaveNoViolations()
  })
})
