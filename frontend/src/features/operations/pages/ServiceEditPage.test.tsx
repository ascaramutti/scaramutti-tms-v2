import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { ServiceEditPage } from './ServiceEditPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import type { ServiceDetailResponse, ServiceStatus, ServiceUpdateRequest, UserRole } from '../../../api'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import { currenciesError, currenciesOk, fakeCurrency } from '../../../test/mocks/handlers/catalogs'
import {
  DEFAULT_SERVICE_ETAG,
  ETAG_AFTER_WRITE,
  fakeServiceDetail,
  serviceDetailSequence,
  serviceDetailWithoutEtag,
  serviceDetailError,
  serviceDetailOk,
  updateServiceCapture,
  updateServiceError,
  type ChangeStatusCaptureSink,
} from '../../../test/mocks/handlers/operations'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }))

// Los espías de `toast` viven en el módulo, así que acumulan llamadas entre casos: sin
// limpiarlos, un "no se llamó" mide lo que hicieron los anteriores y no este.
beforeEach(() => {
  vi.clearAllMocks()
})

// Red del archivo: la restauración del reloj vive dentro del único caso que lo falsea, y
// el próximo que lo haga sin `finally` fugaría a todos los que siguen.
afterEach(() => {
  vi.useRealTimers()
})

const SERVICE_ID = 77
const EDIT_PATH = `/cotizaciones/operaciones/servicios/${SERVICE_ID}/editar`
const JUSTIFICATION = 'Corrijo el destino que vino mal del cliente'

const CURRENCIES = [
  fakeCurrency({ id: 1, code: 'USD', name: 'Dólares' }),
  fakeCurrency({ id: 2, code: 'PEN', name: 'Soles' }),
]

/** Sustituta del detalle que nombra el id de la ruta, para afirmar a cuál se vuelve. */
function DetalleStub() {
  const { id } = useParams()
  return <div>Detalle del servicio {id}</div>
}

