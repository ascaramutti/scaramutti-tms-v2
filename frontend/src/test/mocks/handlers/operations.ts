import { http, HttpResponse, delay } from 'msw'
import type {
  AddResourcesRequest,
  AssignResourcesRequest,
  DriverResponse,
  PageMeta,
  PageOfServiceSummary,
  Problem,
  ServiceCreateRequest,
  ServiceDetailResponse,
  ServiceAdditionalResourceResponse,
  ServiceEventResponse,
  ServiceResourceConflictProblem,
  ServiceStatsResponse,
  ServiceStatus,
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
 * Fixture de una entrada de bitácora. `createdAt` cae en la misma ventana que el
 * del resumen (21:00 en Lima ya es el día siguiente en UTC), así que un test que
 * afirme su fecha detecta que se saque la zona del formateador.
 */
export function fakeServiceEvent(
  overrides: Partial<ServiceEventResponse> = {},
): ServiceEventResponse {
  return {
    id: 1,
    eventType: 'CREATED',
    note: 'Servicio registrado',
    createdBy: { id: 1, username: 'cscaramutti', fullName: 'Carlos Scaramutti' },
    createdAt: '2026-08-24T02:00:00Z',
    ...overrides,
  }
}

/**
 * Fixture del detalle: lo devuelven tanto el alta como `GET /services/{id}`. Los
 * valores no repiten los del resumen
 * (otro id, otro código): si el test afirma el código de un servicio recién creado
 * y el fixture compartiera el del listado, la aserción pasaría midiendo la fila
 * equivocada.
 */
export function fakeServiceDetail(
  overrides: Partial<ServiceDetailResponse> = {},
): ServiceDetailResponse {
  return {
    id: 77,
    code: 'SRV-0077',
    client: { id: 12, name: 'IPH S.A.C.', ruc: '20123456789' },
    origin: 'Piura',
    destination: 'Lima — Callao',
    tentativeDate: '2026-09-10',
    tripScope: 'PROVINCIA',
    cargoType: { id: 3, name: 'CARGA GENERAL' },
    weightKg: 28000,
    lengthM: 12.5,
    widthM: null,
    heightM: null,
    observations: null,
    price: 5800,
    currencyCode: 'PEN',
    status: 'PENDING_ASSIGNMENT',
    driver: null,
    tractor: null,
    trailer: null,
    startDateTime: null,
    endDateTime: null,
    additionalResources: [],
    events: [],
    createdBy: { id: 1, username: 'cscaramutti', fullName: 'Carlos Scaramutti' },
    // Las dos fechas de auditoría son DISTINTAS a propósito: compartiendo valor,
    // mostrar una bajo el rótulo de la otra sería un no-op y ningún test lo vería.
    //
    // `updatedAt` lleva los microsegundos con el cero final YA RECORTADO, que es
    // como los serializa Jackson. El ETag del header trae el mismo instante con el
    // cero puesto: ese es el near-miss que hace fallar un `If-Match` armado desde
    // el cuerpo.
    createdAt: '2026-08-24T15:00:00Z',
    updatedAt: '2026-08-26T13:20:00.39289Z',
    ...overrides,
  }
}

/**
 * ETag por defecto del detalle. Es el `updatedAt` del cuerpo CASI igual: mismo
 * instante, pero con los microsegundos completos y entre comillas.
 *
 * El parecido es el punto. El error que el contrato advierte es armar el
 * `If-Match` desde el cuerpo, y ese error solo se caza con dos valores que se
 * parezcan: Jackson recorta el cero final de los microsegundos, así que el cuerpo
 * dice `.39289Z` donde el header dice `.392890Z` y el servidor contesta un 412
 * espurio. Con dos fechas distintas, un test que solo compare "son diferentes"
 * pasaría sin medir nada.
 */
export const DEFAULT_SERVICE_ETAG = '"2026-08-26T13:20:00.392890Z"'

/** Detalle de un servicio, con su ETag en el header. */
export function serviceDetailOk(
  service: ServiceDetailResponse = fakeServiceDetail(),
  etag: string = DEFAULT_SERVICE_ETAG,
) {
  return http.get(`${API}/services/:id`, () => HttpResponse.json(service, { headers: { ETag: etag } }))
}

/** Detalle SIN el header ETag (un gateway que no lo expone). */
export function serviceDetailWithoutEtag(service: ServiceDetailResponse = fakeServiceDetail()) {
  return http.get(`${API}/services/:id`, () => HttpResponse.json(service))
}

/** Detalle que falla (Problem RFC 7807). */
export function serviceDetailError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/services/:id`, () => problemResponse(status, problem))
}

/** Detalle que tarda, para observar el estado de carga. */
export function serviceDetailSlow(ms = 40, service: ServiceDetailResponse = fakeServiceDetail()) {
  return http.get(`${API}/services/:id`, async () => {
    await delay(ms)
    return HttpResponse.json(service, { headers: { ETag: DEFAULT_SERVICE_ETAG } })
  })
}

/**
 * Alta correcta: responde 201 con el detalle dado.
 *
 * Por defecto iguala `updatedAt` a `createdAt`: el fixture base los separa a
 * propósito para el detalle, pero un viaje recién creado no puede haberse
 * actualizado dos días después.
 */
export function createServiceOk(
  service: ServiceDetailResponse = fakeServiceDetail({ updatedAt: fakeServiceDetail().createdAt }),
) {
  return http.post(`${API}/services`, () => HttpResponse.json(service, { status: 201 }))
}

/**
 * Captura el CUERPO del alta. Sin esto un test solo puede afirmar que la pantalla
 * no explotó: lo que importa es qué se mandó, y un campo que viaja en null o que
 * no viaja pasa desapercibido.
 */
export function createServiceCapture(
  sink: { body?: ServiceCreateRequest },
  service: ServiceDetailResponse = fakeServiceDetail(),
) {
  return http.post(`${API}/services`, async ({ request }) => {
    sink.body = (await request.json()) as ServiceCreateRequest
    return HttpResponse.json(service, { status: 201 })
  })
}

/** Alta rechazada por repetida (409 `OPS-007`), el anti doble-click del contrato. */
export function createServiceDuplicate(
  detail = 'Ya se registró un servicio igual para este cliente hace instantes.',
) {
  return http.post(`${API}/services`, () =>
    HttpResponse.json(
      {
        type: 'urn:tms:error:ops-007',
        title: 'Conflict',
        status: 409,
        code: 'OPS-007',
        detail,
        traceId: 'test',
      },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Alta rechazada con errores por campo (400), como los devuelve Bean Validation. */
export function createServiceFieldErrors(errors: { field: string; message: string }[]) {
  return http.post(`${API}/services`, () =>
    HttpResponse.json(
      {
        type: 'urn:tms:error:com-001',
        title: 'Bad Request',
        status: 400,
        code: 'COM-001',
        detail: 'La solicitud tiene errores de validación.',
        errors,
        traceId: 'test',
      },
      { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Alta rechazada por el veto de precios (403 `COM-003`). */
export function createServiceForbidden(
  detail = 'Registrar un servicio exige poder ver los importes.',
) {
  return http.post(`${API}/services`, () =>
    HttpResponse.json(
      {
        type: 'urn:tms:error:com-003',
        title: 'Forbidden',
        status: 403,
        code: 'COM-003',
        detail,
        traceId: 'test',
      },
      { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Alta que tarda, para observar el botón mientras el envío está en curso. */
export function createServiceSlow(ms = 40, service: ServiceDetailResponse = fakeServiceDetail()) {
  return http.post(`${API}/services`, async () => {
    await delay(ms)
    return HttpResponse.json(service, { status: 201 })
  })
}

/**
 * Handlers por defecto (camino feliz). Son CUATRO y ahora el orden IMPORTA, a
 * diferencia de cuando eran tres: `/services/:id` es un patrón CON parámetro y se
 * tragaría `/services/stats` como si "stats" fuera un id, así que el de los
 * indicadores tiene que registrarse antes. MSW resuelve por orden de registro.
 */


// ----- Recursos del viaje (asignación, refuerzos y baja) -----

/**
 * ETag de una respuesta de ESCRITURA. Distinto del de la lectura a propósito: el
 * detalle que devuelve una escritura trae su propia versión, y guardar la vieja
 * dejaría al próximo `If-Match` pidiendo con una versión que la base ya no tiene.
 */
export const ETAG_AFTER_WRITE = '"2026-08-27T09:05:12.100450Z"'

/**
 * Fixture de un refuerzo del viaje. Sus recursos son DISTINTOS de los principales y
 * del otro refuerzo: si la pantalla pintara un refuerzo donde va el principal, o uno
 * donde va el otro, el valor cambia y algún caso lo ve.
 */
export function fakeAdditionalResource(
  overrides: Partial<ServiceAdditionalResourceResponse> = {},
): ServiceAdditionalResourceResponse {
  return {
    id: 51,
    driver: { id: 8, fullName: 'Ana Ríos Chávez' },
    tractor: { kind: 'TRACTOR', id: 11, plate: 'V1B-911' },
    trailer: null,
    // Ni 10 ni 500 caracteres: son los dos literales de los mensajes de validación, y
    // con cualquiera de ellos un contador no se distinguiría de un límite impreso.
    reason: 'Relevo por descanso reglamentario del conductor principal, coordinado con la garita.',
    assignedBy: { id: 2, username: 'jvega', fullName: 'Jorge Vega' },
    assignedAt: '2026-08-24T02:00:00Z',
    ...overrides,
  }
}

/** Fixture de conductor (`public.drivers`). */
export function fakeDriver(overrides: Partial<DriverResponse> = {}): DriverResponse {
  return {
    id: 4,
    fullName: 'Juan Pérez Huamán',
    licenseNumber: 'Q12345678',
    licenseCategory: 'A-IIIC',
    phone: '987654321',
    status: 'AVAILABLE',
    isActive: true,
    ...overrides,
  }
}

/**
 * Padrón por defecto. Los tres traen un `status` DISTINTO, y no por adorno: el
 * contrato dice que la disponibilidad no prohíbe asignar, así que un campo que
 * filtrara por disponible se llevaría dos de los tres y el fixture lo delata.
 */
export const DRIVERS = [
  fakeDriver(),
  fakeDriver({ id: 8, fullName: 'Ana Ríos Chávez', licenseNumber: 'Q22222222', status: 'MAINTENANCE' }),
  fakeDriver({
    id: 15,
    fullName: 'Luis Quispe Mamani',
    licenseNumber: 'Q33333333',
    status: 'NOT_AVAILABLE',
  }),
]

/** Responde el padrón de conductores. */
export function driversList(content: DriverResponse[] = DRIVERS) {
  return http.get(`${API}/drivers`, () => HttpResponse.json(content))
}

/** Captura los query params del padrón de conductores. */
export function driversCapture(sink: ServicesCaptureSink, content: DriverResponse[] = DRIVERS) {
  sink.calls = []
  return http.get(`${API}/drivers`, ({ request }) => {
    const params = new URL(request.url).searchParams
    sink.params = params
    sink.calls = [...(sink.calls ?? []), params]
    return HttpResponse.json(content)
  })
}

/** Responde un error en el padrón de conductores. */
export function driversError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/drivers`, () => problemResponse(status, problem))
}

