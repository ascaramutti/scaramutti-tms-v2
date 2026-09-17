import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { toast } from 'sonner'
import { http, HttpResponse } from 'msw'
import { ClientEditPage } from './ClientEditPage'
import type { ClientRequest } from '../../../api'
import { CLIENTS_BASE } from '../../../shared/paths'
import { clientKeys } from '../queryKeys'
import { server } from '../../../test/mocks/server'
import {
  fakeClient,
  getClientError,
  getClientNotFound,
  getClientOk,
  getClientSlow,
  getClientCapture,
  updateClientCapture,
  updateClientConflict,
  updateClientForbidden,
  updateClientNotFound,
  updateClientSlow,
  updateClientValidation,
} from '../../../test/mocks/handlers/clients'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const API = 'http://localhost:8080/api/v1'
const EDITAR = `${CLIENTS_BASE}/7/editar`
const DETALLE = `${CLIENTS_BASE}/7`

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidadas: Array<Record<string, unknown>> = []
  const invalidateReal = queryClient.invalidateQueries.bind(queryClient)
  queryClient.invalidateQueries = ((filtros: Record<string, unknown>) => {
    // Se guardan los FILTROS enteros y no solo la clave: invalidar por prefijo es
    // lo que alcanza a las búsquedas de los combobox, y una invalidación exacta
    // con la misma clave no alcanza a ninguna.
    invalidadas.push(filtros)
    return invalidateReal(filtros as never)
  }) as typeof queryClient.invalidateQueries

  const router = createMemoryRouter(
    [
      { path: `${CLIENTS_BASE}/:id/editar`, element: <ClientEditPage /> },
      { path: CLIENTS_BASE, element: <p>Pantalla de búsqueda</p> },
      { path: `${CLIENTS_BASE}/:id`, element: <p>Pantalla de detalle</p> },
    ],
    { initialEntries: [EDITAR] },
  )
  const { container } = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, invalidadas, container }
}

async function cargado() {
  return screen.findByLabelText('Razón social')
}

