import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { DANGER_BUTTON, PRIMARY_BUTTON } from '../../../../shared/ui/buttonStyles'
import { ServiceExitModal } from './ServiceExitModal'
import {
  SERVICE_EXIT_REASON_MIN_LENGTH,
  STATUS_NOTE_MAX_LENGTH,
} from '../../schemas/service-status.schema'
import { server } from '../../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  changeStatusCapture,
  changeStatusConflict,
  changeStatusError,
  changeStatusNetworkError,
  changeStatusSlow,
  changeStatusResourceConflict,
  THREE_CONFLICTS,
  fakeServiceDetail,
  type ChangeStatusCaptureSink,
  fakeAdditionalResource,
} from '../../../../test/mocks/handlers/operations'
import type { UserRole } from '../../../../api'
import type { ServiceWithEtag } from '../../hooks/useService'
import {
  REOPEN_AVAILABLE_NOTE,
  REOPEN_FORCE_WARNING,
  SERVICE_EXIT_FAILURE_MESSAGE,
  type ServiceExitTransition,
} from '../../status/serviceStatusTransitions'
import { operationsKeys } from '../../queryKeys'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

const REASON = 'El cliente reprogramó la obra'

function serviceInCache(overrides: Partial<ServiceWithEtag> = {}): ServiceWithEtag {
  return {
    ...fakeServiceDetail({ status: 'IN_PROGRESS' }),
    _etag: DEFAULT_SERVICE_ETAG,
    ...overrides,
  }
}

function renderModal(
  service: ServiceWithEtag = serviceInCache(),
  transition: ServiceExitTransition = 'CANCELLED',
  role: UserRole = 'admin',
) {
  const onClose = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const view = render(<ServiceExitModal
      isOpen
      onClose={onClose}
      transition={transition}
      service={service}
      role={role}
    />, {
    wrapper,
  })
  return { ...view, onClose, queryClient, user: userEvent.setup() }
}

/** El diálogo se monta por portal en `body`: el contenedor que devuelve `render()`
 * está VACÍO, así que todo lo que lo mire pasa por `screen` o por acá. */
function dialog() {
  return screen.getByRole('dialog')
}