/** Cuerpos de los pedidos de asignación observados, EN ORDEN. */
export interface AssignCaptureSink {
  bodies?: AssignResourcesRequest[]
}

/** Asignación exitosa: devuelve el detalle y su ETag. */
export function assignResourcesOk(
  service: ServiceDetailResponse = fakeServiceDetail({ status: 'PENDING_START' }),
  etag: string = ETAG_AFTER_WRITE,
) {
  return http.post(`${API}/services/:id/assignment`, () =>
    HttpResponse.json(service, { status: 200, headers: { ETag: etag } }),
  )
}

/**
 * Captura los cuerpos de la asignación. Guarda el HISTORIAL y no el último: sin él,
 * afirmar el reintento forzado no distingue "mandó dos veces" de "mandó una sola y
 * el primer intento nunca salió".
 */
export function assignResourcesCapture(
  sink: AssignCaptureSink,
  service: ServiceDetailResponse = fakeServiceDetail({ status: 'PENDING_START' }),
  etag: string = ETAG_AFTER_WRITE,
) {
  sink.bodies = []
  return http.post(`${API}/services/:id/assignment`, async ({ request }) => {
    sink.bodies = [...(sink.bodies ?? []), (await request.json()) as AssignResourcesRequest]
    return HttpResponse.json(service, { status: 200, headers: { ETag: etag } })
  })
}