function renderEditPage({ role = 'admin' as UserRole } = {}) {
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  // `refetchOnWindowFocus` en false como en producción: la identidad del formulario es una
  // clave derivada del ETag, así que un refetch por foco lo remontaría y se llevaría lo
  // que el usuario está escribiendo. La suite tiene que medir el mismo mundo que corre.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  queryClient.setQueryData(currentUserQueryKey, { ...fakeUser, role })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[EDIT_PATH]}>
          <Routes>
            <Route
              path="/cotizaciones/operaciones/servicios/:id/editar"
              element={<ServiceEditPage />}
            />
            <Route path="/cotizaciones/operaciones/servicios/:id" element={<DetalleStub />} />
            <Route path="/cotizaciones/operaciones" element={<div>Listado de servicios</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { ...view, user: userEvent.setup() }
}

/**
 * El viaje que se va a editar, servido por el detalle y con el catálogo cargado.
 *
 * Todos los campos con valores DISTINTOS entre sí, y ninguna medida nula: con dos en
 * blanco (como viene el fixture compartido), registrar un input con el nombre de otro no
 * se vería.
 */
function serveService(overrides: Partial<ServiceDetailResponse> = {}) {
  const service = fakeServiceDetail({
    tentativeDate: '2026-09-10',
    origin: 'Piura',
    destination: 'Lima — Callao',
    weightKg: 28000,
    lengthM: 12.5,
    widthM: 2.4,
    heightM: 3.6,
    price: 5800,
    currencyCode: 'PEN',
    observations: 'Carga frágil',
    ...overrides,
  })
  server.use(currenciesOk(CURRENCIES), serviceDetailOk(service))
  return service
}

/**
 * El viaje tal como vuelve de un guardado que SÍ escribió: con la versión movida.
 *
 * `updatedAt` es lo único que el servidor toca cuando escribe de verdad, y es lo que
 * distingue ese 200 del que descarta un cuerpo sin cambios (RN-OP10).
 */
function editado(overrides: Partial<ServiceDetailResponse> = {}): ServiceDetailResponse {
  return fakeServiceDetail({
    destination: 'Trujillo',
    updatedAt: '2026-08-28T20:00:00Z',
    ...overrides,
  })
}

/** Espera a que el formulario esté montado (el catálogo llega después del detalle). */
async function waitForForm() {
  return screen.findByLabelText('Motivo del cambio')
}

describe('ServiceEditPage, lo que abre', () => {
  it('precarga cada campo con SU valor, y no con el de otro', async () => {
    /*
     * Los once campos y no una muestra: probar las fábricas con la etiqueta correcta no
     * dice nada sobre con qué nombre las llama cada input. Lo que este caso mata es
     * registrar el largo donde va el ancho, o el precio donde va el peso, que compila
     * y pasa desapercibido con valores repetidos.
     */
    serveService()
    renderEditPage()

    await waitForForm()
    expect(screen.getByLabelText('Fecha tentativa')).toHaveValue('2026-09-10')
    expect(screen.getByLabelText('Origen')).toHaveValue('Piura')
    expect(screen.getByLabelText('Destino')).toHaveValue('Lima — Callao')
    expect(screen.getByLabelText('Peso (kg)')).toHaveValue(28000)
    expect(screen.getByLabelText('Largo (m)')).toHaveValue(12.5)
    expect(screen.getByLabelText('Ancho (m)')).toHaveValue(2.4)
    expect(screen.getByLabelText('Alto (m)')).toHaveValue(3.6)
    expect(screen.getByLabelText('Precio')).toHaveValue(5800)
    expect(screen.getByLabelText('Moneda')).toHaveValue('2')
    expect(screen.getByLabelText('Observaciones (opcional)')).toHaveValue('Carga frágil')
    // La justificación arranca vacía SIEMPRE: es de este cambio, no del anterior.
    expect(screen.getByLabelText('Motivo del cambio')).toHaveValue('')
  })

  it('deja la moneda del viaje seleccionada, traducida desde su código', async () => {
    // El detalle publica el código y el selector trabaja con el id: si la traducción
    // fallara en silencio, el desplegable abriría vacío y el usuario guardaría con otra
    // moneda sin notar que la cambió.
    serveService({ currencyCode: 'USD' })
    renderEditPage()

    await waitForForm()
    expect(screen.getByLabelText('Moneda')).toHaveValue('1')
  })

  it('no ofrece los campos inmutables, que el cuerpo ni siquiera lleva', async () => {
    // Cliente, ámbito y tipo de carga no se editan: si se equivocaron, el viaje se crea
    // de nuevo. Mostrarlos deshabilitados invitaría a pedir que se habiliten.
    serveService()
    renderEditPage()

    await waitForForm()
    expect(screen.queryByLabelText('Cliente')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Ámbito del viaje')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Tipo de carga')).not.toBeInTheDocument()
  })
})

