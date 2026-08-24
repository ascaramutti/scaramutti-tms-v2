import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { toast } from 'sonner'
import { delay, http, HttpResponse } from 'msw'
import { axe } from 'vitest-axe'
import { ServiceCreatePage } from './ServiceCreatePage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import type { ServiceCreateRequest, UserRole } from '../../../api'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import { operationsKeys } from '../queryKeys'
import { cargoTypesSearch, fakeCargoType } from '../../../test/mocks/handlers/cargotypes'
import {
  currenciesError,
  currenciesOk,
  fakeCurrency,
} from '../../../test/mocks/handlers/catalogs'
import {
  clientsSearch,
  createClientOk,
  fakeClient,
} from '../../../test/mocks/handlers/clients'
import {
  createServiceCapture,
  createServiceDuplicate,
  createServiceFieldErrors,
  createServiceForbidden,
  createServiceOk,
  createServiceSlow,
  fakeServiceDetail,
} from '../../../test/mocks/handlers/operations'

/**
 * Reloj fijo en una hora cualquiera del mediodía de Lima: los casos de este archivo
 * hablan de qué día propone y qué día avisa la pantalla, y sin reloj fijo el
 * esperado cambiaría con el día en que corra la suite.
 */
const NOW = new Date('2026-08-24T17:00:00Z') // 24/08 12:00 en Lima
const TODAY_IN_LIMA = '2026-08-24'
const API_URL = 'http://localhost:8080/api/v1'