describe('ServiceExitModal, la forma', () => {
  it('es un diálogo con su nombre', () => {
    renderModal()

    expect(screen.getByRole('dialog', { name: 'Cancelar viaje' })).toBeInTheDocument()
  })

  it('pide el motivo y no pide fecha', () => {
    // Mandar la fecha al cancelar es un rechazo del servidor: el campo no puede existir.
    renderModal()

    expect(screen.getByLabelText(/^Motivo/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Fecha y hora/)).not.toBeInTheDocument()
  })

  it('avisa del mínimo antes de que el usuario lo pise, y topa el campo en el máximo', () => {
    // El aviso por adelantado es lo único que evita escribir el motivo entero para
    // enterarse recién al enviar. Se busca por `aria-describedby` y no por texto suelto
    // porque eso mide de paso que esté ASOCIADO al campo, que es la mitad que un lector
    // de pantalla necesita.
    renderModal()

    const field = screen.getByLabelText(/^Motivo/)
    expect(field).toHaveAttribute('maxlength', String(STATUS_NOTE_MAX_LENGTH))
    const describedIds = (field.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean)
    const texts = describedIds.map((id) => document.getElementById(id)?.textContent ?? '')

    expect(texts.join(' ')).toContain(`Mínimo ${SERVICE_EXIT_REASON_MIN_LENGTH} caracteres`)
  })

  it('rotula el texto como motivo y no como nota', () => {
    // En una transición que saca el viaje del circuito, el texto ES el motivo por el
    // que murió, y es lo único que después lo explica.
    renderModal()

    expect(screen.queryByLabelText(/Nota \(opcional\)/)).not.toBeInTheDocument()
  })

  it('nombra el viaje y el cliente sobre los que va a actuar', () => {
    // Es lo que protege de cancelar el viaje equivocado, y el código vive en el
    // encabezado, detrás del backdrop. La regex de "estado terminal" del caso de abajo
    // sigue matcheando con el código y el cliente borrados, así que no lo cubre.
    renderModal()

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/SRV-0077/)).toBeInTheDocument()
    expect(within(dialog).getByText(new RegExp(fakeServiceDetail().client.name))).toBeInTheDocument()
  })

  it('dice qué decisión se está tomando, distinta en cada salida', () => {
    // Cancelar y eliminar terminan los dos con el viaje fuera del circuito, y la
    // diferencia no está en el efecto sino en lo que el usuario afirma: uno dice que el
    // viaje ocurrió y se abortó, el otro que nunca debió existir.
    const { unmount } = renderModal(serviceInCache(), 'CANCELLED')
    expect(within(dialog()).getByText(/ocurrió y se abortó/)).toBeInTheDocument()
    unmount()

    renderModal(serviceInCache(), 'DELETED')
    expect(within(dialog()).getByText(/nunca debió existir/)).toBeInTheDocument()
  })

  it('le dice que hay vuelta atrás solo a quien puede reabrir', () => {
    // La jefatura de operaciones cancela y elimina pero está vetada de reabrir: leía una
    // promesa que su rol le niega, y en el viaje ya cancelado tampoco iba a ver el botón.
    for (const transition of ['CANCELLED', 'DELETED'] as const) {
      const { unmount } = renderModal(serviceInCache(), transition, 'admin')
      expect(within(dialog()).getByText(new RegExp(REOPEN_AVAILABLE_NOTE))).toBeInTheDocument()
      unmount()

      const conVeto = renderModal(serviceInCache(), transition, 'operations_manager')
      expect(
        within(dialog()).queryByText(new RegExp(REOPEN_AVAILABLE_NOTE)),
      ).not.toBeInTheDocument()
      conVeto.unmount()
    }
  })

  it('no le promete la vuelta atrás al viaje que tiene refuerzos', () => {
    // Un viaje con refuerzos NO se reabre (el servidor lo rechaza) y la baja de un
    // refuerzo exige el viaje en ruta, así que al salir del circuito el bloqueo queda
    // firme. La frase salía por rol, y este es justo el viaje donde no se puede cumplir.
    for (const transition of ['CANCELLED', 'DELETED'] as const) {
      const view = renderModal(
        serviceInCache({ additionalResources: [fakeAdditionalResource()] }),
        transition,
        'admin',
      )

      expect(
        within(dialog()).queryByText(new RegExp(REOPEN_AVAILABLE_NOTE)),
      ).not.toBeInTheDocument()
      view.unmount()
    }
  })

  it('no se lo dice al que ya está reabriendo', () => {
    renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED', 'admin')

    expect(
      within(dialog()).queryByText(new RegExp(REOPEN_AVAILABLE_NOTE)),
    ).not.toBeInTheDocument()
  })

  it('ya no llama terminal al estado, ahora que se puede reabrir', () => {
    // La palabra era cierta cuando cancelado no tenía salida. Con reabrir a la vista
    // significaría para el lector algo que dejó de ser verdad.
    renderModal(serviceInCache(), 'CANCELLED')

    expect(within(dialog()).queryByText(/terminal/i)).not.toBeInTheDocument()
    expect(within(dialog()).queryByText(/no se puede revertir/i)).not.toBeInTheDocument()
  })

  it('al reabrir no nombra a qué estado vuelve el viaje', () => {
    // El detalle no trae de dónde viene: el estado sale del rastro de auditoría, del
    // lado del servidor, y llega recién en la respuesta. Anticiparlo sería inventarlo.
    renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    expect(within(dialog()).getByText(/vuelve al estado que tenía antes/)).toBeInTheDocument()
    expect(within(dialog()).queryByText(/En ruta|Pendiente de inicio/)).not.toBeInTheDocument()
  })

  it('no pinta de alarma el botón que repara', () => {
    // Reabrir se alcanza desde un botón primario en la barra: que acá se volviera rojo
    // daría vuelta el color entre una pantalla y la siguiente sin que nada lo explique.
    renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    expect(screen.getByRole('button', { name: 'Reabrir viaje' }).className).toBe(PRIMARY_BUTTON)
  })

  it('rotula el motivo nombrando la acción', () => {
    const { unmount } = renderModal(serviceInCache(), 'DELETED')
    expect(screen.getByLabelText(/Motivo de la eliminación/)).toBeInTheDocument()
    unmount()

    renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')
    expect(screen.getByLabelText(/Motivo de la reapertura/)).toBeInTheDocument()
  })

  it('marca como destructivo el botón que confirma', () => {
    // Es el único indicio visual de que ese botón no es uno más, y la decisión tenía
    // su párrafo en el código y ninguna red.
    renderModal()

    // Contra la constante y no contra una clase suelta: el caso mide el ROL del botón
    // (destructivo), y sobrevive a que el tono cambie.
    expect(screen.getByRole('button', { name: 'Cancelar viaje' }).className).toBe(DANGER_BUTTON)
  })

  it('no se monta cuando está cerrado', () => {
    render(<ServiceExitModal
        isOpen={false}
        onClose={vi.fn()}
        transition="CANCELLED"
        service={serviceInCache()}
        role="admin"
      />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('nombra "Volver" al botón que cierra', () => {
    // Es la única pantalla del sistema donde "Cancelar" nombra las dos cosas a la vez.
    renderModal()

    expect(screen.getByRole('button', { name: 'Volver' })).toBeInTheDocument()
  })
})

describe('ServiceExitModal, la validación', () => {
  it('no llama al servidor con un motivo corto', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), 'muy corto')
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(
      await screen.findByText('El motivo debe tener al menos 10 caracteres'),
    ).toBeInTheDocument()
    expect(sink.bodies).toHaveLength(0)
  })

  it('acepta un motivo de exactamente el mínimo', async () => {
    // El compañero del caso anterior: sin él, un mínimo escrito de más pasa igual.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), 'Diez chars')
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
  })

  it('no acepta un motivo hecho solo de espacios', async () => {
    // Doce espacios pasan un mínimo de diez medido antes del recorte.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), '            ')
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(
      await screen.findByText('El motivo debe tener al menos 10 caracteres'),
    ).toBeInTheDocument()
    expect(sink.bodies).toHaveLength(0)
  })

  it('limpia los caracteres de control al pegarlos, antes de validar', async () => {
    // Es la capa que corre mientras se escribe. Sin ella, pegar un motivo con un
    // control invisible lo rechaza el schema con un mensaje sobre un carácter que el
    // usuario no puede ver ni encontrar.
    const { user } = renderModal()

    const field = screen.getByLabelText(/^Motivo/)
    await user.click(field)
    await user.paste('reprogramó\u0000 la obra')

    expect(field).toHaveValue('reprogramó la obra')
  })

  it('exige el motivo', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await screen.findByText('El motivo debe tener al menos 10 caracteres')
    expect(sink.bodies).toHaveLength(0)
  })
})