/** Asignación lenta, para medir el doble envío. */
export function assignResourcesSlow(sink: AssignCaptureSink, ms = 40) {
  sink.bodies = []
  return http.post(`${API}/services/:id/assignment`, async ({ request }) => {
    sink.bodies = [...(sink.bodies ?? []), (await request.json()) as AssignResourcesRequest]
    await delay(ms)
    return HttpResponse.json(fakeServiceDetail({ status: 'PENDING_START' }), {
      status: 200,
      headers: { ETag: ETAG_AFTER_WRITE },
    })
  })
}

/** Un recurso tomado por otro viaje, tal como lo publica el `Problem` del 409. */
export const DRIVER_CONFLICT = {
  resource: 'DRIVER',
  resourceName: 'Juan Pérez Huamán',
  serviceCode: 'SRV-0042',
  serviceStatus: 'IN_PROGRESS',
} as const

/**
 * Tres conflictos, uno por clase de recurso, con TRES nombres, TRES códigos de viaje
 * y los DOS estados que el contrato admite. Con tres filas iguales, una tabla que
 * repitiera la primera en las tres pasaría igual.
 */
export const THREE_CONFLICTS = [
  DRIVER_CONFLICT,
  {
    resource: 'TRACTOR',
    resourceName: 'T7A-701',
    serviceCode: 'SRV-0100',
    serviceStatus: 'PENDING_START',
  },
  {
    resource: 'TRAILER',
    resourceName: 'R3C-303',
    serviceCode: 'SRV-0311',
    serviceStatus: 'IN_PROGRESS',
  },
] as const

