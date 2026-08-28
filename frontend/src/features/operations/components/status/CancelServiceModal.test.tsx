import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { axe } from 'vitest-axe'
import { DANGER_BUTTON } from '../../../../shared/ui/buttonStyles'
import { CancelServiceModal } from './CancelServiceModal'
import {
  CANCEL_REASON_MIN_LENGTH,
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
  fakeServiceDetail,
  type ChangeStatusCaptureSink,
} from '../../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from '../../hooks/useService'
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

function renderModal(service: ServiceWithEtag = serviceInCache()) {
  const onClose = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const view = render(<CancelServiceModal isOpen onClose={onClose} service={service} />, {
    wrapper,
  })
  return { ...view, onClose, queryClient, user: userEvent.setup() }
}

/** El diálogo se monta por portal en `body`: el contenedor que devuelve `render()`
 * está VACÍO, así que todo lo que lo mire pasa por `screen` o por acá. */
function dialog() {
  return screen.getByRole('dialog')
}

describe('CancelServiceModal, la forma', () => {
  it('es un diálogo con su nombre', () => {
    renderModal()

    expect(screen.getByRole('dialog', { name: 'Cancelar viaje' })).toBeInTheDocument()
  })

  it('pide el motivo y no pide fecha', () => {
    // Mandar la fecha al cancelar es un rechazo del servidor: el campo no puede existir.
    renderModal()

    expect(screen.getByLabelText(/Motivo de la cancelación/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Fecha y hora/)).not.toBeInTheDocument()
  })

  it('avisa del mínimo antes de que el usuario lo pise, y topa el campo en el máximo', () => {
    // El aviso por adelantado es lo único que evita escribir el motivo entero para
    // enterarse recién al enviar. Se busca por `aria-describedby` y no por texto suelto
    // porque eso mide de paso que esté ASOCIADO al campo, que es la mitad que un lector
    // de pantalla necesita.
    renderModal()

    const field = screen.getByLabelText(/Motivo de la cancelación/)
    expect(field).toHaveAttribute('maxlength', String(STATUS_NOTE_MAX_LENGTH))
    const describedIds = (field.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean)
    const texts = describedIds.map((id) => document.getElementById(id)?.textContent ?? '')

    expect(texts.join(' ')).toContain(`Mínimo ${CANCEL_REASON_MIN_LENGTH} caracteres`)
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

  it('dice que el estado es terminal sin prometer que no se revierte', () => {
    // La reapertura existe en el contrato y llega en su propio cambio: escribir hoy
    // que no se revierte sería dejar puesta una frase que va a volverse falsa.
    renderModal()

    expect(within(dialog()).getByText(/estado\s+terminal/)).toBeInTheDocument()
    expect(within(dialog()).queryByText(/no se puede revertir/i)).not.toBeInTheDocument()
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
    render(<CancelServiceModal isOpen={false} onClose={vi.fn()} service={serviceInCache()} />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('nombra "Volver" al botón que cierra', () => {
    // Es la única pantalla del sistema donde "Cancelar" nombra las dos cosas a la vez.
    renderModal()

    expect(screen.getByRole('button', { name: 'Volver' })).toBeInTheDocument()
  })
})

describe('CancelServiceModal, la validación', () => {
  it('no llama al servidor con un motivo corto', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), 'muy corto')
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

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), 'Diez chars')
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
  })

  it('no acepta un motivo hecho solo de espacios', async () => {
    // Doce espacios pasan un mínimo de diez medido antes del recorte.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), '            ')
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

    const field = screen.getByLabelText(/Motivo de la cancelación/)
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

describe('CancelServiceModal, lo que manda', () => {
  it('manda el destino y el motivo recortado', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), `  ${REASON}  `)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]?.target).toBe('CANCELLED')
    expect(sink.bodies?.[0]?.note).toBe(REASON)
  })

  it('no manda la fecha, ni siquiera en null', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
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

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect('force' in (sink.bodies?.[0] ?? {})).toBe(false)
  })

  it('manda el If-Match del header', async () => {
    // Acá el servidor SÍ lo exige: sin él la cancelación no procede.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
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

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.ifMatches?.[0]).toBe(null)
  })

  it('no manda dos veces con un doble clic', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusSlow(sink))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    const submit = screen.getByRole('button', { name: 'Cancelar viaje' })
    await user.click(submit)
    await user.click(submit)

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies).toHaveLength(1)
  })
})

