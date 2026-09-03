import type { ReactNode } from 'react'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { ServiceProgressModal } from './ServiceProgressModal'
import { STATUS_NOTE_MAX_LENGTH } from '../../schemas/service-status.schema'
import { server } from '../../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  changeStatusCapture,
  changeStatusConflict,
  changeStatusError,
  changeStatusNetworkError,
  changeStatusSlow,
  fakeServiceDetail,
  type ChangeStatusCaptureSink,
} from '../../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from '../../hooks/useService'
import type { ServiceProgressTransition } from '../../status/serviceStatusTransitions'
import { operationsKeys } from '../../queryKeys'
import { buttonClasses } from '../../../../shared/ui/buttonClasses'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

/**
 * La zona del proceso se fija lejos de Lima. Bajo Lima, la conversión correcta y la que
 * lee el reloj del navegador dan lo mismo, y los casos de abajo no distinguirían una de
 * la otra.
 *
 * 25/08 02:30 UTC = 24/08 21:30 en Lima y 25/08 11:30 en Tokio: tres valores distintos,
 * en dos días distintos.
 */
/** Lejos de Lima y del signo opuesto: en los instantes que estos casos usan, las
 * dos zonas están en días distintos, así que un error de zona no puede disfrazarse
 * de un error de minutos.
 *
 * Respeta `FORCE_TZ`, igual que el config: el pin de acá existe por si alguien saca
 * la línea de allá, no para anular la vía de escape. Sin esto,
 * `FORCE_TZ=America/Lima npm test` dejaba corriendo en Tokio justo a los archivos
 * que uno querría ver en Lima, y el guardián de abajo pasaba por el pin y no por
 * la zona real. */
const TEST_TIME_ZONE = process.env.FORCE_TZ ?? 'Asia/Tokyo'
const ORIGINAL_TZ = process.env.TZ
const NOW = new Date('2026-08-25T02:30:00Z')
const NOW_IN_LIMA = '2026-08-24T21:30'
const NOW_AS_INSTANT = '2026-08-25T02:30:00.000Z'

beforeAll(() => {
  process.env.TZ = TEST_TIME_ZONE
})

afterAll(() => {
  process.env.TZ = ORIGINAL_TZ
})

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

function serviceInCache(overrides: Partial<ServiceWithEtag> = {}): ServiceWithEtag {
  return {
    ...fakeServiceDetail({ status: 'PENDING_START' }),
    _etag: DEFAULT_SERVICE_ETAG,
    ...overrides,
  }
}

function renderModal(
  transition: ServiceProgressTransition = 'IN_PROGRESS',
  service: ServiceWithEtag = serviceInCache(),
) {
  const onClose = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const view = render(
    <ServiceProgressModal isOpen onClose={onClose} transition={transition} service={service} />,
    { wrapper },
  )
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  return { ...view, onClose, user, queryClient }
}

/** El diálogo se monta por portal en `body`, así que el contenedor que devuelve
 * `render()` está VACÍO. Todo lo que mire el modal pasa por acá o por `screen`. */
function dialog() {
  return screen.getByRole('dialog')
}

