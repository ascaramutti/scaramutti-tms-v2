import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ServiceStatus, UserRole } from '../../../../api'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../../shared/ui/buttonStyles'
import { REOPEN_AVAILABLE_NOTE } from '../../status/serviceStatusTransitions'
import { ServiceStatusActions } from './ServiceStatusActions'
import { server } from '../../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  changeStatusCapture,
  fakeServiceDetail,
} from '../../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from '../../hooks/useService'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

/**
 * El rol va SIEMPRE explícito, sin valor por defecto: un default de parámetro se
 * aplica también cuando el argumento es `undefined`, así que el caso "sin rol" habría
 * corrido como `admin` y no podía fallar.
 */
function serviceWithStatus(status: ServiceStatus): ServiceWithEtag {
  return { ...fakeServiceDetail({ status }), _etag: DEFAULT_SERVICE_ETAG }
}

function renderActions(status: ServiceStatus, role: UserRole | undefined) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // El router hace falta porque la barra lleva un enlace (editar), no solo botones: sin
  // él, `Link` revienta al leer un contexto que no existe.
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
  return render(<ServiceStatusActions service={serviceWithStatus(status)} role={role} />, {
    wrapper,
  })
}

describe('ServiceStatusActions, qué ofrece', () => {
  it('ofrece iniciar y cancelar desde pendiente de inicio', () => {
    renderActions('PENDING_START', 'admin')

    expect(screen.getByRole('button', { name: /Iniciar viaje/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Cancelar viaje/ })).toBeInTheDocument()
    // Por ROL y no por texto: un `<span>` con la misma palabra, que no se puede
    // clickear, pasaría una búsqueda por texto.
    expect(screen.queryByRole('button', { name: /Finalizar viaje/ })).not.toBeInTheDocument()
  })

  it('ofrece finalizar y cancelar desde en ruta', () => {
    renderActions('IN_PROGRESS', 'admin')

    expect(screen.getByRole('button', { name: /Finalizar viaje/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Cancelar viaje/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Iniciar viaje/ })).not.toBeInTheDocument()
  })

  it('desde pendiente de asignación solo ofrece cancelar', () => {
    // El viaje avanza asignándole recursos, que es otra acción y vive en otra ficha.
    renderActions('PENDING_ASSIGNMENT', 'admin')

    expect(screen.getByRole('button', { name: /Cancelar viaje/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Iniciar viaje/ })).not.toBeInTheDocument()
  })

  it('en un viaje completado no queda ninguna transición, pero sí se corrige', () => {
    // El único estado del circuito que no admite ninguna transición. Editar SÍ, y es
    // deliberado: corregir los datos de un viaje ya cerrado es para lo que existe el
    // endpoint. Por eso acá se afirma la ausencia de BOTONES y la presencia del enlace,
    // que son cosas distintas.
    renderActions('COMPLETED', 'admin')

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    // El destino y no solo la presencia: con el `to` apuntando al detalle, el enlace
    // devuelve al usuario a donde ya estaba y la pantalla queda inalcanzable.
    expect(screen.getByRole('link', { name: 'Editar' })).toHaveAttribute(
      'href',
      '/cotizaciones/operaciones/servicios/77/editar',
    )
  })

  it('no monta ni el contenedor cuando no hay nada que ofrecer', () => {
    // Sin permiso de edición y sin transiciones no queda nada: ni el grupo, para no dejar
    // en el encabezado un espacio que nada explica. El despacho es el único rol que da ese
    // cruce en un viaje completado: lo opera pero no lo corrige, porque el cuerpo de la
    // edición pide el precio que no puede ver.
    renderActions('COMPLETED', 'dispatcher')

    expect(screen.queryByRole('group', { name: 'Acciones del viaje' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
  })

  it.each(['CANCELLED', 'DELETED'] as const)('ofrece reabrir en %s, pero no corregir', (status) => {
    // Estos dos dejaron de ser el final del camino. Corregir NO se ofrece: un viaje fuera
    // del circuito es inmutable, y ofrecer la entrada para explicar después que no se
    // puede es justo lo que la pantalla de edición evita. Sin esta mitad, borrar la
    // condición de estado del enlace pasa verde, porque acá la barra ya existe por el
    // botón de reabrir.
    renderActions(status, 'admin')
    expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()

    expect(screen.getByRole('button', { name: /Reabrir viaje/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Cancelar viaje/ })).not.toBeInTheDocument()
  })

  it.each(['CANCELLED', 'DELETED'] as const)(
    'a la jefatura de operaciones no le ofrece reabrir en %s',
    (status) => {
      renderActions(status, 'operations_manager')

      expect(screen.queryByRole('group', { name: 'Acciones del viaje' })).not.toBeInTheDocument()
    },
  )

  it('ofrece eliminar donde el viaje todavía no salió', () => {
    renderActions('PENDING_START', 'admin')
    expect(screen.getByRole('button', { name: /Eliminar viaje/ })).toBeInTheDocument()
  })

  it('no ofrece eliminar un viaje en ruta', () => {
    // Lo que ya ocurrió se cancela; lo que nunca fue se elimina.
    renderActions('IN_PROGRESS', 'admin')

    expect(screen.queryByRole('button', { name: /Eliminar viaje/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Cancelar viaje/ })).toBeInTheDocument()
  })

  it('muestra los botones en el orden de la tabla, con el avance primero', () => {
    // La tabla declara que su orden es el de la barra; sin este caso el componente
    // puede darlo vuelta y poner la salida antes que el avance, impunemente.
    renderActions('IN_PROGRESS', 'admin')

    const group = screen.getByRole('group', { name: 'Acciones del viaje' })
    const labels = within(group)
      .getAllByRole('button')
      .map((button) => button.textContent?.trim())

    expect(labels).toEqual(['Finalizar viaje', 'Cancelar viaje'])
  })

  it('ofrece la cancelación en gris y no como acción destructiva de la barra', () => {
    // Decisión de diseño con su párrafo en el código y, sin esto, sin ninguna red: el
    // rojo se reserva para el botón que confirma dentro del diálogo.
    renderActions('IN_PROGRESS', 'admin')

    // Contra las constantes y no contra clases sueltas: prohibir solo el rojo dejaba
    // pasar que cancelar se pintara del mismo azul primario que finalizar, que es el
    // error real, porque borra la jerarquía entre avanzar el viaje y matarlo.
    expect(screen.getByRole('button', { name: /Cancelar viaje/ }).className).toBe(SECONDARY_BUTTON)
    expect(screen.getByRole('button', { name: /Finalizar viaje/ }).className).toBe(PRIMARY_BUTTON)
  })

  it('ofrece las dos salidas en gris y la reapertura en primario', () => {
    // Eliminar acompaña a cancelar: las dos sacan el viaje del circuito y ninguna es la
    // acción principal de esa pantalla. Reabrir sí lo es, porque en un viaje que ya salió
    // del circuito no compite con nada.
    const { unmount } = renderActions('PENDING_START', 'admin')
    expect(screen.getByRole('button', { name: /Eliminar viaje/ }).className).toBe(SECONDARY_BUTTON)
    unmount()

    renderActions('CANCELLED', 'admin')
    expect(screen.getByRole('button', { name: /Reabrir viaje/ }).className).toBe(PRIMARY_BUTTON)
  })

  it('agrupa los botones con un nombre', () => {
    renderActions('PENDING_START', 'admin')

    expect(screen.getByRole('group', { name: 'Acciones del viaje' })).toBeInTheDocument()
  })
})

describe('ServiceStatusActions, por rol', () => {
  it.each(['admin', 'general_manager', 'operations_manager', 'dispatcher'] as const)(
    '%s puede iniciar el viaje',
    (role) => {
      renderActions('PENDING_START', role)

      expect(screen.getByRole('button', { name: /Iniciar viaje/ })).toBeInTheDocument()
    },
  )

  it.each(['PENDING_ASSIGNMENT', 'PENDING_START', 'IN_PROGRESS'] as const)(
    'al despacho no le ofrece cancelar en %s',
    (status) => {
      // Aplicar el veto recién al enviar le mostraría al despacho un botón que termina
      // en un 403.
      renderActions(status, 'dispatcher')

      expect(screen.queryByRole('button', { name: /Cancelar viaje/ })).not.toBeInTheDocument()
    },
  )

  it('al despacho sí le ofrece avanzar el viaje', () => {
    // La otra mitad del caso anterior: sin esto, esconderle TODA la barra al despacho
    // pasaría verde.
    renderActions('IN_PROGRESS', 'dispatcher')

    expect(screen.getByRole('button', { name: /Finalizar viaje/ })).toBeInTheDocument()
  })

  it('a ventas no le ofrece ninguna acción', () => {
    // Colgar la barra de los roles del módulo (que incluyen ventas) en vez de los que
    // operan el viaje es un cambio de una sola constante importada.
    renderActions('IN_PROGRESS', 'sales')

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('sin rol tampoco ofrece nada', () => {
    renderActions('IN_PROGRESS', undefined)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('ServiceStatusActions, la apertura', () => {
  it('abre el diálogo de la acción que se apretó', async () => {
    const user = userEvent.setup()
    renderActions('IN_PROGRESS', 'admin')

    await user.click(screen.getByRole('button', { name: /Finalizar viaje/ }))

    // Con el modal cableado al destino equivocado, el título delataría el cruce.
    expect(await screen.findByRole('dialog', { name: 'Finalizar viaje' })).toBeInTheDocument()
    // Y es el de AVANZAR: el título sale de la misma tabla en los dos diálogos, así que
    // solo el formulario distingue cuál se abrió.
    expect(screen.getByLabelText(/Fecha y hora/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Motivo/)).not.toBeInTheDocument()
  })

  it('el diálogo de salida vuelve a abrirse en blanco', async () => {
    // El motivo de un intento anterior reenviado por descuido queda en la bitácora del
    // viaje. Lo que hoy lo garantiza es que la barra desmonta el modal al cerrarlo.
    const user = userEvent.setup()
    renderActions('IN_PROGRESS', 'admin')

    await user.click(screen.getByRole('button', { name: /Cancelar viaje/ }))
    await user.type(await screen.findByLabelText(/^Motivo/), 'motivo de la vez pasada')
    await user.click(screen.getByRole('button', { name: 'Volver' }))
    await user.click(screen.getByRole('button', { name: /Cancelar viaje/ }))

    expect(await screen.findByLabelText(/^Motivo/)).toHaveValue('')
  })

  it.each([
    ['IN_PROGRESS', /Cancelar viaje/, 'Cancelar viaje'],
    ['PENDING_START', /Eliminar viaje/, 'Eliminar viaje'],
    ['CANCELLED', /Reabrir viaje/, 'Reabrir viaje'],
  ] as const)('abre el diálogo de salida desde %s', async (status, boton, titulo) => {
    const user = userEvent.setup()
    renderActions(status, 'admin')

    await user.click(screen.getByRole('button', { name: boton }))

    expect(await screen.findByRole('dialog', { name: titulo })).toBeInTheDocument()
    // Y es el de salida de verdad, no el de avanzar con otro título: pide motivo y no
    // pide fecha.
    expect(screen.getByLabelText(/^Motivo/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Fecha y hora/)).not.toBeInTheDocument()
  })

  it('cierra el diálogo desde el botón de volver', async () => {
    // El cableado del cierre es de la barra (es la dueña del estado) y ninguno de los
    // dos archivos lo consideraba suyo: los del modal espían un `onClose` que no está
    // conectado a nada, y los de acá solo abrían. Con el cierre neutralizado, el
    // diálogo no se cerraba por ningún camino y la suite entera pasaba.
    const user = userEvent.setup()
    renderActions('PENDING_START', 'admin')

    await user.click(screen.getByRole('button', { name: /Iniciar viaje/ }))
    await screen.findByRole('dialog', { name: 'Iniciar viaje' })
    await user.click(screen.getByRole('button', { name: 'Volver' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('cierra el diálogo después de un envío exitoso', async () => {
    const user = userEvent.setup()
    server.use(changeStatusCapture({}))
    renderActions('PENDING_START', 'admin')

    await user.click(screen.getByRole('button', { name: /Iniciar viaje/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Iniciar viaje' })
    // Acotado al diálogo: el botón que abre y el que confirma comparten nombre, y sin
    // acotar la consulta encuentra los dos. Para un lector de pantalla no se pisan (el
    // diálogo es modal y deja inerte lo de atrás), pero el test sí los ve a ambos.
    await user.click(within(dialog).getByRole('button', { name: 'Iniciar viaje' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('cierra el diálogo con la tecla de escape', async () => {
    const user = userEvent.setup()
    renderActions('IN_PROGRESS', 'admin')

    await user.click(screen.getByRole('button', { name: /Cancelar viaje/ }))
    await screen.findByRole('dialog', { name: 'Cancelar viaje' })
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('no se lleva puesto el diálogo abierto si el viaje deja de admitir la acción', async () => {
    // Pasa de verdad: el usuario abre "Iniciar viaje", otro usuario cancela el viaje y
    // el detalle se refresca. Con el diálogo colgado de la condición de la barra, se
    // desvanecía bajo el cursor, con el error adentro y sin nada que lo explicara.
    const user = userEvent.setup()
    // Con el DESPACHO y no con admin: es el único rol que en un viaje completado se queda
    // sin transiciones Y sin edición, o sea el único escenario donde la barra entera
    // desaparece. Con admin la barra sobrevive por el enlace de editar, y entonces colgar
    // el diálogo de la condición de la barra pasaría verde: el caso mediría otra cosa.
    const { rerender } = renderActions('PENDING_START', 'dispatcher')

    await user.click(screen.getByRole('button', { name: /Iniciar viaje/ }))
    expect(await screen.findByRole('dialog', { name: 'Iniciar viaje' })).toBeInTheDocument()

    // `rerender` toma un solo argumento: reusa el wrapper del render original.
    rerender(<ServiceStatusActions service={serviceWithStatus('COMPLETED')} role="dispatcher" />)

    // La barra entera desaparece (no hay nada que ofrecerle al despacho en un completado),
    // pero el diálogo sigue ahí, que es lo que este caso protege.
    expect(screen.queryByRole('group', { name: 'Acciones del viaje' })).not.toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Iniciar viaje' })).toBeInTheDocument()
  })

  it('le pasa al diálogo el rol de quien mira, no uno fijo', async () => {
    // Las dos mitades: con solo la negativa, cablear `role="operations_manager"` pasaría;
    // con solo la positiva, pasaría `role="admin"`. Y lo que está en juego es el arreglo
    // de la ronda anterior: con un rol fijo, la jefatura de operaciones vuelve a leer que
    // puede reabrir un viaje que su rol no la deja reabrir.
    const user = userEvent.setup()
    const { unmount } = renderActions('PENDING_START', 'operations_manager')

    await user.click(screen.getByRole('button', { name: /Cancelar viaje/ }))
    const sinPermiso = await screen.findByRole('dialog', { name: 'Cancelar viaje' })
    expect(
      within(sinPermiso).queryByText(new RegExp(REOPEN_AVAILABLE_NOTE)),
    ).not.toBeInTheDocument()
    unmount()

    renderActions('PENDING_START', 'admin')
    await user.click(screen.getByRole('button', { name: /Cancelar viaje/ }))
    const conPermiso = await screen.findByRole('dialog', { name: 'Cancelar viaje' })
    expect(within(conPermiso).getByText(new RegExp(REOPEN_AVAILABLE_NOTE))).toBeInTheDocument()
  })

  it('no monta el diálogo hasta que se lo abre', () => {
    // El formulario congela sus valores al montar, y el suyo incluye la hora actual:
    // montado de entrada, precargaría la hora en que se abrió la pantalla.
    renderActions('PENDING_START', 'admin')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