type ResourceConflicts = NonNullable<ServiceResourceConflictProblem['conflicts']>

function conflictProblem(
  detail: string,
  conflicts: ResourceConflicts,
): ServiceResourceConflictProblem {
  return {
    type: 'urn:tms:error:ops-002',
    title: 'Resource conflict',
    status: 409,
    detail,
    instance: '/api/v1/services/77/assignment',
    code: 'OPS-002',
    traceId: '3f2a1b0c-9d8e-7f6a-5b4c-3d2e1f0a9b8c',
    forcible: true,
    conflicts,
  }
}

/** 409 `OPS-002`: FORZABLE, con `forcible` y `conflicts` aplanados junto a `code`. */
export function assignResourcesConflict(
  conflicts: ResourceConflicts = [DRIVER_CONFLICT],
  detail = 'El conductor Juan Pérez Huamán ya está asignado al servicio SRV-0042 (en ruta).',
) {
  return http.post(`${API}/services/:id/assignment`, () =>
    HttpResponse.json(conflictProblem(detail, conflicts), {
      status: 409,
      headers: { 'Content-Type': 'application/problem+json' },
    }),
  )
}

/**
 * Rechaza el primer intento con el conflicto forzable y acepta el segundo. Es el
 * handler que sostiene el caso central de la pantalla: el reintento con `force`.
 */
export function assignConflictThenOk(
  sink: AssignCaptureSink,
  service: ServiceDetailResponse = fakeServiceDetail({ status: 'PENDING_START' }),
) {
  sink.bodies = []
  return http.post(`${API}/services/:id/assignment`, async ({ request }) => {
    sink.bodies = [...(sink.bodies ?? []), (await request.json()) as AssignResourcesRequest]
    if (sink.bodies.length === 1) {
      return HttpResponse.json(
        conflictProblem(
          'El conductor Juan Pérez Huamán ya está asignado al servicio SRV-0042 (en ruta).',
          [DRIVER_CONFLICT],
        ),
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
      )
    }
    return HttpResponse.json(service, { status: 200, headers: { ETag: ETAG_AFTER_WRITE } })
  })
}