describe('ServiceProgressModal, la forma', () => {
  it('titula el diálogo según la transición', () => {
    // Por ROL y nombre accesible: buscar el título por texto pasaría con un div sin rol.
    renderModal('IN_PROGRESS')

    expect(screen.getByRole('dialog', { name: 'Iniciar viaje' })).toBeInTheDocument()
  })

  it('titula distinto al finalizar', () => {
    renderModal('COMPLETED')

    expect(screen.getByRole('dialog', { name: 'Finalizar viaje' })).toBeInTheDocument()
  })

  it.each([
    ['IN_PROGRESS', 'Iniciar viaje'],
    ['COMPLETED', 'Finalizar viaje'],
  ] as const)('el botón que confirma (%s) es la acción principal, no la destructiva', (transition, etiqueta) => {
    // Su gemelo, el modal de salida, tiene esta red desde este PR; este no la tenía, y
    // pintar de rojo el confirmar de AVANZAR un viaje dejaba los 41 casos del archivo en
    // verde. El rojo está reservado a lo que saca el viaje del circuito.
    renderModal(transition)
    expect(screen.getByRole('button', { name: etiqueta }).className).toBe(
      buttonClasses({ variant: 'primary' }),
    )
  })

  it('rotula el campo de fecha según la transición', () => {
    const { unmount } = renderModal('IN_PROGRESS')
    expect(screen.getByLabelText('Fecha y hora de inicio')).toBeInTheDocument()
    unmount()

    renderModal('COMPLETED')
    expect(screen.getByLabelText('Fecha y hora de fin')).toBeInTheDocument()
  })

  it('precarga la fecha con la hora de Lima y no con la del navegador', () => {
    renderModal('IN_PROGRESS')

    const field = screen.getByLabelText('Fecha y hora de inicio')
    expect(field).toHaveValue(NOW_IN_LIMA)
    // La hora de pared de Tokio y el recorte de toISOString, escritos literales.
    expect(field).not.toHaveValue('2026-08-25T11:30')
    expect(field).not.toHaveValue('2026-08-25T02:30')
  })

  it('topa el selector en el ahora de Lima y lo mueve al minuto', () => {
    renderModal('IN_PROGRESS')

    const field = screen.getByLabelText('Fecha y hora de inicio')
    expect(field).toHaveAttribute('max', NOW_IN_LIMA)
    // Y el par del caso que afirma el `aria-invalid` con el error puesto: sin esto, el
    // campo puede nacer anunciado como inválido y nadie lo nota.
    expect(field).not.toHaveAttribute('aria-invalid')
    // El paso explícito. Hoy coincide con el default del control, así que lo que este
    // caso ata es que la precisión la fije este formulario y no un default ajeno.
    expect(field).toHaveAttribute('step', '60')
  })

  it('no se monta cuando está cerrado', () => {
    // El formulario congela sus valores al montar, y el suyo incluye la hora actual:
    // montado de entrada, precargaría la hora en que se abrió la pantalla.
    render(<ServiceProgressModal isOpen={false} onClose={vi.fn()} transition="IN_PROGRESS" service={serviceInCache()} />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('deja la nota opcional y topada en el máximo', () => {
    renderModal('IN_PROGRESS')

    const note = screen.getByLabelText(/Nota \(opcional\)/)
    expect(note).toBeInTheDocument()
    // El tope duro del campo, que es lo que impide llegar al rechazo del schema. El
    // `.max()` está medido aparte, pero nada ataba que el campo lo aplicara.
    expect(note).toHaveAttribute('maxlength', String(STATUS_NOTE_MAX_LENGTH))
  })

  it('anuncia junto al campo en qué zona se interpreta la hora', () => {
    // Es el dato del que depende que el registro quede bien, y sin la asociación un
    // lector de pantalla lee el rótulo y el valor, y nada más.
    renderModal('IN_PROGRESS')

    const field = screen.getByLabelText('Fecha y hora de inicio')
    const describedIds = (field.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean)
    const texts = describedIds.map((id) => document.getElementById(id)?.textContent ?? '')

    expect(texts.join(' ')).toContain('Hora de Perú')
  })
})

describe('ServiceProgressModal, lo que manda', () => {
  it('manda la fecha precargada como el instante de Lima', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]?.dateTime).toBe(NOW_AS_INSTANT)
    // El instante que daría leer el texto con el reloj del navegador, literal.
    expect(sink.bodies?.[0]?.dateTime).not.toBe(new Date(NOW_IN_LIMA).toISOString())
  })

  it('manda la hora que el usuario editó y no la precargada', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS')

    const field = screen.getByLabelText('Fecha y hora de inicio')
    await user.clear(field)
    await user.type(field, '2026-08-24T18:00')
    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    // Un instante distinto del del caso anterior: dos aciertos por casualidad con el
    // mismo desplazamiento serían mucha casualidad.
    expect(sink.bodies?.[0]?.dateTime).toBe('2026-08-24T23:00:00.000Z')
  })

  it('manda el destino de la transición que abrió el modal', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('COMPLETED')

    await user.click(screen.getByRole('button', { name: 'Finalizar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]?.target).toBe('COMPLETED')
  })

  it('manda el If-Match en el header y no dentro del cuerpo', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    // El servidor no exige el header al iniciar: se manda igual, por decisión, y eso
    // es justamente lo que se cae primero si nadie lo mide.
    expect(sink.ifMatches?.[0]).toBe(DEFAULT_SERVICE_ETAG)
    expect('ifMatch' in (sink.bodies?.[0] ?? {})).toBe(false)
  })

  it('manda la nota recortada', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS')

    await user.type(screen.getByLabelText(/Nota \(opcional\)/), '  Salió con demora  ')
    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]?.note).toBe('Salió con demora')
  })

  it('manda la nota vacía como null', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]?.note).toBe(null)
  })

  it('sigue mandando el pedido cuando no hay ETag, sin el header', async () => {
    // Que el servidor deje de exponer el ETag es un problema de configuración, no un
    // estado del viaje: esconder la acción lo haría ver como si no se pudiera operar.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS', serviceInCache({ _etag: null }))

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.ifMatches?.[0]).toBe(null)
  })

  it('no manda dos veces con un doble clic', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusSlow(sink))
    const { user } = renderModal('IN_PROGRESS')

    const submit = screen.getByRole('button', { name: 'Iniciar viaje' })
    await user.click(submit)
    await user.click(submit)

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    // El segundo pedido volvería como un conflicto de estado que el usuario no provocó.
    expect(sink.bodies).toHaveLength(1)
  })
})