describe('ServiceEditPage, las fechas reales', () => {
  it('no las ofrece en un viaje que todavía no arrancó', async () => {
    // Acá se CORRIGEN, no se fijan: el servidor rechaza con 400 la fecha de un viaje que
    // no la tiene. El bloque entero desaparece, no queda un campo gris que nada explica.
    serveService({ status: 'PENDING_START', startDateTime: null, endDateTime: null })
    renderEditPage()

    await waitForForm()
    expect(screen.queryByLabelText('Inicio real')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Fin real')).not.toBeInTheDocument()
    expect(screen.queryByText('Fechas reales')).not.toBeInTheDocument()
  })

  it('ofrece solo el inicio en un viaje en ruta', async () => {
    serveService({
      status: 'IN_PROGRESS',
      startDateTime: '2026-08-20T19:30:00Z',
      endDateTime: null,
    })
    renderEditPage()

    await waitForForm()
    // 14:30 en Lima, que es lo que el usuario tiene que ver esté donde esté.
    expect(screen.getByLabelText('Inicio real')).toHaveValue('2026-08-20T14:30')
    expect(screen.queryByLabelText('Fin real')).not.toBeInTheDocument()
  })

  it('ofrece las dos en un viaje completado', async () => {
    serveService({
      status: 'COMPLETED',
      startDateTime: '2026-08-20T19:30:00Z',
      endDateTime: '2026-08-20T23:45:00Z',
    })
    renderEditPage()

    await waitForForm()
    expect(screen.getByLabelText('Inicio real')).toHaveValue('2026-08-20T14:30')
    expect(screen.getByLabelText('Fin real')).toHaveValue('2026-08-20T18:45')
  })

  it('se mira la fecha y no el estado, como hace el servidor', async () => {
    // Un viaje llegado del sistema anterior puede estar en ruta SIN fecha de inicio. Ese
    // no se corrige por acá: el saneo va en el script del cutover.
    serveService({ status: 'IN_PROGRESS', startDateTime: null, endDateTime: null })
    renderEditPage()

    await waitForForm()
    expect(screen.queryByLabelText('Inicio real')).not.toBeInTheDocument()
  })
})

describe('ServiceEditPage, lo que manda', () => {
  it('manda lo corregido con su justificación y vuelve al detalle', async () => {
    const sink: ChangeStatusCaptureSink = {}
    serveService({ destination: 'Lima — Callao' })
    const { user } = renderEditPage()

    await waitForForm()
    // La respuesta trae la versión NUEVA, que es lo que el servidor mueve al escribir.
    server.use(updateServiceCapture(sink, editado()))
    await user.clear(screen.getByLabelText('Destino'))
    await user.type(screen.getByLabelText('Destino'), 'Trujillo')
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    const body = sink.bodies?.[0] as unknown as ServiceUpdateRequest
    expect(body.destination).toBe('Trujillo')
    expect(body.justification).toBe(JUSTIFICATION)
    // Vuelve al detalle del MISMO viaje, que es donde queda la bitácora con el motivo.
    expect(await screen.findByText(`Detalle del servicio ${SERVICE_ID}`)).toBeInTheDocument()
    expect(toast.success).toHaveBeenCalledWith('Servicio SRV-0077 actualizado.')
  })

  it('reenvía el ETag del detalle, que es lo que evita pisar a otro', async () => {
    const sink: ChangeStatusCaptureSink = {}
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    server.use(updateServiceCapture(sink, editado()))
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    await waitFor(() => expect(sink.ifMatches).toHaveLength(1))
    expect(sink.ifMatches?.[0]).toBe(DEFAULT_SERVICE_ETAG)
  })

  it('no manda nada sin justificación, y lo dice sobre el campo', async () => {
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    const sink: ChangeStatusCaptureSink = {}
    server.use(updateServiceCapture(sink))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    expect(await screen.findByText(/Explica el cambio en al menos 10/)).toBeInTheDocument()
    expect(sink.bodies ?? []).toHaveLength(0)
  })

  it('nombra el campo que falló, y no otro', async () => {
    // Las reglas salen de fábricas compartidas con el alta, que reciben la etiqueta como
    // parámetro: lo que este caso mata es llamarlas con el nombre cruzado, que compila.
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    await user.clear(screen.getByLabelText('Origen'))
    await user.clear(screen.getByLabelText('Alto (m)'))
    await user.type(screen.getByLabelText('Alto (m)'), '0')
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    expect(await screen.findByText('Indica el origen')).toBeInTheDocument()
    expect(screen.getByText('El alto debe ser mayor a 0')).toBeInTheDocument()
  })

  it('explica el 409 del viaje que salió del circuito mientras editaba', async () => {
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    server.use(updateServiceError(409, { code: 'OPS-004', detail: 'El viaje está cancelado.' }))
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    // En el aviso del formulario y no en un toast que se va: el usuario tiene el
    // formulario lleno. Lleva el `detail` del servidor, que es el que sabe qué pasó, y
    // además qué hacer, que el servidor no dice.
    const aviso = await screen.findByRole('alert')
    expect(aviso).toHaveTextContent('El viaje está cancelado.')
    expect(aviso).toHaveTextContent('Vuelve al detalle')
  })

  it('no canta guardado cuando el servidor no escribió nada', async () => {
    /*
     * El contrato descarta un cuerpo sin cambios reales: responde 200 y no escribe ni
     * auditoría, ni bitácora, ni versión. Tratarlo igual que un guardado real le dice al
     * usuario "actualizado" sobre algo que no pasó, y de paso tira su justificación sin
     * avisarle. Las dos mitades en el mismo caso: con una sola, tratar los dos 200 igual
     * pasa verde por el lado que quedó sin afirmar.
     */
    const sinCambios = serveService()
    const { user } = renderEditPage()

    await waitForForm()
    // El servidor devuelve el viaje TAL CUAL, con su versión intacta y el MISMO ETag: el
    // contrato dice que sin cambios reales tampoco se mueve.
    server.use(updateServiceCapture({}, sinCambios, DEFAULT_SERVICE_ETAG))
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('No había nada que corregir: SRV-0077 quedó igual.'))
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('canta actualizado cuando lo único que se movió es la versión', async () => {
    /*
     * La respuesta es idéntica al viaje servido SALVO `updatedAt`. Con fixtures que
     * difieren en muchos campos, discriminar por el destino o por el precio daba el mismo
     * resultado que discriminar por la versión, y este caso es el único que los separa: la
     * versión es lo que el servidor mueve cuando escribe, y lo demás puede no cambiar
     * (una corrección que solo tocó la justificación, por ejemplo).
     */
    const servido = serveService()
    const { user } = renderEditPage()

    await waitForForm()
    server.use(
      updateServiceCapture({}, { ...servido, updatedAt: '2026-08-28T20:00:00Z' }, ETAG_AFTER_WRITE),
    )
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Servicio SRV-0077 actualizado.'))
    expect(toast.info).not.toHaveBeenCalled()
  })

  it('ante un 412 explica y ofrece traer la versión actual', async () => {
    // La otra rama del aviso bloqueante, gemela de la del 409: sin este caso, borrarla
    // manda el 412 a un toast que se desvanece con el formulario lleno.
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    server.use(
      updateServiceError(412, { code: 'COM-004', detail: 'El viaje cambió mientras lo editabas.' }),
    )
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    const aviso = await screen.findByRole('alert')
    expect(aviso).toHaveTextContent('El viaje cambió mientras lo editabas.')
    // El texto dice lo que cuesta ANTES de que el usuario apriete.
    expect(aviso).toHaveTextContent('se pierde lo que escribiste')
    expect(within(aviso).getByRole('button', { name: 'Descartar y recargar' })).toBeInTheDocument()
  })

  it('descartar y recargar trae los datos del servidor y tira lo escrito', async () => {
    /*
     * El caso que faltaba, y el que destapó el defecto: sin remontar el formulario, el
     * botón refrescaba la VERSIÓN y dejaba los campos viejos, así que el siguiente
     * guardado mandaba el cuerpo de antes con un `If-Match` válido y pisaba en silencio
     * el cambio de la otra persona. O sea lo contrario de lo que el botón promete.
     *
     * Se afirma el EFECTO y no la presencia del control: que el campo traiga lo que el
     * servidor tiene ahora, y que el aviso desaparezca. Con el aviso pegado, el usuario
     * cree que sigue habiendo un conflicto.
     */
    server.use(
      currenciesOk(CURRENCIES),
      serviceDetailSequence([
        { service: fakeServiceDetail({ destination: 'Lima — Callao' }), etag: 'W/"v1"' },
        // Lo que la otra persona dejó mientras este usuario editaba.
        { service: fakeServiceDetail({ destination: 'Piura' }), etag: 'W/"v2"' },
      ]),
    )
    const { user } = renderEditPage()

    await waitForForm()
    await user.clear(screen.getByLabelText('Destino'))
    await user.type(screen.getByLabelText('Destino'), 'Trujillo')
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    server.use(updateServiceError(412, { code: 'COM-004', detail: 'El viaje cambió.' }))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await screen.findByRole('alert')

    await user.click(screen.getByRole('button', { name: 'Descartar y recargar' }))

    await waitFor(() => expect(screen.getByLabelText('Destino')).toHaveValue('Piura'))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('un error sobre un campo que no está en pantalla se dice igual', async () => {
    /*
     * El viaje no arrancó, así que el formulario no muestra las fechas reales. Si el
     * servidor igual rechaza una de ellas, marcar el campo lo manda a un nodo que no
     * existe: el usuario aprieta Guardar y no pasa nada visible, con el formulario
     * negándose a enviarse sin decir por qué. Lo que no puede ver, no lo puede corregir.
     */
    serveService({ status: 'PENDING_START', startDateTime: null, endDateTime: null })
    const { user } = renderEditPage()

    await waitForForm()
    server.use(
      updateServiceError(400, {
        code: 'COM-001',
        detail: 'El viaje todavía no tiene fecha de inicio.',
        errors: [{ field: 'startDateTime', message: 'El viaje todavía no arrancó' }],
      }),
    )
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    // El mensaje del servidor y no el de respaldo, y además que NO se haya marcado el
    // campo: sin la segunda mitad, marcar un campo invisible pasaría igual.
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('El viaje todavía no tiene fecha de inicio.'),
    )
    expect(screen.queryByText('El viaje todavía no arrancó')).toBeNull()
  })

  it('un error sobre un campo visible se marca en ese campo', async () => {
    // La mitad positiva del filtro: sin ella, dejarlo en una lista vacía se comería TODOS
    // los errores de campo del backend y los mandaría a un toast genérico, que es la
    // conducta que el filtro vino a evitar solo para los campos ocultos.
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    server.use(
      updateServiceError(400, {
        code: 'COM-001',
        detail: 'Revisa los datos del viaje.',
        errors: [{ field: 'destination', message: 'El destino no puede repetir el origen' }],
      }),
    )
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    expect(await screen.findByText('El destino no puede repetir el origen')).toBeInTheDocument()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('acota el selector de fecha real por abajo con la columna y por arriba con el ahora', async () => {
    /*
     * El `refine` del schema ya rechaza el envío; esto es la guarda del SELECTOR, que le
     * evita al usuario elegir algo que después se le va a rechazar.
     *
     * El tope superior es el AHORA de Lima y no el fin de la ventana: una fecha real es de
     * algo que ya ocurrió. El reloj se fija para que el esperado no cambie con el día en
     * que corra la suite, y el instante elegido cae en días distintos según la zona (28/08
     * 23:00 en Lima, 29/08 13:00 en Tokio, que es donde corre vitest).
     */
    // `shouldAdvanceTime` porque `findBy`/`waitFor` dependen de los timers: con el reloj
    // congelado del todo, la espera nunca resuelve y el caso muere por tiempo agotado.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    // Con 30 segundos de holgura dentro del minuto: el reloj falso avanza con el real, y
    // parado justo en el borde bastaría un segundo de lentitud para que el esperado
    // cambiara de minuto y el caso fallara sin que nada estuviera roto.
    vi.setSystemTime(new Date('2026-08-29T04:00:30Z'))
    try {
      serveService({
        status: 'COMPLETED',
        startDateTime: '2026-08-20T19:30:00Z',
        endDateTime: '2026-08-20T23:45:00Z',
      })
      renderEditPage()

      // Los DOS campos y no solo el inicio: pasarle el tope a uno y olvidarse del otro es
      // el mismo copiar y pegar contra el que el schema ya se blinda con su `it.each`.
      for (const etiqueta of ['Inicio real', 'Fin real']) {
        const campo = await screen.findByLabelText(etiqueta)
        expect(campo).toHaveAttribute('min', '1900-01-01T00:00')
        expect(campo).toHaveAttribute('max', '2026-08-28T23:00')
        // Al minuto, que es la precisión con la que este formulario trabaja.
        expect(campo).toHaveAttribute('step', '60')
      }
    } finally {
      vi.useRealTimers()
    }
  })

  it('recargar limpia el aviso aunque el viaje haya vuelto igual', async () => {
    // El 412 puede venir de un cambio que ya se revirtió, o de una versión perdida: ahí el
    // detalle vuelve IDÉNTICO, con el mismo ETag. Sin el contador en la clave, el
    // formulario no se remontaría y el aviso quedaría pegado para siempre, diciéndole al
    // usuario que hay un conflicto que ya no existe.
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    server.use(updateServiceError(412, { code: 'COM-004', detail: 'El viaje cambió.' }))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await screen.findByRole('alert')

    await user.click(screen.getByRole('button', { name: 'Descartar y recargar' }))

    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
    // Y lo escrito se fue con el remonte, que es lo que el botón promete.
    expect(screen.getByLabelText('Motivo del cambio')).toHaveValue('')
  })

  it('sin la versión del viaje no deja guardar, y dice que falta esa versión', async () => {
    /*
     * Un gateway que no expone el header ETag deja el detalle sin versión, y el contrato
     * exige `If-Match`: cada guardado sería un 412. Antes de este aviso, el usuario
     * llenaba el formulario y leía "el viaje cambió mientras lo editabas", que lo manda a
     * buscar un conflicto inexistente cuando lo que falta es configuración.
     */
    server.use(currenciesOk(CURRENCIES), serviceDetailWithoutEtag(fakeServiceDetail()))
    renderEditPage()

    await waitForForm()
    expect(await screen.findByRole('alert')).toHaveTextContent('falta la versión del viaje')
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled()
  })

  it('después de recargar, guardar manda lo del servidor y no lo que se descartó', async () => {
    // La segunda mitad, y la que mide el daño real: con el formulario sin remontar, este
    // envío llevaba 'Trujillo' con la versión nueva y borraba el trabajo ajeno.
    const sink: ChangeStatusCaptureSink = {}
    server.use(
      currenciesOk(CURRENCIES),
      serviceDetailSequence([
        { service: fakeServiceDetail({ destination: 'Lima — Callao' }), etag: 'W/"v1"' },
        { service: fakeServiceDetail({ destination: 'Piura' }), etag: 'W/"v2"' },
      ]),
    )
    const { user } = renderEditPage()

    await waitForForm()
    await user.clear(screen.getByLabelText('Destino'))
    await user.type(screen.getByLabelText('Destino'), 'Trujillo')
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    server.use(updateServiceError(412, { code: 'COM-004', detail: 'El viaje cambió.' }))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Descartar y recargar' }))
    await waitFor(() => expect(screen.getByLabelText('Destino')).toHaveValue('Piura'))

    server.use(updateServiceCapture(sink, editado()))
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    const body = sink.bodies?.[0] as unknown as ServiceUpdateRequest
    expect(body.destination).toBe('Piura')
    expect(sink.ifMatches?.[0]).toBe('W/"v2"')
  })

  it('el 409 no ofrece recargar, porque recargar no lo destraba', async () => {
    // La mitad negativa del botón: un viaje que salió del circuito sigue afuera por más
    // que se lo vuelva a pedir, así que ofrecer el botón sería prometer una salida falsa.
    serveService()
    const { user } = renderEditPage()

    await waitForForm()
    server.use(updateServiceError(409, { code: 'OPS-004', detail: 'El viaje está cancelado.' }))
    await user.type(screen.getByLabelText('Motivo del cambio'), JUSTIFICATION)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    const aviso = await screen.findByRole('alert')
    expect(within(aviso).queryByRole('button', { name: 'Descartar y recargar' })).toBeNull()
  })
})

describe('ServiceEditPage, cuando no hay nada que editar', () => {
  it.each([
    ['CANCELLED', 'cancelado'],
    ['DELETED', 'eliminado'],
  ] as const)(
    'explica que un viaje %s no se edita y ofrece volver al detalle',
    async (status: ServiceStatus, etiqueta: string) => {
      serveService({ status })
      renderEditPage()

      expect(await screen.findByText('Este viaje no se puede editar')).toBeInTheDocument()
      // La etiqueta del estado REAL: con un texto fijo, un eliminado diría "cancelado".
      expect(screen.getByText(new RegExp(`está ${etiqueta}`))).toBeInTheDocument()
      expect(screen.queryByLabelText('Motivo del cambio')).not.toBeInTheDocument()
      // El camino de vuelta lleva al detalle, que es donde vive el botón de reabrir: acá
      // no se dice quién puede hacerlo, para no escribir esa regla en dos lugares.
      expect(screen.getByRole('link', { name: 'Volver al detalle' })).toBeInTheDocument()
    },
  )

  it('no monta el formulario si el catálogo de monedas falla', async () => {
    // Sin catálogo no hay id de moneda que precargar, y abrir igual es la forma de
    // guardar con otra moneda sin notarlo. Se afirma el TEXTO y el botón, no que "hay un
    // alert": `findByRole` ya falla si no está, así que sin esto el caso era tautológico
    // y los dos mensajes de esta rama podían estar intercambiados.
    server.use(currenciesError(500), serviceDetailOk(fakeServiceDetail()))
    renderEditPage()

    const aviso = await screen.findByRole('alert')
    // El `detail` del servidor, que es el que sabe por qué falló, y no el texto de
    // respaldo: si se mostrara el propio, el usuario nunca sabría qué pasó de verdad.
    expect(aviso).toHaveTextContent('Fallo al cargar monedas')
    expect(within(aviso).getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Motivo del cambio')).not.toBeInTheDocument()
  })

  it('no monta el formulario con el catálogo vacío', async () => {
    // Sin monedas, resolver la del viaje es imposible: abrir igual termina en un throw en
    // pleno render y, sin ErrorBoundary en la app, en una pantalla en blanco.
    server.use(currenciesOk([]), serviceDetailOk(fakeServiceDetail()))
    renderEditPage()

    const aviso = await screen.findByRole('alert')
    expect(aviso).toHaveTextContent('No hay monedas configuradas.')
    expect(screen.queryByLabelText('Motivo del cambio')).not.toBeInTheDocument()
  })

  it('no monta el formulario si la moneda del viaje no está en el catálogo', async () => {
    // El caso que el hook sin filtro existe para evitar. Si igual ocurre, el usuario ve
    // un aviso que nombra la moneda, y no una pantalla en blanco.
    server.use(currenciesOk(CURRENCIES), serviceDetailOk(fakeServiceDetail({ currencyCode: 'XYZ' })))
    renderEditPage()

    const aviso = await screen.findByRole('alert')
    expect(aviso).toHaveTextContent('XYZ')
    expect(screen.queryByLabelText('Motivo del cambio')).not.toBeInTheDocument()
  })

  it('no le echa la culpa a la moneda cuando el que falla es otro dato', async () => {
    // El aviso lleva el motivo REAL: armar el formulario toca trece campos, y atribuirle
    // a la moneda cualquier fallo manda al usuario a mirar un catálogo que está bien.
    server.use(
      currenciesOk(CURRENCIES),
      serviceDetailOk(fakeServiceDetail({ startDateTime: 'no-es-una-fecha' })),
    )
    renderEditPage()

    const aviso = await screen.findByRole('alert')
    // El texto REAL y no su ausencia: una aserción negativa la satisface cualquier cadena,
    // incluido el "Invalid time value" que tira el formateador, que es del motor y en
    // inglés. Lo que hay que fijar es que el usuario lea algo en castellano.
    expect(aviso).toHaveTextContent('La fecha no-es-una-fecha del viaje no se pudo leer')
    expect(aviso).not.toHaveTextContent('moneda')
    expect(screen.queryByLabelText('Motivo del cambio')).not.toBeInTheDocument()
  })

  it('dice que no se encontró el viaje ante un 404', async () => {
    server.use(currenciesOk(CURRENCIES), serviceDetailError(404))
    renderEditPage()

    expect(await screen.findByText('No se encontró el servicio')).toBeInTheDocument()
  })
})

describe('ServiceEditPage, accesibilidad', () => {
  it('no tiene violaciones en el formulario cargado', async () => {
    serveService({
      status: 'COMPLETED',
      startDateTime: '2026-08-20T19:30:00Z',
      endDateTime: '2026-08-20T23:45:00Z',
    })
    const { container } = renderEditPage()

    await waitForForm()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones en la pantalla del viaje inmutable', async () => {
    serveService({ status: 'CANCELLED' })
    const { container } = renderEditPage()

    await screen.findByText('Este viaje no se puede editar')
    expect(await axe(container)).toHaveNoViolations()
  })
})