describe('ServiceExitModal, lo que manda', () => {
  it('manda el destino y el motivo recortado', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), `  ${REASON}  `)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]?.target).toBe('CANCELLED')
    expect(sink.bodies?.[0]?.note).toBe(REASON)
  })

  it('no manda la fecha, ni siquiera en null', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    // Se mira la CLAVE y no el valor: `toBeUndefined()` no distingue "no está" de
    // "está en null", y son dos cuerpos distintos.
    expect('dateTime' in (sink.bodies?.[0] ?? {})).toBe(false)
  })

  it('no manda la bandera de forzado', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect('force' in (sink.bodies?.[0] ?? {})).toBe(false)
  })

  it('manda el If-Match del header', async () => {
    // Acá el servidor SÍ lo exige: sin él la cancelación no procede.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.ifMatches?.[0]).toBe(DEFAULT_SERVICE_ETAG)
    // El `updatedAt` del cuerpo es el mismo instante con un cero final de menos:
    // reconstruir el header desde ahí devuelve un 412 que nadie puede explicar.
    expect(sink.ifMatches?.[0]).not.toBe(`"${fakeServiceDetail().updatedAt}"`)
  })

  it('intenta igual cuando no hay ETag, y deja que el servidor conteste', async () => {
    // Que el servidor deje de exponer el header es un problema de configuración, no un
    // estado del viaje: esconder la acción lo haría ver como si no se pudiera operar,
    // sin nada que lo explique.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal(serviceInCache({ _etag: null }))

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.ifMatches?.[0]).toBe(null)
  })

  it('no manda dos veces con un doble clic', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusSlow(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    const submit = screen.getByRole('button', { name: 'Cancelar viaje' })
    await user.click(submit)
    await user.click(submit)

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies).toHaveLength(1)
  })
})