describe('ServiceProgressModal, la validación', () => {
  it('no llama al servidor con una fecha futura en Lima', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS')

    const field = screen.getByLabelText('Fecha y hora de inicio')
    await user.clear(field)
    // Un minuto adelante en Lima; en Tokio ese instante ya pasó hace catorce horas.
    await user.type(field, '2026-08-24T21:31')
    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    expect(await screen.findByText('La fecha no puede estar en el futuro')).toBeInTheDocument()
    expect(sink.bodies).toHaveLength(0)
  })

  it('asocia el error de la fecha con su campo', async () => {
    const { user } = renderModal('IN_PROGRESS')

    const field = screen.getByLabelText('Fecha y hora de inicio')
    await user.clear(field)
    await user.type(field, '2026-08-24T21:31')
    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'))
    // Se recorren los ids de la descripción: "hay un mensaje rojo en la pantalla" no
    // es lo mismo que "el mensaje pertenece a ESTE campo", y un lector de pantalla
    // solo anuncia lo segundo. Se recorre la lista y no se toma el atributo entero
    // porque el campo describe también su texto de ayuda: afirmar sobre un id único
    // ataría el caso a que la descripción tenga exactamente un elemento.
    const describedIds = (field.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean)
    const described = describedIds.map((id) => document.getElementById(id))
    const message = described.find((element) => element?.getAttribute('role') === 'alert')
    expect(message).toHaveTextContent('La fecha no puede estar en el futuro')
  })

  it('limpia los caracteres de control al pegarlos en la nota', async () => {
    // Es la capa que corre mientras se escribe, y el gemelo de cancelar ya la tenía
    // medida. Sin ella, pegar un texto con un control invisible lo rechaza el schema
    // con un mensaje sobre un carácter que el usuario no puede ver ni encontrar.
    const { user } = renderModal('IN_PROGRESS')

    const note = screen.getByLabelText(/Nota \(opcional\)/)
    await user.click(note)
    await user.paste('salió\u0000 con escolta')

    expect(note).toHaveValue('salió con escolta')
  })

  it('acepta la fecha precargada sin tocarla', async () => {
    // El compañero del caso de arriba: sin él, una guarda que rechace todo pasaría la
    // suite de validación entera.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
  })
})