function renderCreatePage({
  role = 'admin' as UserRole,
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
} = {}) {
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, { ...fakeUser, role })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cotizaciones/operaciones/servicios/nuevo']}>
          <Routes>
            <Route path="/cotizaciones/operaciones/servicios/nuevo" element={<ServiceCreatePage />} />
            {/* Destino del alta y del cancelar: se afirma que se llega, no que se navega. */}
            <Route path="/cotizaciones/operaciones" element={<div>Listado de servicios</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Elige el cliente del combobox (búsqueda async con mínimo de 3 caracteres). */
async function pickClient(user: ReturnType<typeof userEvent.setup>, name = 'ACME S.A.C.') {
  await user.type(screen.getByLabelText('Cliente'), 'acme')
  await user.click(await screen.findByText(name))
}

/** Elige el tipo de carga del combobox. */
async function pickCargoType(user: ReturnType<typeof userEvent.setup>, name = 'CARGA GENERAL') {
  await user.type(screen.getByLabelText('Tipo de carga'), 'carga')
  await user.click(await screen.findByText(name))
}

/**
 * Llena el formulario completo con datos válidos y lo envía. Devuelve lo cargado
 * para que cada caso afirme contra eso en vez de repetir literales.
 */
async function fillAndSubmit(
  user: ReturnType<typeof userEvent.setup>,
  { withMeasures = true, observations = '' } = {},
) {
  await pickClient(user)
  await user.selectOptions(screen.getByLabelText('Ámbito del viaje'), 'PROVINCIA')
  await user.clear(screen.getByLabelText('Origen'))
  await user.type(screen.getByLabelText('Origen'), 'Piura')
  await user.type(screen.getByLabelText('Destino'), 'Lima')
  await pickCargoType(user)
  await user.clear(screen.getByLabelText('Peso (kg)'))
  await user.type(screen.getByLabelText('Peso (kg)'), '28000')
  if (withMeasures) {
    await user.clear(screen.getByLabelText('Largo (m)'))
    await user.type(screen.getByLabelText('Largo (m)'), '12.5')
  }
  await user.type(screen.getByLabelText('Precio'), '5800')
  await user.selectOptions(screen.getByLabelText('Moneda'), '1')
  if (observations) {
    await user.type(screen.getByLabelText(/observaciones/i), observations)
  }
  await user.click(screen.getByRole('button', { name: /registrar servicio/i }))
}

describe('ServiceCreatePage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(NOW)
    // Un tipo de carga SIN medidas estándar por defecto: los casos que miden el
    // autollenado piden explícitamente uno que sí las tenga, así que un default
    // con medidas les daría verde sin que el autollenado exista.
    server.use(
      // Tres clientes y se elige el del medio: con uno solo, un buscador que
      // devolviera siempre el primero sería indistinguible del correcto, y el viaje
      // terminaría facturado a otra empresa.
      clientsSearch([
        fakeClient({ id: 5, name: 'ACME NORTE S.A.C.', ruc: '20100000001' }),
        fakeClient({ id: 12, name: 'ACME S.A.C.', ruc: '20123456789' }),
        fakeClient({ id: 31, name: 'ACME SUR S.A.C.', ruc: '20100000003' }),
      ]),
      cargoTypesSearch([fakeCargoType({ id: 3, name: 'CARGA GENERAL', standardWeight: 1000 })]),
    )
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  // ----- Render -----
  it('muestra los cuatro bloques del alta', async () => {
    renderCreatePage()
    expect(await screen.findByRole('heading', { name: 'Registrar servicio' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Viaje' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Carga' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Precio' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Observaciones' })).toBeInTheDocument()
  })

  // ----- El catálogo de monedas -----
  it('si las monedas no cargan, lo dice y ofrece reintentar en vez de un desplegable vacío', async () => {
    server.use(currenciesError(500))
    renderCreatePage()

    // Sin este corte el select quedaba habilitado con cero monedas y el usuario se
    // enteraba recién al enviar, con todo el formulario cargado. El texto es el del
    // servidor, no uno inventado acá.
    expect(await screen.findByRole('alert')).toHaveTextContent('Fallo al cargar monedas')
    expect(screen.getByRole('button', { name: /reintentar/i })).toBeInTheDocument()
    expect(screen.queryByLabelText('Moneda')).not.toBeInTheDocument()
  })

  it('mientras las monedas cargan muestra el spinner y no el formulario', async () => {
    server.use(
      http.get(`${API_URL}/currencies`, async () => {
        await delay(50)
        return HttpResponse.json([fakeCurrency({ id: 1, code: 'PEN', name: 'Soles' })])
      }),
    )
    renderCreatePage()

    expect(await screen.findByRole('status', { name: /cargando monedas/i })).toBeInTheDocument()
    expect(screen.queryByLabelText('Origen')).not.toBeInTheDocument()
    // Y cuando llegan, el formulario aparece.
    expect(await screen.findByLabelText('Origen')).toBeInTheDocument()
  })

  it('un catálogo de monedas vacío corta igual que un fallo', async () => {
    // Para el usuario son el mismo problema: un desplegable sin opciones del que se
    // entera después de llenar todo.
    server.use(currenciesOk([]))
    renderCreatePage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/no hay monedas configuradas/i)
    expect(screen.queryByLabelText('Moneda')).not.toBeInTheDocument()
  })

  it('si el fallo de las monedas no trae mensaje, usa el propio', async () => {
    server.use(http.get(`${API_URL}/currencies`, () => new HttpResponse(null, { status: 500 })))
    renderCreatePage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/no se pudieron cargar las monedas/i)
  })

  it('reintentar recupera el formulario cuando las monedas vuelven', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    server.use(currenciesError(500))
    renderCreatePage()
    await screen.findByRole('button', { name: /reintentar/i })

    server.use(currenciesOk([fakeCurrency({ id: 2, code: 'PEN', name: 'Soles' })]))
    await user.click(screen.getByRole('button', { name: /reintentar/i }))

    expect(await screen.findByLabelText('Moneda')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'PEN — Soles' })).toBeInTheDocument()
  })

  it('propone hoy en Lima como fecha tentativa, dentro de la ventana del contrato', async () => {
    renderCreatePage()
    const date = (await screen.findByLabelText('Fecha tentativa')) as HTMLInputElement
    expect(date.value).toBe(TODAY_IN_LIMA)
    // Literales y no las constantes: derivarlos de lo mismo que miden dejaría que
    // la ventana se aleje del contrato con la suite en verde.
    expect(date).toHaveAttribute('min', '1900-01-01')
    expect(date).toHaveAttribute('max', '2999-12-31')
  })

  it('ofrece los dos ámbitos del contrato y ninguno más', async () => {
    renderCreatePage()
    const scope = (await screen.findByLabelText('Ámbito del viaje')) as HTMLSelectElement
    // El vacío inicial no es un ámbito: se descuenta para medir el dominio real.
    const values = [...scope.options].map((option) => option.value).filter(Boolean)
    expect(values).toEqual(['LOCAL', 'PROVINCIA'])
  })

  it('no tiene violaciones de accesibilidad detectables', async () => {
    const { container } = renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    expect(await axe(container)).toHaveNoViolations()
  })

  // ----- Fecha pasada: avisa, no bloquea -----
  it('avisa cuando la fecha tentativa ya pasó', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCreatePage()
    const date = await screen.findByLabelText('Fecha tentativa')
    await user.clear(date)
    await user.type(date, '2026-08-20')
    expect(await screen.findByText(/la fecha ya pasó/i)).toBeInTheDocument()
  })

  it('no avisa con la fecha de hoy en Lima', async () => {
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    expect(screen.queryByText(/la fecha ya pasó/i)).not.toBeInTheDocument()
  })

  it('con la fecha vacía no avisa de nada', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCreatePage()
    const date = await screen.findByLabelText('Fecha tentativa')
    await user.clear(date)

    // Un campo en blanco no es una fecha que quedó atrás: sin la guarda, vaciarlo
    // mostraba el aviso ámbar sobre un campo sin valor.
    expect(screen.queryByText(/la fecha ya pasó/i)).not.toBeInTheDocument()
  })

  it('el aviso de fecha pasada no impide registrar', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    server.use(createServiceCapture(sink))
    renderCreatePage()
    const date = await screen.findByLabelText('Fecha tentativa')
    await user.clear(date)
    await user.type(date, '2026-08-20')
    await fillAndSubmit(user)
    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.tentativeDate).toBe('2026-08-20')
  })

  // ----- Validación -----
  it('no envía nada si falta lo obligatorio y explica cada campo', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    server.use(createServiceCapture(sink))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await user.click(screen.getByRole('button', { name: /registrar servicio/i }))

    expect(await screen.findByText('Selecciona el cliente')).toBeInTheDocument()
    expect(screen.getByText('Elige el ámbito del viaje')).toBeInTheDocument()
    expect(screen.getByText('Indica el origen')).toBeInTheDocument()
    expect(screen.getByText('Indica el destino')).toBeInTheDocument()
    expect(screen.getByText('Selecciona el tipo de carga')).toBeInTheDocument()
    expect(screen.getByText('Indica el peso')).toBeInTheDocument()
    expect(screen.getByText('Indica el precio')).toBeInTheDocument()
    expect(screen.getByText('Elige la moneda del servicio')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('anuncia el error del ámbito y lo asocia a su campo', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await user.click(screen.getByRole('button', { name: /registrar servicio/i }))

    // Este select está escrito a mano (el compartido normaliza a número y el ámbito
    // es texto), así que su error necesita el mismo trato que el de los demás campos:
    // anunciado al aparecer y leído al enfocar el control.
    const error = await screen.findByText('Elige el ámbito del viaje')
    expect(error).toHaveAttribute('role', 'alert')
    const scope = screen.getByLabelText('Ámbito del viaje')
    expect(scope).toHaveAttribute('aria-invalid', 'true')
    expect(scope).toHaveAttribute('aria-describedby', error.id)
  })

  it('avisa que la fecha pasada quedó atrás sin obligar a mirar la pantalla', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCreatePage()
    const date = await screen.findByLabelText('Fecha tentativa')
    await user.clear(date)
    await user.type(date, '2026-08-20')

    // El aviso existe para cazar un año mal tecleado: si no se anuncia, para quien no
    // ve la pantalla no ocurre nunca.
    // `role="alert"` y no `status`: el nodo se inserta junto con su texto, y en una
    // región recién creada un `polite` habitualmente no se anuncia.
    const aviso = await screen.findByText(/la fecha ya pasó/i)
    expect(aviso).toHaveAttribute('role', 'alert')
  })

  it('rechaza un precio en cero: un viaje siempre tiene precio', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCreatePage()
    await screen.findByLabelText('Precio')
    await user.type(screen.getByLabelText('Precio'), '0')
    await user.click(screen.getByRole('button', { name: /registrar servicio/i }))
    expect(await screen.findByText('El precio debe ser mayor a 0')).toBeInTheDocument()
  })

  // ----- Lo que se manda -----
  it('manda el viaje completo con los valores cargados', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    server.use(createServiceCapture(sink))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user, { observations: 'Coordinar ingreso al puerto' })

    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body).toEqual({
      clientId: 12,
      tripScope: 'PROVINCIA',
      tentativeDate: TODAY_IN_LIMA,
      origin: 'Piura',
      destination: 'Lima',
      cargoTypeId: 3,
      weightKg: 28000,
      lengthM: 12.5,
      widthM: null,
      heightM: null,
      price: 5800,
      currencyId: 1,
      observations: 'Coordinar ingreso al puerto',
    })
  })

  it('manda en null las medidas y las observaciones que quedaron vacías', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    server.use(createServiceCapture(sink))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user, { withMeasures: false })

    await waitFor(() => expect(sink.body).toBeDefined())
    // Null y no cero: una medida sin cargar no es una medida de cero metros.
    expect(sink.body?.lengthM).toBeNull()
    expect(sink.body?.widthM).toBeNull()
    expect(sink.body?.heightM).toBeNull()
    expect(sink.body?.observations).toBeNull()
  })

  // ----- Autollenado desde el tipo de carga -----
  it('al elegir el tipo de carga copia su peso y sus medidas estándar', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    server.use(
      cargoTypesSearch([
        fakeCargoType({
          id: 3,
          name: 'CARGA GENERAL',
          standardWeight: 28000,
          standardLength: 12.5,
          standardWidth: 2.6,
          standardHeight: 4,
        }),
      ]),
    )
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await pickCargoType(user)

    expect((screen.getByLabelText('Peso (kg)') as HTMLInputElement).value).toBe('28000')
    expect((screen.getByLabelText('Largo (m)') as HTMLInputElement).value).toBe('12.5')
    expect((screen.getByLabelText('Ancho (m)') as HTMLInputElement).value).toBe('2.6')
    expect((screen.getByLabelText('Alto (m)') as HTMLInputElement).value).toBe('4')
  })

  it('un tipo de carga con medidas en cero deja los campos vacíos y no traba el alta', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    // El catálogo tiene ceros heredados: filas de v1 y filas creadas por el alta al
    // vuelo antes de que se arreglara. Leídos como medida, el formulario los
    // rechazaría por no ser mayores que cero y el viaje no se podría registrar.
    server.use(
      cargoTypesSearch([
        fakeCargoType({
          id: 3,
          name: 'CARGA GENERAL',
          standardWeight: 1000,
          standardLength: 0,
          standardWidth: 0,
          standardHeight: 0,
        }),
      ]),
      createServiceCapture(sink),
    )
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await pickCargoType(user)

    expect((screen.getByLabelText('Largo (m)') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('Ancho (m)') as HTMLInputElement).value).toBe('')

    await pickClient(user)
    await user.selectOptions(screen.getByLabelText('Ámbito del viaje'), 'LOCAL')
    await user.type(screen.getByLabelText('Origen'), 'Piura')
    await user.type(screen.getByLabelText('Destino'), 'Lima')
    await user.type(screen.getByLabelText('Precio'), '900')
    await user.selectOptions(screen.getByLabelText('Moneda'), '1')
    await user.click(screen.getByRole('button', { name: /registrar servicio/i }))

    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.lengthM).toBeNull()
  })

  it('lo autollenado se puede corregir y viaja corregido', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    server.use(
      cargoTypesSearch([
        fakeCargoType({ id: 3, name: 'CARGA GENERAL', standardWeight: 28000, standardLength: 12.5 }),
      ]),
      createServiceCapture(sink),
    )
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await pickClient(user)
    await user.selectOptions(screen.getByLabelText('Ámbito del viaje'), 'LOCAL')
    await user.type(screen.getByLabelText('Origen'), 'Piura')
    await user.type(screen.getByLabelText('Destino'), 'Lima')
    await pickCargoType(user)
    await user.clear(screen.getByLabelText('Peso (kg)'))
    await user.type(screen.getByLabelText('Peso (kg)'), '15000')
    await user.type(screen.getByLabelText('Precio'), '4200')
    await user.selectOptions(screen.getByLabelText('Moneda'), '1')
    await user.click(screen.getByRole('button', { name: /registrar servicio/i }))

    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.weightKg).toBe(15000)
    // El largo autollenado, que nadie tocó, viaja igual.
    expect(sink.body?.lengthM).toBe(12.5)
  })

  // ----- Después de registrar -----
  it('registrado, avisa con el código asignado y vuelve al listado', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const success = vi.spyOn(toast, 'success')
    server.use(createServiceOk(fakeServiceDetail({ code: 'SRV-0077' })))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user)

    expect(await screen.findByText('Listado de servicios')).toBeInTheDocument()
    // El código lo asigna el servidor: es el dato con el que después se busca el viaje.
    expect(success).toHaveBeenCalledWith('Servicio SRV-0077 registrado.')
  })

  it('al registrar deja sin valer el listado y los indicadores', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidadas: unknown[][] = []
    const invalidate = queryClient.invalidateQueries.bind(queryClient)
    vi.spyOn(queryClient, 'invalidateQueries').mockImplementation((filters) => {
      invalidadas.push((filters?.queryKey ?? []) as unknown[])
      return invalidate(filters)
    })
    server.use(createServiceOk())
    renderCreatePage({ queryClient })
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user)
    await screen.findByText('Listado de servicios')

    // Son dos ramas distintas del cache y hacen falta las dos: el viaje nace
    // pendiente de asignación, así que mueve las filas Y el contador de pendientes.
    // Sin esto, el usuario vuelve a un listado y a un tablero que no se movieron.
    expect(invalidadas).toContainEqual(operationsKeys.serviceLists())
    expect(invalidadas).toContainEqual(operationsKeys.serviceStats())
  })

  it('cancelar vuelve al listado sin registrar nada', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    server.use(createServiceCapture(sink))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(await screen.findByText('Listado de servicios')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('deshabilita el botón mientras el alta está en curso', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    server.use(createServiceSlow(60))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user)

    // Anti doble-click del lado del cliente; el servidor tiene el suyo (OPS-007).
    expect(await screen.findByRole('button', { name: /registrando/i })).toBeDisabled()
    // Y el resto del formulario también: lo que se escriba ahora ya no viaja, y el
    // botón de quitar el cliente permitía desasociarlo de un viaje que ya salió.
    expect(screen.getByLabelText('Origen')).toBeDisabled()
    expect(screen.getByLabelText(/observaciones/i)).toBeDisabled()
    expect(screen.queryByRole('button', { name: /quitar selección/i })).not.toBeInTheDocument()
  })

  // ----- Errores del servidor -----
  it('el alta repetida explica que ya se registró, sin sonar a error de datos', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    server.use(createServiceDuplicate())
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user)

    const aviso = await screen.findByText(/se registró hace unos segundos/i)
    expect(aviso).toHaveAttribute('role', 'alert')
    // El aviso habla del viaje entero: el origen no tiene nada de malo y no queda
    // marcado como inválido.
    expect(screen.getByLabelText('Origen')).toHaveAttribute('aria-invalid', 'false')
    // Sigue en el formulario: no se perdió lo cargado.
    expect(screen.queryByText('Listado de servicios')).not.toBeInTheDocument()
  })

  it('ancla al campo los errores de validación del servidor', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    server.use(
      createServiceFieldErrors([
        { field: 'destination', message: 'El destino no puede repetir el origen.' },
      ]),
    )
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user)

    expect(await screen.findByText('El destino no puede repetir el origen.')).toBeInTheDocument()
  })

  it('si el alta falla sin explicación, lo dice con su propio mensaje', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const error = vi.spyOn(toast, 'error')
    // 500 sin cuerpo: es el único caso en que el texto del frontend es el correcto,
    // porque no hay `detail` que mostrar.
    server.use(http.post(`${API_URL}/services`, () => new HttpResponse(null, { status: 500 })))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user)

    await waitFor(() =>
      expect(error).toHaveBeenCalledWith('No se pudo registrar el servicio. Intenta de nuevo.'),
    )
    expect(screen.queryByText('Listado de servicios')).not.toBeInTheDocument()
  })

  it('muestra el motivo del servidor cuando rechaza el alta por permisos', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const error = vi.spyOn(toast, 'error')
    server.use(createServiceForbidden('Registrar un servicio exige poder ver los importes.'))
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await fillAndSubmit(user)

    await waitFor(() =>
      expect(error).toHaveBeenCalledWith('Registrar un servicio exige poder ver los importes.'),
    )
  })

  // ----- Buscadores -----
  it('no busca clientes con menos de 3 caracteres y lo explica', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const consultas: string[] = []
    server.use(
      http.get(`${API_URL}/clients`, ({ request }) => {
        consultas.push(new URL(request.url).searchParams.get('q') ?? '')
        return HttpResponse.json({
          content: [],
          page: 0,
          size: 10,
          totalElements: 0,
          totalPages: 0,
          numberOfElements: 0,
          first: true,
          last: true,
          empty: true,
        })
      }),
    )
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await user.type(screen.getByLabelText('Cliente'), 'ac')

    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    // Espera mayor al debounce: sin esto, "no se disparó" es indistinguible de
    // "todavía no se disparó".
    await new Promise((resolve) => setTimeout(resolve, 450))
    expect(consultas).toEqual([])
  })

  it('muestra el RUC del cliente elegido, en un campo que no se escribe', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')
    await pickClient(user)

    const ruc = screen.getByLabelText('RUC del cliente seleccionado') as HTMLInputElement
    expect(ruc.value).toBe('20123456789')
    expect(ruc).toHaveAttribute('readonly')
  })

  it('el cliente creado al vuelo queda elegido y viaja en el alta', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const sink: { body?: ServiceCreateRequest } = {}
    server.use(
      createServiceCapture(sink),
      createClientOk(fakeClient({ id: 99, name: 'NUEVA SAC', ruc: '20111111111' })),
    )
    renderCreatePage()
    await screen.findByLabelText('Fecha tentativa')

    await user.type(screen.getByLabelText('Cliente'), 'nueva')
    await user.click(await screen.findByText('Nuevo cliente'))
    // El modal precarga la razón social con lo tecleado en el buscador.
    await user.clear(screen.getByLabelText('Razón social'))
    await user.type(screen.getByLabelText('Razón social'), 'NUEVA SAC')
    await user.type(within(screen.getByRole('dialog')).getByLabelText('RUC'), '20111111111')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))

    // El handler por defecto de clientes devuelve id 99 para el alta.
    expect(await screen.findByText('NUEVA SAC')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Ámbito del viaje'), 'LOCAL')
    await user.type(screen.getByLabelText('Origen'), 'Piura')
    await user.type(screen.getByLabelText('Destino'), 'Lima')
    await pickCargoType(user)
    await user.clear(screen.getByLabelText('Peso (kg)'))
    await user.type(screen.getByLabelText('Peso (kg)'), '1000')
    await user.type(screen.getByLabelText('Precio'), '900')
    await user.selectOptions(screen.getByLabelText('Moneda'), '1')
    await user.click(screen.getByRole('button', { name: /registrar servicio/i }))

    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.clientId).toBe(99)
  })
})