describe('ClientEditPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('muestra el estado de carga mientras trae el cliente', async () => {
    server.use(getClientSlow(fakeClient({ id: 7 }), 60))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Cargando cliente')).toBeInTheDocument())
    expect(await cargado()).toBeInTheDocument()
  })

  it('pide el cliente al servidor al abrir la URL', async () => {
    const sink: { id?: number } = {}
    server.use(getClientCapture(sink, fakeClient({ id: 7 })))
    renderPage()

    await cargado()
    expect(sink.id).toBe(7)
  })

  it('llena el formulario con lo que devuelve el servidor', async () => {
    server.use(
      getClientOk(
        fakeClient({
          id: 7,
          name: 'NUEVO SAC',
          ruc: '20999999999',
          phone: '912345678',
          contactName: 'Ana Torres',
        }),
      ),
    )
    renderPage()

    expect(await cargado()).toHaveValue('NUEVO SAC')
    expect(screen.getByLabelText('RUC')).toHaveValue('20999999999')
    expect(screen.getByLabelText(/teléfono/i)).toHaveValue('912345678')
    expect(screen.getByLabelText(/persona de contacto/i)).toHaveValue('Ana Torres')
  })

  /**
   * Sin ninguna interacción previa: el aviso está desde que se abre. El texto va
   * completo porque la versión recortada perdería el "e imprimirán", que es la
   * mitad que importa.
   */
  it('el aviso sobre los documentos ya registrados está al abrir', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    // Se afirma que se ve, que acá alcanza para atrapar un ocultamiento por estilo
    // en línea o por el atributo del elemento. OJO con lo que NO cubre: la suite
    // corre sin hojas de estilo, así que una clase de utilidad que oculte no se
    // nota desde acá. Lo que sí queda medido es que el aviso no dependa de haber
    // tocado el formulario, que es la mutación que importa.
    expect(
      screen.getByText(
        'Si cambias la razón social o el RUC, las cotizaciones y los servicios ya registrados de este cliente se mostrarán e imprimirán con los datos nuevos.',
      ),
    ).toBeVisible()
  })

  it('el aviso acompaña y no interrumpe', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    const aviso = screen.getByText(/las cotizaciones y los servicios ya registrados/i)
    expect(aviso.closest('[role="alert"]')).toBeNull()
    expect(aviso.closest('[role="status"]')).toBeNull()
  })

  it('el aviso va arriba de los campos', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    const primerCampo = await cargado()

    const aviso = screen.getByText(/las cotizaciones y los servicios ya registrados/i)
    expect(aviso.compareDocumentPosition(primerCampo) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('guardar arranca deshabilitado y el primer cambio lo habilita', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    const guardar = screen.getByRole('button', { name: /guardar/i })
    expect(guardar).toBeDisabled()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    expect(guardar).not.toBeDisabled()
  })

  /**
   * Deshacer el cambio vuelve a apagarlo. Sin este caso, un pestillo que se
   * prende al primer tecleo y no se apaga nunca pasa los otros dos enteros.
   */
  it('deshacer el cambio vuelve a deshabilitar guardar', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    const guardar = screen.getByRole('button', { name: /guardar/i })
    await user.type(screen.getByLabelText('Razón social'), 'X')
    expect(guardar).not.toBeDisabled()

    await user.keyboard('{Backspace}')
    await waitFor(() => expect(guardar).toBeDisabled())
  })

  it('guardar manda los cuatro campos editables y ninguno más', async () => {
    const user = userEvent.setup()
    const sink: { body?: ClientRequest; id?: number } = {}
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientCapture(sink))
    const { router } = renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), ' CORREGIDA')
    await user.clear(screen.getByLabelText(/teléfono/i))
    await user.type(screen.getByLabelText(/teléfono/i), '911222333')
    await user.clear(screen.getByLabelText(/persona de contacto/i))
    await user.type(screen.getByLabelText(/persona de contacto/i), 'Rosa Quispe')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    // Se espera el efecto en la pantalla y no la entrada al doble del servidor:
    // esto último se cumple ANTES de que la respuesta se entregue, así que no
    // probaría que la pantalla hizo nada con ella, y deja un pedido en vuelo.
    await waitFor(() => expect(router.state.location.pathname).toBe(DETALLE))
    expect(Object.keys(sink.body ?? {}).sort()).toEqual(['contactName', 'name', 'phone', 'ruc'])
    // Y los VALORES, no solo las llaves: es una pantalla de edición y su única
    // promesa es que lo tecleado llegue al servidor. Con solo las llaves, mandar
    // los datos cargados en vez del formulario pasa entero.
    expect(sink.body?.name).toBe('ACME S.A.C. CORREGIDA')
    expect(sink.body?.ruc).toBe('20123456789')
    expect(sink.body?.phone).toBe('911222333')
    expect(sink.body?.contactName).toBe('Rosa Quispe')
    expect(sink.id).toBe(7)
  })

  it('el teléfono y el contacto vacíos viajan como nulos', async () => {
    const user = userEvent.setup()
    const sink: { body?: ClientRequest } = {}
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientCapture(sink))
    const { router } = renderPage()
    await cargado()

    await user.clear(screen.getByLabelText(/teléfono/i))
    await user.clear(screen.getByLabelText(/persona de contacto/i))
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe(DETALLE))
    expect(sink.body?.phone).toBeNull()
    expect(sink.body?.contactName).toBeNull()
  })

  it('guardar con éxito avisa con lo que devolvió el servidor y vuelve al detalle', async () => {
    const user = userEvent.setup()
    server.use(
      getClientOk(fakeClient({ id: 7, name: 'acme minuscula' })),
      updateClientCapture({}, fakeClient({ id: 7, name: 'ACME MINUSCULA' })),
    )
    const { router } = renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe(DETALLE))
    expect(toast.success).toHaveBeenCalledWith('ACME MINUSCULA actualizado.')
  })

  /**
   * Los combobox del asistente de cotizaciones y del alta de servicios leen esa
   * misma caché: sin invalidarla seguirían ofreciendo la razón social vieja, y
   * nada en pantalla lo delataría porque la pantalla ya navegó.
   */
  it('guardar deja sin valer las búsquedas de clientes', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientCapture({}))
    const { invalidadas } = renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(invalidadas).toContainEqual({ queryKey: clientKeys.searches() }))
  })

  it('cancelar vuelve al detalle sin guardar nada', async () => {
    const user = userEvent.setup()
    const sink: { body?: ClientRequest } = {}
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientCapture(sink))
    const { router } = renderPage()
    await cargado()

    await user.click(screen.getByRole('button', { name: /cancelar/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe(DETALLE))
    expect(sink.body).toBeUndefined()
  })

  it('exige la razón social', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    await user.clear(screen.getByLabelText('Razón social'))
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    expect(await screen.findByText('La razón social es obligatoria.')).toBeInTheDocument()
  })

  it.each(['123', '201234567890', '2012345678a'])('rechaza el RUC %s', async (ruc) => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    await user.clear(screen.getByLabelText('RUC'))
    await user.type(screen.getByLabelText('RUC'), ruc)
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    expect(await screen.findByText('El RUC debe tener 11 dígitos.')).toBeInTheDocument()
  })

  it('un formulario inválido no llama al servidor', async () => {
    const user = userEvent.setup()
    const sink: { body?: ClientRequest } = {}
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientCapture(sink))
    renderPage()
    await cargado()

    await user.clear(screen.getByLabelText('RUC'))
    await user.type(screen.getByLabelText('RUC'), '123')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await screen.findByText('El RUC debe tener 11 dígitos.')
    expect(sink.body).toBeUndefined()
  })

  it('corregir el campo borra su mensaje', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    await user.clear(screen.getByLabelText('RUC'))
    await user.type(screen.getByLabelText('RUC'), '123')
    await user.click(screen.getByRole('button', { name: /guardar/i }))
    await screen.findByText('El RUC debe tener 11 dígitos.')

    await user.clear(screen.getByLabelText('RUC'))
    await user.type(screen.getByLabelText('RUC'), '20123456789')

    // Revalida mientras se escribe: el mensaje se va sin esperar a otro envío.
    await waitFor(() =>
      expect(screen.queryByText('El RUC debe tener 11 dígitos.')).not.toBeInTheDocument(),
    )
  })

  /**
   * El mensaje tiene que quedar ENGANCHADO al campo, no suelto: encontrarlo por
   * texto pasaría igual con el mensaje en un aviso flotante o bajo el campo
   * equivocado.
   */
  it('el RUC duplicado se explica en el campo RUC', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientConflict('CLI-001'))
    renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() =>
      expect(screen.getByLabelText('RUC')).toHaveAccessibleDescription(
        'Ya existe un cliente con el RUC indicado',
      ),
    )
    expect(screen.getByLabelText('Razón social')).toHaveAttribute('aria-invalid', 'false')
  })

  it('la razón social duplicada se explica en el campo razón social', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientConflict('CLI-002'))
    renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() =>
      expect(screen.getByLabelText('Razón social')).toHaveAccessibleDescription(
        'Ya existe un cliente con el nombre indicado',
      ),
    )
    expect(screen.getByLabelText('RUC')).toHaveAttribute('aria-invalid', 'false')
  })

  it('un 400 por campo lo reparte a su campo', async () => {
    const user = userEvent.setup()
    server.use(
      getClientOk(fakeClient({ id: 7 })),
      updateClientValidation([{ field: 'ruc', message: 'RUC inválido' }]),
    )
    renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() =>
      expect(screen.getByLabelText('RUC')).toHaveAccessibleDescription('RUC inválido'),
    )
  })

  it('abrir un cliente que ya no existe lo dice y ofrece la vuelta', async () => {
    server.use(getClientNotFound())
    const { router } = renderPage()

    expect(await screen.findByText('Este cliente ya no existe')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(EDITAR)
    expect(screen.getByRole('button', { name: /volver a clientes/i })).toBeInTheDocument()
  })

  it('guardar un cliente que ya no existe avisa y devuelve a la búsqueda', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientNotFound())
    const { router } = renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe(CLIENTS_BASE))
    expect(toast.error).toHaveBeenCalledWith('Este cliente ya no existe.')
    // Reemplaza en vez de apilar: el botón Atrás no puede devolver al formulario
    // de un cliente que ya no existe.
    expect(router.state.historyAction).toBe('REPLACE')
  })

  /**
   * Un error de red NO es "el cliente no existe": sin este caso, la vuelta
   * automática se implementa para cualquier error y una caída se ve como un
   * borrado.
   */
  it('un error al cargar se explica sin sacar al usuario de la pantalla', async () => {
    server.use(getClientError())
    const { router } = renderPage()

    // El hook reintenta una vez antes de darse por vencido (un 500 puede ser
    // pasajero), así que el aviso tarda más que el tope por omisión de la espera.
    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reintentar/i })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(EDITAR)
    expect(screen.queryByText('Este cliente ya no existe')).not.toBeInTheDocument()
  })

  /**
   * Sin permiso el formulario no se rompe: lo tipeado sigue ahí y el botón se
   * puede volver a usar. Esa es la mitad que un test perezoso omite.
   */
  it('sin permiso avisa y deja el formulario usable', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientForbidden())
    const { router } = renderPage()
    await cargado()

    await user.clear(screen.getByLabelText('Razón social'))
    await user.type(screen.getByLabelText('Razón social'), 'CORREGIDA SAC')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('No tiene permisos para acceder a este recurso'),
    )
    expect(screen.getByLabelText('Razón social')).toHaveValue('CORREGIDA SAC')
    expect(screen.getByRole('button', { name: /guardar/i })).not.toBeDisabled()
    expect(router.state.location.pathname).toBe(EDITAR)
  })

  it('un error sin explicación del servidor usa el mensaje de último recurso', async () => {
    const user = userEvent.setup()
    server.use(
      getClientOk(fakeClient({ id: 7 })),
      http.put(`${API}/clients/:id`, () => HttpResponse.error()),
    )
    renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('No se pudieron guardar los cambios. Intenta de nuevo.'),
    )
  })

  it('los cuatro campos tienen rótulo accesible', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    expect(screen.getByLabelText('Razón social')).toBeInTheDocument()
    expect(screen.getByLabelText('RUC')).toBeInTheDocument()
    expect(screen.getByLabelText(/teléfono/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/persona de contacto/i)).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad con el formulario cargado', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    const { container } = renderPage()
    await cargado()

    expect(await axe(container)).toHaveNoViolations()
  })
  /**
   * El botón apagado tiene que VERSE apagado. El proyecto ya se comió una vez un
   * botón deshabilitado para el DOM y encendido para el ojo, y la consulta de
   * "está deshabilitado" mira el atributo, no el color.
   */
  it('guardar trae la clase que lo apaga cuando queda deshabilitado', async () => {
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    expect(screen.getByRole('button', { name: /guardar/i }).className).toContain(
      'disabled:bg-accent-disabled',
    )
  })

  /**
   * El mensaje del backend gana sobre el genérico: un 403 que explique el motivo
   * no puede salir como "no se pudo cargar, intenta de nuevo" con un botón de
   * reintentar que nunca va a funcionar.
   */
  it('un error al cargar muestra lo que explica el backend', async () => {
    server.use(getClientError())
    renderPage()

    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toHaveTextContent(
      'Error interno del servidor',
    )
  })

  /**
   * MA5: la pantalla tiene que decir a quién estás editando. Con dos pestañas
   * abiertas es lo único que las distingue, y sin este caso el encabezado puede
   * quedar en "Editar" a secas sin que nada se rompa.
   */
  it('el encabezado nombra al cliente que se está editando', async () => {
    server.use(getClientOk(fakeClient({ id: 7, name: 'NUEVO SAC', ruc: '20999999999' })))
    renderPage()
    await cargado()

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Editar NUEVO SAC')
    expect(screen.getByText(/20999999999/)).toBeInTheDocument()
  })

  /**
   * Mientras el guardado vuela, el botón lo dice y los campos no se tocan. Sin
   * este caso, toda la rama de "enviando" queda sin red y un doble envío pasa.
   */
  it('mientras guarda lo dice y bloquea el formulario', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientSlow(fakeClient({ id: 7 }), 80))
    renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    expect(await screen.findByRole('button', { name: /guardando/i })).toBeDisabled()
    expect(screen.getByLabelText('Razón social')).toBeDisabled()
    expect(screen.getByLabelText('RUC')).toBeDisabled()
    expect(screen.getByLabelText(/teléfono/i)).toBeDisabled()
    expect(screen.getByLabelText(/persona de contacto/i)).toBeDisabled()
  })

  /** El botón que se ofrece al volver tiene que volver de verdad. */
  it('volver a clientes desde el cliente inexistente navega a la búsqueda', async () => {
    const user = userEvent.setup()
    server.use(getClientNotFound())
    const { router } = renderPage()

    await screen.findByText('Este cliente ya no existe')
    await user.click(screen.getByRole('button', { name: /volver a clientes/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe(CLIENTS_BASE))
  })

  /**
   * MI1: el texto que explica por qué el botón está apagado es la compensación
   * accesible de un control que el lector de pantalla saltea. Sin caso, se borra
   * en cualquier limpieza y nadie se entera.
   */
  it('con el formulario sin tocar, dice por qué no se puede guardar', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })))
    renderPage()
    await cargado()

    expect(screen.getByText('Cambia algún dato para guardar.')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await waitFor(() =>
      expect(screen.queryByText('Cambia algún dato para guardar.')).not.toBeInTheDocument(),
    )
  })

  /**
   * MI3: volver a entrar al mismo cliente no puede mostrar la foto vieja, así
   * que guardar invalida también su detalle.
   */
  it('guardar deja sin valer el detalle del cliente', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7 })), updateClientCapture({}))
    const { invalidadas } = renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(invalidadas).toContainEqual({ queryKey: clientKeys.detail(7) }))
  })

  /**
   * MI6: el teléfono y el contacto pueden venir nulos del servidor, y es la única
   * rama con riesgo declarado: si entran como nulo al formulario en vez de como
   * cadena vacía, el botón de guardar nace habilitado sin que nadie haya tocado
   * nada.
   */
  it('un cliente sin teléfono ni contacto abre con el guardar apagado', async () => {
    server.use(getClientOk(fakeClient({ id: 7, phone: null, contactName: null })))
    renderPage()
    await cargado()

    expect(screen.getByLabelText(/teléfono/i)).toHaveValue('')
    expect(screen.getByLabelText(/persona de contacto/i)).toHaveValue('')
    expect(screen.getByRole('button', { name: /guardar/i })).toBeDisabled()
  })

  /**
   * MI2: un campo que el backend nombre y el formulario no conozca no puede
   * romper el reparto de errores.
   */
  it('un 400 que nombra un campo desconocido no rompe el formulario', async () => {
    const user = userEvent.setup()
    server.use(
      getClientOk(fakeClient({ id: 7 })),
      updateClientValidation([
        { field: 'inventado', message: 'campo que el formulario no tiene' },
        { field: 'ruc', message: 'RUC inválido' },
      ]),
    )
    renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() =>
      expect(screen.getByLabelText('RUC')).toHaveAccessibleDescription('RUC inválido'),
    )
    expect(screen.getByLabelText('Razón social')).toHaveValue('ACME S.A.C.X')
  })

  /**
   * El caso de arriba no distingue el valor por omisión vacío del nulo: con nulo
   * el formulario tampoco nace sucio. La diferencia aparece cuando se escribe y
   * se borra: ahí el campo vuelve a cadena vacía y, si el valor por omisión era
   * nulo, el botón queda encendido sin que haya cambio neto.
   */
  it('escribir y borrar el teléfono de un cliente sin teléfono vuelve a apagar guardar', async () => {
    const user = userEvent.setup()
    server.use(getClientOk(fakeClient({ id: 7, phone: null, contactName: null })))
    renderPage()
    await cargado()

    const guardar = screen.getByRole('button', { name: /guardar/i })
    await user.type(screen.getByLabelText(/teléfono/i), '987654321')
    await waitFor(() => expect(guardar).not.toBeDisabled())

    await user.clear(screen.getByLabelText(/teléfono/i))
    await waitFor(() => expect(guardar).toBeDisabled())
  })

  /** El reintento de esta pantalla también tiene que reintentar de verdad. */
  it('reintentar vuelve a pedir el cliente y lo muestra', async () => {
    const user = userEvent.setup()
    server.use(getClientError())
    renderPage()

    await screen.findByRole('alert', {}, { timeout: 5000 })
    server.use(getClientOk(fakeClient({ id: 7, name: 'ACME RECUPERADO' })))
    await user.click(screen.getByRole('button', { name: /reintentar/i }))

    expect(await screen.findByLabelText('Razón social')).toHaveValue('ACME RECUPERADO')
  })

  /**
   * Un 400 cuyos campos son TODOS desconocidos no puede quedar en silencio: sin
   * la lista blanca, el reparto cree haber acertado y el usuario no ve nada.
   */
  it('un 400 con solo campos desconocidos avisa en vez de quedarse mudo', async () => {
    const user = userEvent.setup()
    server.use(
      getClientOk(fakeClient({ id: 7 })),
      updateClientValidation([{ field: 'inventado', message: 'campo que el formulario no tiene' }]),
    )
    renderPage()
    await cargado()

    await user.type(screen.getByLabelText('Razón social'), 'X')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('La solicitud contiene errores de validación'),
    )
  })

})