describe('ServiceProgressModal, los errores', () => {
  it.each([
    [412, 'El viaje cambió desde que abriste la pantalla.'],
    [400, 'La fecha de fin no puede ser anterior a la de inicio'],
    [403, 'Tu rol no puede pedir esa transición.'],
  ])('muestra el detail que mandó el servidor en el %s', async (status, detail) => {
    server.use(changeStatusError(status, { detail }))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    expect(await screen.findByText(detail)).toBeInTheDocument()
    // Y no ADEMÁS el genérico: mostrar los dos no es mostrar el del servidor.
    expect(screen.queryByText(/No se pudo cambiar el estado del viaje/)).not.toBeInTheDocument()
  })

  it.each([
    ['OPS-001', 'No se puede pasar de "Completado" a "En ruta"'],
    ['OPS-004', 'El viaje está cancelado y no admite cambios'],
    ['OPS-009', 'El viaje no tiene conductor y tracto asignados'],
  ] as const)('muestra el detail del conflicto %s', async (code, detail) => {
    server.use(changeStatusConflict(code, detail))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    expect(await screen.findByText(detail)).toBeInTheDocument()
    expect(screen.queryByText(/No se pudo cambiar el estado del viaje/)).not.toBeInTheDocument()
  })

  it('ofrece recargar solo ante un 412', async () => {
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    expect(await screen.findByRole('button', { name: 'Descartar y recargar' })).toBeInTheDocument()
  })

  it('al recargar vuelve a pedir el detalle, además de cerrar', async () => {
    // La otra mitad de la acción, que es la que el nombre del botón promete: sin la
    // invalidación, la pantalla queda con la versión vieja y la transición siguiente
    // vuelve a dar 412 sin que nadie haya tocado nada.
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user, queryClient } = renderModal('IN_PROGRESS')
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar y recargar' }))

    expect(invalidate).toHaveBeenCalledWith({
      queryKey: operationsKeys.serviceDetail(serviceInCache().id),
    })
  })

  it('al recargar cierra el diálogo', async () => {
    // Sin esto, apretar el botón no producía ningún cambio visible: el mismo cartel
    // rojo y el mismo formulario, y el usuario tenía que adivinar que ya podía
    // reintentar.
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user, onClose } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar y recargar' }))

    expect(onClose).toHaveBeenCalled()
  })

  it('no ofrece recargar ante un conflicto de estado', async () => {
    // Recargar no cambia que desde ese estado no se pueda llegar al que se pidió.
    server.use(changeStatusConflict('OPS-001', 'No se puede pasar de "Completado" a "En ruta"'))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await screen.findByText('No se puede pasar de "Completado" a "En ruta"')
    expect(screen.queryByRole('button', { name: 'Descartar y recargar' })).not.toBeInTheDocument()
  })

  it('usa el mensaje propio solo cuando el servidor no manda nada', async () => {
    // El único caso en que inventar el texto es lo correcto, y es lo que le da sentido
    // a los "sin genérico" de los casos anteriores.
    server.use(changeStatusNetworkError())
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    expect(
      await screen.findByText('No se pudo cambiar el estado del viaje. Intenta de nuevo.'),
    ).toBeInTheDocument()
  })

  it('no cierra el modal ni pierde la nota cuando el pedido falla', async () => {
    server.use(changeStatusConflict('OPS-004', 'El viaje está cancelado y no admite cambios'))
    const { user, onClose } = renderModal('IN_PROGRESS')

    await user.type(screen.getByLabelText(/Nota \(opcional\)/), 'Salida demorada por lluvia')
    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await screen.findByText('El viaje está cancelado y no admite cambios')
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/Nota \(opcional\)/)).toHaveValue('Salida demorada por lluvia')
  })

  it('anuncia el error del servidor con rol de alerta', async () => {
    server.use(changeStatusConflict('OPS-001', 'No se puede pasar de "Completado" a "En ruta"'))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    // Se busca POR el rol y después se mira el texto adentro: afirmar el texto sobre
    // el contenedor pasaría igual con el rol borrado.
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('No se puede pasar de "Completado" a "En ruta"')
  })
})