/** Un `Problem` PELADO del módulo: sin `forcible` ni `conflicts`. */
export function serviceOperationProblem(
  path: 'assignment' | 'resources',
  code: string,
  detail: string,
  status = 409,
) {
  return http.post(`${API}/services/:id/${path}`, () =>
    HttpResponse.json(
      {
        type: `urn:tms:error:${code.toLowerCase()}`,
        title: 'Conflict',
        status,
        detail,
        instance: `/api/v1/services/77/${path}`,
        code,
        traceId: '7c6b5a49-3d2e-1f0a-9b8c-7d6e5f4a3b2c',
      },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** La asignación se cae por red, sin cuerpo que mostrar. */
export function assignResourcesNetworkError() {
  return http.post(`${API}/services/:id/assignment`, () => HttpResponse.error())
}

/**
 * Un detalle por estado, con el CEBO de las acciones puesto: sin recursos asignados
 * (cebo del botón de asignar) y con DOS refuerzos vivos (cebo de los de refuerzo).
 *
 * Son formas que el servidor no emite: un viaje completado sin conductor no existe.
 * Se arman así a propósito. Si la visibilidad de un botón se decidiera por el DATO en
 * vez de por el ESTADO, los casos de ausencia pasarían igual sin medir nada: "no se
 * ofrece quitar un refuerzo" sería cierto solo porque no hay refuerzos.
 *
 * El código cambia por estado para que montar el fixture equivocado se note.
 */
export function fakeBaitedServiceDetail(status: ServiceStatus): ServiceDetailResponse {
  const codeByStatus: Record<ServiceStatus, string> = {
    PENDING_ASSIGNMENT: 'SRV-0701',
    PENDING_START: 'SRV-0702',
    IN_PROGRESS: 'SRV-0703',
    COMPLETED: 'SRV-0704',
    CANCELLED: 'SRV-0705',
    DELETED: 'SRV-0706',
  }
  return fakeServiceDetail({
    status,
    code: codeByStatus[status],
    driver: null,
    tractor: null,
    trailer: null,
    additionalResources: [
      fakeAdditionalResource(),
      fakeAdditionalResource({
        id: 52,
        driver: { id: 15, fullName: 'Luis Quispe Mamani' },
        tractor: null,
        trailer: { kind: 'TRAILER', id: 9, plate: 'Z9D-909' },
        reason: 'Se suma una carreta de apoyo para redistribuir la carga en el km 214.',
      }),
    ],
  })
}

/** Cuerpos de los pedidos de refuerzo observados, EN ORDEN. */
export interface AddResourcesCaptureSink {
  bodies?: AddResourcesRequest[]
}

/** Refuerzo sumado: devuelve el detalle y su ETag. */
export function addResourcesOk(
  service: ServiceDetailResponse = fakeServiceDetail({
    status: 'IN_PROGRESS',
    additionalResources: [fakeAdditionalResource()],
  }),
  etag: string = ETAG_AFTER_WRITE,
) {
  return http.post(`${API}/services/:id/resources`, () =>
    HttpResponse.json(service, { status: 200, headers: { ETag: etag } }),
  )
}

/** Captura los cuerpos del alta de refuerzos, con historial. */
export function addResourcesCapture(
  sink: AddResourcesCaptureSink,
  service: ServiceDetailResponse = fakeServiceDetail({
    status: 'IN_PROGRESS',
    additionalResources: [fakeAdditionalResource()],
  }),
) {
  sink.bodies = []
  return http.post(`${API}/services/:id/resources`, async ({ request }) => {
    sink.bodies = [...(sink.bodies ?? []), (await request.json()) as AddResourcesRequest]
    return HttpResponse.json(service, { status: 200, headers: { ETag: ETAG_AFTER_WRITE } })
  })
}

/**
 * 409 `OPS-003`: el recurso ya participa de ESTE viaje. DURO, y el `Problem` viaja
 * PELADO, sin `forcible` ni `conflicts`. Es la forma real, y por eso el caso negativo
 * se prueba contra ella y no contra un `forcible: false`, que el backend nunca manda.
 */
export function addResourcesDuplicate(
  detail = 'El conductor Ana Ríos Chávez ya participa de este servicio.',
) {
  return http.post(`${API}/services/:id/resources`, () =>
    HttpResponse.json(
      {
        type: 'urn:tms:error:ops-003',
        title: 'Duplicate resource',
        status: 409,
        detail,
        instance: '/api/v1/services/77/resources',
        code: 'OPS-003',
        traceId: '8e7d6c5b-4a39-2817-0f6e-5d4c3b2a1908',
      },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** 409 `OPS-002` sobre el alta de refuerzos: forzable, con su detalle. */
export function addResourcesConflict(
  conflicts: ResourceConflicts = [DRIVER_CONFLICT],
  detail = 'El conductor Ana Ríos Chávez ya está asignado al servicio SRV-0042 (en ruta).',
) {
  return http.post(`${API}/services/:id/resources`, () =>
    HttpResponse.json(
      { ...conflictProblem(detail, conflicts), instance: '/api/v1/services/77/resources' },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Rechaza el primer alta de refuerzo con el conflicto forzable y acepta la segunda. */
export function addConflictThenOk(
  sink: AddResourcesCaptureSink,
  service: ServiceDetailResponse = fakeServiceDetail({
    status: 'IN_PROGRESS',
    additionalResources: [fakeAdditionalResource()],
  }),
) {
  sink.bodies = []
  return http.post(`${API}/services/:id/resources`, async ({ request }) => {
    sink.bodies = [...(sink.bodies ?? []), (await request.json()) as AddResourcesRequest]
    if (sink.bodies.length === 1) {
      return HttpResponse.json(
        {
          ...conflictProblem(
            'El conductor Ana Ríos Chávez ya está asignado al servicio SRV-0042 (en ruta).',
            [DRIVER_CONFLICT],
          ),
          instance: '/api/v1/services/77/resources',
        },
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
      )
    }
    return HttpResponse.json(service, { status: 200, headers: { ETag: ETAG_AFTER_WRITE } })
  })
}

/** Bajas de refuerzo observadas, con los DOS ids del path. */
export interface RemoveResourceCaptureSink {
  calls?: { id: string; assignmentId: string }[]
}

/** Baja exitosa: devuelve el detalle que queda y su ETag (200, no 204). */
export function removeResourceOk(
  service: ServiceDetailResponse = fakeServiceDetail({
    status: 'IN_PROGRESS',
    additionalResources: [],
  }),
  etag: string = ETAG_AFTER_WRITE,
) {
  return http.delete(`${API}/services/:id/resources/:assignmentId`, () =>
    HttpResponse.json(service, { status: 200, headers: { ETag: etag } }),
  )
}

/** Captura los dos ids del path de la baja. */
export function removeResourceCapture(
  sink: RemoveResourceCaptureSink,
  service: ServiceDetailResponse = fakeServiceDetail({
    status: 'IN_PROGRESS',
    additionalResources: [],
  }),
) {
  sink.calls = []
  return http.delete(`${API}/services/:id/resources/:assignmentId`, ({ params }) => {
    sink.calls = [
      ...(sink.calls ?? []),
      { id: params.id as string, assignmentId: params.assignmentId as string },
    ]
    return HttpResponse.json(service, { status: 200, headers: { ETag: ETAG_AFTER_WRITE } })
  })
}

/** Baja lenta, para medir el doble clic. */
export function removeResourceSlow(sink: RemoveResourceCaptureSink, ms = 40) {
  sink.calls = []
  return http.delete(`${API}/services/:id/resources/:assignmentId`, async ({ params }) => {
    sink.calls = [
      ...(sink.calls ?? []),
      { id: params.id as string, assignmentId: params.assignmentId as string },
    ]
    await delay(ms)
    return HttpResponse.json(
      fakeServiceDetail({ status: 'IN_PROGRESS', additionalResources: [] }),
      { status: 200, headers: { ETag: ETAG_AFTER_WRITE } },
    )
  })
}

/**
 * 404 `OPS-010`: el refuerzo no existe, o existe pero es de OTRO viaje. Los dos casos
 * responden lo MISMO a propósito, para que el 404 no sea un canal para averiguar qué
 * refuerzos hay vivos en viajes que quien pregunta podría no poder leer.
 */
export function removeResourceNotFound(
  detail = 'El recurso adicional no existe o no pertenece a este servicio.',
) {
  return http.delete(`${API}/services/:id/resources/:assignmentId`, () =>
    HttpResponse.json(
      {
        type: 'urn:tms:error:ops-010',
        title: 'Not found',
        status: 404,
        detail,
        instance: '/api/v1/services/77/resources/51',
        code: 'OPS-010',
        traceId: '9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d',
      },
      { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Un 409 sobre la baja (el estado no la admite, o el viaje está cerrado). */
export function removeResourceConflict(code: string, detail: string) {
  return http.delete(`${API}/services/:id/resources/:assignmentId`, () =>
    HttpResponse.json(
      {
        type: `urn:tms:error:${code.toLowerCase()}`,
        title: 'Conflict',
        status: 409,
        detail,
        instance: '/api/v1/services/77/resources/51',
        code,
        traceId: '2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e',
      },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Camino feliz por defecto del módulo. */
export const operationsHandlers = [
  serviceStatsOk(),
  serviceDetailOk(),
  servicesPage([fakeServiceSummary()]),
  createServiceOk(),
  driversList(),
]