describe('ServiceExitModal, los errores', () => {
  it.each([
    [412, 'El viaje cambió desde que abriste la pantalla.'],
    [403, 'Tu rol no puede cancelar un viaje.'],
  ])('muestra el detail del %s', async (status, detail) => {
    server.use(changeStatusError(status, { detail }))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(await screen.findByText(detail)).toBeInTheDocument()
    expect(screen.queryByText(/No se pudo cancelar el viaje/)).not.toBeInTheDocument()
  })

  it('muestra el detail del conflicto de estado', async () => {
    server.use(changeStatusConflict('OPS-004', 'El viaje ya está cancelado'))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(await screen.findByText('El viaje ya está cancelado')).toBeInTheDocument()
    // Recargar no cambia que el viaje ya esté cancelado.
    expect(screen.queryByRole('button', { name: 'Descartar y recargar' })).not.toBeInTheDocument()
  })

  it('ofrece recargar ante un 412', async () => {
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(await screen.findByRole('button', { name: 'Descartar y recargar' })).toBeInTheDocument()
  })

  it('al recargar vuelve a pedir el detalle, además de cerrar', async () => {
    // La otra mitad de la acción, que es la que el nombre del botón promete: sin la
    // invalidación, la pantalla queda con la versión vieja y el intento siguiente
    // vuelve a dar 412 sin que nadie haya tocado nada.
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user, queryClient } = renderModal()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar y recargar' }))

    expect(invalidate).toHaveBeenCalledWith({
      queryKey: operationsKeys.serviceDetail(serviceInCache().id),
    })
  })

  it('al recargar cierra el diálogo', async () => {
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user, onClose } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar y recargar' }))

    expect(onClose).toHaveBeenCalled()
  })

  it.each([
    ['CANCELLED', 'Cancelar viaje'],
    ['DELETED', 'Eliminar viaje'],
    ['REOPENED', 'Reabrir viaje'],
  ] as const)('nombra la acción que falló al %s', async (transition, boton) => {
    // Con la red caída no hay `detail` del servidor, y lo único que queda es este texto.
    // Sin un caso por transición, eliminar avisaría "No se pudo cancelar el viaje".
    server.use(changeStatusNetworkError())
    const { user } = renderModal(serviceInCache(), transition)

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: boton }))

    expect(await screen.findByText(SERVICE_EXIT_FAILURE_MESSAGE[transition])).toBeInTheDocument()
  })

  it('usa el mensaje propio solo cuando el servidor no manda nada', async () => {
    server.use(changeStatusNetworkError())
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(
      await screen.findByText('No se pudo cancelar el viaje. Intenta de nuevo.'),
    ).toBeInTheDocument()
  })

  it('al reabrir muestra la tabla de conflictos y ofrece forzar', async () => {
    // Solo puede pasar al reabrir: cancelar no libera los recursos, pero un viaje fuera
    // del circuito deja de retenerlos, así que en el medio otro viaje se los llevó.
    server.use(changeStatusResourceConflict(THREE_CONFLICTS))
    const { user } = renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))

    expect(
      await screen.findByText('Uno o más recursos ya están asignados a otro viaje.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reabrir de todos modos' })).toBeInTheDocument()
    // Y lo que se acepta al forzar, que es el dato que falta para decidir: sin él, el
    // texto de arriba promete devolver los recursos y la tabla dice que están en otro
    // viaje, sin nada que reconcilie las dos cosas.
    expect(screen.getByText(REOPEN_FORCE_WARNING)).toBeInTheDocument()
    // La tabla es la fuente de verdad: una fila por conflicto, sin resumen que se
    // desalinee con lo que se lista. Se afirma por el NOMBRE de cada recurso, que es
    // distinto en los tres, y no contando apariciones de un patrón: los códigos de viaje
    // del fixture no comparten prefijo y un conteo por patrón mide de menos.
    for (const conflicto of THREE_CONFLICTS) {
      expect(screen.getByText(conflicto.resourceName)).toBeInTheDocument()
    }
  })

  it('al forzar la reapertura manda la bandera, y solo entonces', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusResourceConflict(THREE_CONFLICTS))
    const { user } = renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))
    await screen.findByRole('button', { name: 'Reabrir de todos modos' })

    server.use(changeStatusCapture(sink))
    await user.click(screen.getByRole('button', { name: 'Reabrir de todos modos' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]?.force).toBe(true)
  })

  it('al forzar no deja el foco fuera del diálogo', async () => {
    // El botón de forzar vive dentro del aviso, y forzar lo desmonta: sin devolver el
    // foco, cae en `body` y el siguiente tabulador recorre la pantalla de atrás, que es
    // justo lo que un diálogo modal tiene que impedir.
    server.use(changeStatusResourceConflict(THREE_CONFLICTS))
    const { user } = renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))
    await screen.findByRole('button', { name: 'Reabrir de todos modos' })

    // El segundo intento falla por otra cosa, que es lo que hace desaparecer el aviso: si
    // volviera el mismo conflicto, el botón se re-renderiza y el foco no se pierde nunca.
    server.use(changeStatusError(500))
    await user.click(screen.getByRole('button', { name: 'Reabrir de todos modos' }))

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Reabrir de todos modos' })).toBeNull(),
    )
    expect(dialog().contains(document.activeElement)).toBe(true)
  })

  it('avisa del mínimo al salir del campo, sin esperar al envío', async () => {
    // Enterarse recién al confirmar obliga a escribir el motivo entero para descubrir que
    // era corto. El aviso sale del formulario, no del botón.
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), 'corto')
    await user.tab()

    expect(await within(dialog()).findByRole('alert')).toHaveTextContent(
      new RegExp(String(SERVICE_EXIT_REASON_MIN_LENGTH)),
    )
  })

  it('el contador acompaña lo que se escribe', async () => {
    // El contador le dice al usuario cuánto le queda del tope: congelado en cero mientras
    // escribe, miente sin que nada falle.
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)

    expect(
      within(dialog()).getByText(`${REASON.length}/${STATUS_NOTE_MAX_LENGTH}`),
    ).toBeInTheDocument()
  })

  it.each([
    ['CANCELLED', 'Cancelar viaje'],
    ['DELETED', 'Eliminar viaje'],
  ] as const)('no ofrece forzar al %s, aunque el conflicto traiga la tabla', async (transition, boton) => {
    // Ninguna de las dos moviliza recursos, así que el servidor rechaza la bandera: un
    // botón de forzar acá sería una promesa que el backend no cumple. Se le manda un
    // conflicto CON tabla a propósito, que es el único cuerpo capaz de armar el aviso
    // forzable: con un 409 sin `conflicts` el caso pasaba sin ejercitar esa rama.
    server.use(changeStatusResourceConflict(THREE_CONFLICTS))
    const { user } = renderModal(serviceInCache(), transition)

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: boton }))

    await screen.findByRole('alert')
    expect(screen.queryByRole('button', { name: /de todos modos/ })).not.toBeInTheDocument()
  })

  it('un error sin conflictos no arma la tabla, ni siquiera al reabrir', async () => {
    // La guarda mira si hay CONFLICTOS, no si hay error: sin ella, un 412 al reabrir
    // pintaría el aviso de conflicto en vez del genérico, y con eso perdería el botón
    // de recargar, que es lo único que saca al usuario de un 412.
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user } = renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))

    expect(await screen.findByText('El viaje cambió mientras tanto.')).toBeInTheDocument()
    // Y conserva su salida: el botón de recargar vive solo en el aviso genérico, así que
    // si el 412 se pintara como conflicto se perdería con él la invalidación y el cierre.
    expect(await screen.findByRole('button', { name: 'Descartar y recargar' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reabrir de todos modos' })).not.toBeInTheDocument()
  })

  it('el reintento saca la tabla de conflictos mientras el pedido viaja', async () => {
    // Sin la limpieza, el usuario reintenta y sigue viendo el choque anterior encima,
    // sin saber si es el de antes o uno nuevo.
    server.use(changeStatusResourceConflict(THREE_CONFLICTS))
    const { user } = renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))
    await screen.findByRole('button', { name: 'Reabrir de todos modos' })

    server.use(changeStatusSlow({}))
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))

    expect(
      screen.queryByRole('button', { name: 'Reabrir de todos modos' }),
    ).not.toBeInTheDocument()
  })

  it('al forzar retira el camino de forzar, así que no se manda dos veces', async () => {
    // Lo que impide el segundo envío no es que el botón se deshabilite: es que el aviso
    // entero se desmonta al reintentar, así que el segundo clic cae sobre un nodo que ya
    // no está. Se afirma eso, que es lo que de verdad protege; medir el conteo de cuerpos
    // pasaba igual con la guarda borrada.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusResourceConflict(THREE_CONFLICTS))
    const { user } = renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))
    await screen.findByRole('button', { name: 'Reabrir de todos modos' })

    server.use(changeStatusSlow(sink))
    const forzar = screen.getByRole('button', { name: 'Reabrir de todos modos' })
    await user.click(forzar)

    expect(screen.queryByRole('button', { name: 'Reabrir de todos modos' })).not.toBeInTheDocument()
    await waitFor(() => expect(sink.bodies).toHaveLength(1))
  })

  it('no cierra el modal ni pierde el motivo cuando falla', async () => {
    // El motivo cuesta escribirlo: perderlo obliga a redactarlo de nuevo para
    // reintentar algo que puede volver a fallar.
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user, onClose } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await screen.findByText('El viaje cambió mientras tanto.')
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/^Motivo/)).toHaveValue(REASON)
  })
})