describe('ServiceProgressModal, el éxito', () => {
  it('avisa nombrando la acción y cierra', async () => {
    server.use(changeStatusCapture({}))
    const { user, onClose } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    // Nombra el viaje y qué le pasó: "estado actualizado" no distingue haber iniciado
    // de haber finalizado.
    expect(toast.success).toHaveBeenCalledWith('SRV-0077 iniciado. El viaje está en ruta.')
  })

  it('avisa distinto al finalizar', async () => {
    server.use(changeStatusCapture({}, fakeServiceDetail({ status: 'COMPLETED' })))
    const { user } = renderModal('COMPLETED', serviceInCache({ status: 'IN_PROGRESS' }))

    await user.click(screen.getByRole('button', { name: 'Finalizar viaje' }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('SRV-0077 finalizado.'))
  })
})

describe('ServiceProgressModal, accesibilidad', () => {
  it('deshabilita el botón y dice que está enviando', async () => {
    server.use(changeStatusSlow({}))
    const { user } = renderModal('IN_PROGRESS')

    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))

    // Las dos mitades: deshabilitado sin cambiar el texto no le dice al usuario que
    // pasó algo; cambiar el texto sin deshabilitar deja pasar el doble envío.
    const pending = await screen.findByRole('button', { name: /Iniciando/ })
    expect(pending).toBeDisabled()
    // Y el formulario entero se congela, no solo el botón: un campo que sigue
    // aceptando texto mientras el pedido viaja promete una edición que no se guarda.
    expect(screen.getByLabelText(/Nota \(opcional\)/)).toBeDisabled()
    const dateField = screen.getByLabelText('Fecha y hora de inicio')
    expect(dateField).toBeDisabled()
    // Y se VE apagado, con las mismas clases que el textarea de al lado: medir solo el
    // atributo dejaba pasar un campo que no acepta escritura pero conserva aspecto de
    // editable, que es el síntoma que esta línea existe para evitar.
    expect(dateField).toHaveClass('bg-slate-50')
  })

  it('arranca con el foco en el campo de fecha', async () => {
    renderModal('IN_PROGRESS')

    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByLabelText('Fecha y hora de inicio')),
    )
  })

  it('no deja escapar el foco del diálogo', async () => {
    const { user } = renderModal('IN_PROGRESS')

    const focusables = within(dialog()).getAllByRole('button').length + 2
    for (let tab = 0; tab < focusables + 2; tab += 1) {
      await user.tab()
    }

    // Se afirma la CONTENCIÓN y no un elemento puntual: así el caso sobrevive a que se
    // agregue un campo y sigue midiendo lo mismo.
    expect(dialog().contains(document.activeElement)).toBe(true)
  })

  it.each(['IN_PROGRESS', 'COMPLETED'] as const)('no tiene violaciones de axe en %s', async (transition) => {
    // Sobre `baseElement` y NUNCA sobre `container`: el diálogo se monta por portal en
    // `body`, así que el contenedor que devuelve `render()` está vacío y este caso no
    // podría fallar nunca.
    const { baseElement } = renderModal(transition)

    expect(await axe(baseElement)).toHaveNoViolations()
  })

  it('tampoco tiene violaciones con un error de validación puesto', async () => {
    // Un `aria-describedby` que apunte a un id inexistente solo aparece con el error.
    const { baseElement, user } = renderModal('IN_PROGRESS')

    const field = screen.getByLabelText('Fecha y hora de inicio')
    await user.clear(field)
    await user.type(field, '2026-08-24T21:31')
    await user.click(screen.getByRole('button', { name: 'Iniciar viaje' }))
    await screen.findByText('La fecha no puede estar en el futuro')

    expect(await axe(baseElement)).toHaveNoViolations()
  })
})
