import { http, HttpResponse, delay } from 'msw'
import type {
  PageMeta,
  PageOfServiceSummary,
  Problem,
  ServiceStatsResponse,
  ServiceSummaryResponse,
} from '../../../api'

const API = 'http://localhost:8080/api/v1'

/**
 * Metadatos de una página a partir de la cantidad de filas y los overrides.
 * Misma aritmética que la de almacén; se duplica el mínimo para no acoplar los
 * handlers de dos módulos por un helper de 10 líneas.
 */
function pageMeta(numberOfElements: number, meta: Partial<PageMeta> = {}): PageMeta {
  const size = meta.size ?? 10
  const page = meta.page ?? 0
  const totalElements = meta.totalElements ?? numberOfElements
  const totalPages = meta.totalPages ?? (totalElements === 0 ? 0 : Math.ceil(totalElements / size))
  return {
    page,
    size,
    totalElements,
    totalPages,
    numberOfElements,
    first: page === 0,
    last: totalPages === 0 || page >= totalPages - 1,
    empty: numberOfElements === 0,
    ...meta,
  }
}

function problemResponse(status: number, problem: Partial<Problem> = {}) {
  return HttpResponse.json(
    { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo de prueba', ...problem },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/**
 * Fixture base de un servicio sin recursos asignados.
 *
 * `createdAt` cae a propósito en la ventana en que UTC ya cambió de día y Lima
 * no: 02/07 a las 21:00 en Lima es 03/07 a las 02:00 en UTC. Un test que afirme
 * la fecha con hora sobre este fixture falla si alguien saca la zona del
 * formateador.
 */
export function fakeServiceSummary(
  overrides: Partial<ServiceSummaryResponse> = {},
): ServiceSummaryResponse {
  return {
    id: 42,
    code: 'SRV-0042',
    client: { id: 12, name: 'IPH S.A.C.', ruc: '20123456789' },
    origin: 'Piura',
    destination: 'Lima — Callao',
    tentativeDate: '2026-07-10',
    tripScope: 'PROVINCIA',
    status: 'PENDING_ASSIGNMENT',
    driver: null,
    tractor: null,
    price: 5800,
    currencyCode: 'PEN',
    createdAt: '2026-07-03T02:00:00Z',
    ...overrides,
  }
}

/** Servicio en ruta, con conductor y tracto asignados. */
export function fakeAssignedService(
  overrides: Partial<ServiceSummaryResponse> = {},
): ServiceSummaryResponse {
  return fakeServiceSummary({
    id: 43,
    code: 'SRV-0043',
    status: 'IN_PROGRESS',
    driver: { id: 3, fullName: 'Juan Pérez' },
    tractor: { kind: 'TRACTOR', id: 9, plate: 'ABC-123' },
    ...overrides,
  })
}

/**
 * Servicio como lo ve el despacho: sin `price` ni `currencyCode`.
 * El contrato dice AUSENTES, no null, así que se borran las claves en vez de
 * ponerlas en undefined.
 */
export function fakeDispatcherServiceSummary(
  overrides: Partial<ServiceSummaryResponse> = {},
): ServiceSummaryResponse {
  const service = fakeServiceSummary(overrides)
  delete service.price
  delete service.currencyCode
  return service
}

/** Envuelve un array de servicios en una página completa (PageMeta + content). */
export function pageOfServices(
  content: ServiceSummaryResponse[],
  meta: Partial<PageOfServiceSummary> = {},
): PageOfServiceSummary {
  return { ...pageMeta(content.length, meta), content }
}

/**
 * Fixture de los indicadores. Todos los enteros distintos entre sí a propósito:
 * con valores repetidos, un `getByText` puede estar midiendo el tile equivocado
 * y el test pasaría igual.
 */
export function fakeServiceStats(
  overrides: Partial<ServiceStatsResponse> = {},
): ServiceStatsResponse {
  return {
    pendingAssignment: 4,
    pendingStart: 2,
    inProgress: 7,
    completedThisWeek: 11,
    driversOnRoad: { active: 3, total: 5 },
    unitsOnRoad: { active: 1, total: 6 },
    weekCycle: { start: '2026-08-19', end: '2026-08-25' },
    ...overrides,
  }
}

export interface ServicesCaptureSink {
  /** Params de la última request observada. */
  params?: URLSearchParams
  /** Params de TODAS las requests observadas, en orden. */
  calls?: URLSearchParams[]
}

/** Responde una página fija con el content/meta dados. */
export function servicesPage(
  content: ServiceSummaryResponse[],
  meta?: Partial<PageOfServiceSummary>,
) {
  return http.get(`${API}/services`, () => HttpResponse.json(pageOfServices(content, meta)))
}

/** Responde un listado vacío. */
export function servicesEmpty() {
  return http.get(`${API}/services`, () => HttpResponse.json(pageOfServices([])))
}

/** Responde un error (Problem RFC 7807). */
export function servicesError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/services`, () => problemResponse(status, problem))
}

/**
 * Error 500 sin cuerpo. Es el único caso en que el texto genérico del frontend
 * es el correcto: no hay `detail` que mostrar.
 */
export function servicesErrorWithoutBody() {
  return http.get(`${API}/services`, () => new HttpResponse(null, { status: 500 }))
}

/** Falla de red (sin respuesta del servidor). */
export function servicesNetworkError() {
  return http.get(`${API}/services`, () => HttpResponse.error())
}

/** Responde con un delay (para observar el estado de carga). */
export function servicesSlow(content: ServiceSummaryResponse[] = [], ms = 40) {
  return http.get(`${API}/services`, async () => {
    await delay(ms)
    return HttpResponse.json(pageOfServices(content))
  })
}

/**
 * Captura los query params de cada request en `sink`. Además de los últimos
 * params guarda el historial: sin él, aseverar que un parámetro está ausente no
 * distingue "no se disparó la request" de "se disparó sin ese parámetro".
 */
export function servicesCapture(
  sink: ServicesCaptureSink,
  content: ServiceSummaryResponse[] = [],
  meta?: Partial<PageOfServiceSummary>,
) {
  sink.calls = []
  return http.get(`${API}/services`, ({ request }) => {
    const params = new URL(request.url).searchParams
    sink.params = params
    sink.calls = [...(sink.calls ?? []), params]
    return HttpResponse.json(pageOfServices(content, meta))
  })
}

/** Página con filas propias por número de página (para testear el paginado). */
export function servicesPagedByParam(totalElements: number, size = 10) {
  return http.get(`${API}/services`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? '0')
    const content = [
      fakeServiceSummary({ id: page * 100 + 1, code: `SRV-P${page}-1` }),
      fakeServiceSummary({ id: page * 100 + 2, code: `SRV-P${page}-2` }),
    ]
    return HttpResponse.json(pageOfServices(content, { page, size, totalElements }))
  })
}

/**
 * Página 0 instantánea y las siguientes con demora: deja una ventana para
 * observar qué muestra la tabla MIENTRAS carga la página nueva.
 */
export function servicesPagedByParamSlow(totalElements: number, size = 10, ms = 60) {
  return http.get(`${API}/services`, async ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? '0')
    if (page > 0) await delay(ms)
    const content = [
      fakeServiceSummary({ id: page * 100 + 1, code: `SRV-P${page}-1` }),
      fakeServiceSummary({ id: page * 100 + 2, code: `SRV-P${page}-2` }),
    ]
    return HttpResponse.json(pageOfServices(content, { page, size, totalElements }))
  })
}

/**
 * OK en la página 0, error 500 en las siguientes: para testear que un refetch
 * fallido al paginar no borra la tabla previa.
 */
export function servicesOkThenErrorOnNextPage(
  content: ServiceSummaryResponse[],
  meta: Partial<PageOfServiceSummary> = {},
) {
  return http.get(`${API}/services`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? '0')
    if (page === 0) return HttpResponse.json(pageOfServices(content, { ...meta, page: 0 }))
    return problemResponse(500, { detail: 'Fallo al paginar' })
  })
}

/** Indicadores del tablero (happy path). */
export function serviceStatsOk(overrides: Partial<ServiceStatsResponse> = {}) {
  return http.get(`${API}/services/stats`, () => HttpResponse.json(fakeServiceStats(overrides)))
}

/** Indicadores en error. */
export function serviceStatsError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/services/stats`, () => problemResponse(status, problem))
}

/**
 * Handlers por defecto (happy path).
 *
 * El orden entre estos dos es indistinto: MSW matchea la ruta exacta, y
 * `/services` no se traga `/services/stats`. Se listan de más específico a menos
 * por convención, y porque el día que exista `/services/:id` (un patrón CON
 * parámetro, que sí se tragaría `/services/stats`) el orden va a importar de
 * verdad.
 */
export const operationsHandlers = [serviceStatsOk(), servicesPage([fakeServiceSummary()])]