describe('ServiceExitModal, el éxito y la accesibilidad', () => {
  it.each([
    ['CANCELLED', 'Cancelar viaje', 'SRV-0077 cancelado.'],
    ['DELETED', 'Eliminar viaje', 'SRV-0077 eliminado.'],
    ['REOPENED', 'Reabrir viaje', 'SRV-0077 reabierto.'],
  ] as const)('avisa lo que pasó de verdad al %s', async (transition, boton, aviso) => {
    // Es lo último que el usuario ve de la acción. Sin un caso por transición, el aviso
    // puede decir "cancelado" para las tres y la suite pasa entera: eliminar un viaje
    // avisaría que se canceló, que es otra cosa.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal(serviceInCache(), transition)

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: boton }))

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith(aviso))
    // Y el destino que viaja, que es lo único que decide qué le pasa al viaje: el aviso
    // sale de la tabla de textos, así que un modal cableado a un destino fijo seguiría
    // diciendo "eliminado" mientras cancela.
    expect(sink.bodies?.[0]?.target).toBe(transition)
  })

  it('avisa y cierra', async () => {
    server.use(changeStatusCapture({}, fakeServiceDetail({ status: 'CANCELLED' })))
    const { user, onClose } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(toast.success).toHaveBeenCalledWith('SRV-0077 cancelado.')
  })

  it('deshabilita el botón y dice que está cancelando', async () => {
    server.use(changeStatusSlow({}))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    const pending = await screen.findByRole('button', { name: /Cancelando/ })
    expect(pending).toBeDisabled()
    expect(screen.getByLabelText(/^Motivo/)).toBeDisabled()
  })

  it('asocia el error del motivo con su campo', async () => {
    const { user } = renderModal()

    const field = screen.getByLabelText(/^Motivo/)
    await user.type(field, 'corto')
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'))
    // Por id y no por texto: "hay un mensaje rojo" no es lo mismo que "el mensaje es
    // de ESTE campo", y un lector de pantalla solo anuncia lo segundo.
    const describedIds = (field.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean)
    const described = describedIds.map((id) => document.getElementById(id))
    const message = described.find((element) => element?.getAttribute('role') === 'alert')
    // Se afirma el ROL además del texto, igual que en el diálogo gemelo: sin el rol, el
    // mensaje existe pero no se anuncia, y las dos mitades de la regla se estaban
    // midiendo con distinta vara en cada archivo.
    expect(message).toHaveTextContent('El motivo debe tener al menos 10 caracteres')
  })

  it('arranca con el foco en el campo del motivo', async () => {
    renderModal()

    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByLabelText(/^Motivo/)),
    )
  })

  it('no deja escapar el foco del diálogo', async () => {
    const { user } = renderModal()

    const focusables = within(dialog()).getAllByRole('button').length + 3
    for (let tab = 0; tab < focusables; tab += 1) {
      await user.tab()
    }

    expect(dialog().contains(document.activeElement)).toBe(true)
  })

  it('no tiene violaciones de accesibilidad', async () => {
    // Sobre `baseElement` y nunca sobre `container`: el diálogo vive en un portal, así
    // que sobre el contenedor este caso no podría fallar.
    const { baseElement } = renderModal()

    expect(await axe(baseElement)).toHaveNoViolations()
  })

  it('tampoco las tiene con la tabla de conflictos en pantalla', async () => {
    // Es la superficie que este cambio estrena —tabla de cuatro columnas, botón de forzar
    // y el aviso de lo que se acepta— y era la única sin medir. Custodia además el
    // `aria-describedby` que cuelga el aviso del botón.
    server.use(changeStatusResourceConflict(THREE_CONFLICTS))
    const { baseElement, user } = renderModal(serviceInCache({ status: 'CANCELLED' }), 'REOPENED')

    await user.type(screen.getByLabelText(/^Motivo/), REASON)
    await user.click(screen.getByRole('button', { name: 'Reabrir viaje' }))
    const forzar = await screen.findByRole('button', { name: 'Reabrir de todos modos' })

    // El aviso se anuncia CON el botón: afuera de la región viva, un lector de pantalla
    // oía el conflicto y no lo que estaba por aceptar.
    // Por la descripción accesible y no por el texto suelto: buscarlo con `getByText` lo
    // encuentra igual sin la asociación, que es justo la mitad que un lector de pantalla
    // necesita al enfocar el botón.
    expect(forzar).toHaveAccessibleDescription(REOPEN_FORCE_WARNING)
    expect(await axe(baseElement)).toHaveNoViolations()
  })

  it('tampoco las tiene con el error de validación puesto', async () => {
    const { baseElement, user } = renderModal()

    await user.type(screen.getByLabelText(/^Motivo/), 'corto')
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))
    await screen.findByText('El motivo debe tener al menos 10 caracteres')

    expect(await axe(baseElement)).toHaveNoViolations()
  })
})