describe('CancelServiceModal, los errores', () => {
  it.each([
    [412, 'El viaje cambió desde que abriste la pantalla.'],
    [403, 'Tu rol no puede cancelar un viaje.'],
  ])('muestra el detail del %s', async (status, detail) => {
    server.use(changeStatusError(status, { detail }))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(await screen.findByText(detail)).toBeInTheDocument()
    expect(screen.queryByText(/No se pudo cancelar el viaje/)).not.toBeInTheDocument()
  })

  it('muestra el detail del conflicto de estado', async () => {
    server.use(changeStatusConflict('OPS-004', 'El viaje ya está cancelado'))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(await screen.findByText('El viaje ya está cancelado')).toBeInTheDocument()
    // Recargar no cambia que el viaje ya esté cancelado.
    expect(screen.queryByRole('button', { name: 'Descartar y recargar' })).not.toBeInTheDocument()
  })

  it('ofrece recargar ante un 412', async () => {
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
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

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar y recargar' }))

    expect(invalidate).toHaveBeenCalledWith({
      queryKey: operationsKeys.serviceDetail(serviceInCache().id),
    })
  })

  it('al recargar cierra el diálogo', async () => {
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user, onClose } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar y recargar' }))

    expect(onClose).toHaveBeenCalled()
  })

  it('usa el mensaje propio solo cuando el servidor no manda nada', async () => {
    server.use(changeStatusNetworkError())
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    expect(
      await screen.findByText('No se pudo cancelar el viaje. Intenta de nuevo.'),
    ).toBeInTheDocument()
  })

  it('no cierra el modal ni pierde el motivo cuando falla', async () => {
    // El motivo cuesta escribirlo: perderlo obliga a redactarlo de nuevo para
    // reintentar algo que puede volver a fallar.
    server.use(changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' }))
    const { user, onClose } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await screen.findByText('El viaje cambió mientras tanto.')
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/Motivo de la cancelación/)).toHaveValue(REASON)
  })
})

describe('CancelServiceModal, el éxito y la accesibilidad', () => {
  it('avisa y cierra', async () => {
    server.use(changeStatusCapture({}, fakeServiceDetail({ status: 'CANCELLED' })))
    const { user, onClose } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(toast.success).toHaveBeenCalledWith('SRV-0077 cancelado.')
  })

  it('deshabilita el botón y dice que está cancelando', async () => {
    server.use(changeStatusSlow({}))
    const { user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), REASON)
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))

    const pending = await screen.findByRole('button', { name: /Cancelando/ })
    expect(pending).toBeDisabled()
    expect(screen.getByLabelText(/Motivo de la cancelación/)).toBeDisabled()
  })

  it('asocia el error del motivo con su campo', async () => {
    const { user } = renderModal()

    const field = screen.getByLabelText(/Motivo de la cancelación/)
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
      expect(document.activeElement).toBe(screen.getByLabelText(/Motivo de la cancelación/)),
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

  it('tampoco las tiene con el error de validación puesto', async () => {
    const { baseElement, user } = renderModal()

    await user.type(screen.getByLabelText(/Motivo de la cancelación/), 'corto')
    await user.click(screen.getByRole('button', { name: 'Cancelar viaje' }))
    await screen.findByText('El motivo debe tener al menos 10 caracteres')

    expect(await axe(baseElement)).toHaveNoViolations()
  })
})
