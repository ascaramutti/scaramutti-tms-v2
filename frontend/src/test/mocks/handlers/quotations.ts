import { http, HttpResponse, delay } from 'msw'
import type {
  PageOfQuotationSummary,
  Problem,
  QuotationItemResponse,
  QuotationRequest,
  QuotationResponse,
  QuotationSummary,
  UserResponse,
} from '../../../api'

const API = 'http://localhost:8080/api/v1'

/** Fixture base de una cotización del listado. Override con `overrides`. */
export function fakeQuotation(overrides: Partial<QuotationSummary> = {}): QuotationSummary {
  return {
    id: 1,
    code: '2026-00001',
    quotationType: 'TRANSPORTE',
    status: 'DRAFT',
    client: { id: 1, name: 'ACME S.A.C.', ruc: '20123456789' },
    currencyCode: 'PEN',
    totalAmount: 1500.5,
    itemsCount: 3,
    validityDays: 15,
    expiresAt: '2026-06-15T00:00:00Z',
    isExpired: false,
    origin: 'Lima',
    destination: 'Arequipa',
    createdAt: '2026-05-20T10:00:00Z',
    createdBy: {
      id: 1,
      username: 'admin',
      fullName: 'Admin TMS',
      position: 'Administrador',
      role: 'admin',
      isActive: true,
    },
    ...overrides,
  }
}

/** Envuelve un array de cotizaciones en una página completa (PageMeta + content). */
export function pageOfQuotations(
  content: QuotationSummary[],
  meta: Partial<PageOfQuotationSummary> = {},
): PageOfQuotationSummary {
  const size = meta.size ?? 10
  const page = meta.page ?? 0
  const totalElements = meta.totalElements ?? content.length
  const totalPages = meta.totalPages ?? (totalElements === 0 ? 0 : Math.ceil(totalElements / size))
  return {
    content,
    page,
    size,
    totalElements,
    totalPages,
    numberOfElements: content.length,
    first: page === 0,
    last: totalPages === 0 || page >= totalPages - 1,
    empty: content.length === 0,
    ...meta,
  }
}

const AUDIT_USER: UserResponse = {
  id: 1,
  username: 'admin',
  fullName: 'Admin TMS',
  position: 'Administrador',
  role: 'admin',
  isActive: true,
}

/** Fixture de ítem de cotización (detalle). Default: ítem root simple. */
export function fakeItem(overrides: Partial<QuotationItemResponse> = {}): QuotationItemResponse {
  return {
    id: 1,
    itemNumber: 1,
    displayLabel: '1',
    serviceType: { id: 3, code: 'SPL', name: 'Servicio de transporte en Plataforma', kind: 'SERVICIO' },
    quantity: 1,
    unitPrice: 500,
    igvPercentage: 18,
    subtotal: 500,
    ...overrides,
  }
}

/** Fixture completo de detalle (QuotationResponse). Default: TRANSPORTE con un
 * Servicio Integral (2 hijos con referencia interna) + ruta. */
export function getQuotationResponse(overrides: Partial<QuotationResponse> = {}): QuotationResponse {
  return {
    id: 1,
    code: '2026-00001',
    quotationType: 'TRANSPORTE',
    status: 'DRAFT',
    client: { id: 1, name: 'ACME S.A.C.', ruc: '20123456789' },
    contactName: 'Juan Pérez',
    contactPhone: '987654321',
    currency: { id: 2, code: 'PEN', symbol: 'S/' },
    paymentTerm: { id: 3, name: '30 días', days: 30 },
    tentativeServiceDate: '2026-06-01',
    validityDays: 15,
    expiresAt: '2026-06-15T00:00:00Z',
    isExpired: false,
    origin: 'Lima',
    destination: 'Arequipa',
    totalSubtotal: 1271.19,
    totalIgv: 228.81,
    totalAmount: 1500.0,
    items: [
      fakeItem({
        id: 1,
        itemNumber: 1,
        serviceType: { id: 24, code: 'INT', name: 'Servicio Integral', kind: 'INTEGRAL' },
        unitPrice: 1500,
        subtotal: 1500,
        children: [
          fakeItem({
            id: 2,
            parentItemId: 1,
            itemNumber: 2,
            displayLabel: '1.a',
            serviceType: { id: 3, code: 'SPL', name: 'Servicio de transporte en Plataforma', kind: 'SERVICIO' },
            cargoType: { id: 7, name: 'EXCAVADORA 326' },
            weightKg: 25900,
            unitPrice: 0,
            subtotal: 0,
            internalReferencePrice: 900,
          }),
          fakeItem({
            id: 3,
            parentItemId: 1,
            itemNumber: 3,
            displayLabel: '1.b',
            serviceType: { id: 18, code: 'CES', name: 'Servicio de Escolta', kind: 'COMPLEMENTARIO' },
            unitPrice: 0,
            subtotal: 0,
            internalReferencePrice: 371.19,
          }),
        ],
      }),
    ],
    createdBy: AUDIT_USER,
    updatedBy: AUDIT_USER,
    createdAt: '2026-05-20T10:00:00Z',
    updatedAt: '2026-05-20T10:00:00Z',
    ...overrides,
  }
}

/** Default happy-path: listado (GET /quotations) + detalle (GET /quotations/:id). */
export const quotationsHandlers = [
  http.get(`${API}/quotations`, () =>
    HttpResponse.json(
      pageOfQuotations([
        fakeQuotation({ id: 1, code: '2026-00001' }),
        fakeQuotation({ id: 2, code: '2026-00002', status: 'SENT' }),
        fakeQuotation({
          id: 3,
          code: '2026-00003',
          quotationType: 'ALQUILER',
          origin: null,
          destination: null,
        }),
      ]),
    ),
  ),
  http.get(`${API}/quotations/:id`, () => {
    const quotation = getQuotationResponse()
    return HttpResponse.json(quotation, { headers: { ETag: `"${quotation.updatedAt}"` } })
  }),
  http.post(`${API}/quotations`, () =>
    HttpResponse.json(getQuotationResponse({ id: 99 }), { status: 201 }),
  ),
]

// ----- Overrides para server.use(...) -----

/** Responde una página fija con el content/meta dados. */
export function quotationsPage(
  content: QuotationSummary[],
  meta?: Partial<PageOfQuotationSummary>,
) {
  return http.get(`${API}/quotations`, () => HttpResponse.json(pageOfQuotations(content, meta)))
}

/** Responde un listado vacío. */
export function quotationsEmpty() {
  return http.get(`${API}/quotations`, () => HttpResponse.json(pageOfQuotations([])))
}

/** Responde un error (Problem RFC 7807). */
export function quotationsError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/quotations`, () =>
    HttpResponse.json(
      {
        type: 'urn:tms:error:test',
        title: 'Error',
        status,
        detail: 'Fallo de prueba',
        ...problem,
      },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Responde con un delay (para observar el estado de carga). */
export function quotationsSlow(content: QuotationSummary[] = [], ms = 40) {
  return http.get(`${API}/quotations`, async () => {
    await delay(ms)
    return HttpResponse.json(pageOfQuotations(content))
  })
}

/** Captura los query params de la request en `sink` (filtros/paginación/búsqueda). */
export function quotationsCapture(
  sink: { params?: URLSearchParams },
  content: QuotationSummary[] = [],
  meta?: Partial<PageOfQuotationSummary>,
) {
  return http.get(`${API}/quotations`, ({ request }) => {
    sink.params = new URL(request.url).searchParams
    return HttpResponse.json(pageOfQuotations(content, meta))
  })
}

/** OK en la página 0, error 500 en las páginas siguientes (para testear que un
 * refetch fallido al paginar no borra la tabla previa). */
export function quotationsOkThenErrorOnNextPage(
  content: QuotationSummary[],
  meta: Partial<PageOfQuotationSummary> = {},
) {
  return http.get(`${API}/quotations`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    if (page === 0) {
      return HttpResponse.json(pageOfQuotations(content, meta))
    }
    return HttpResponse.json(
      { type: 'urn:tms:error:test', title: 'Error', status: 500, detail: 'Fallo al paginar' },
      { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
    )
  })
}

/** Responde según el `page` solicitado (para testear navegación entre páginas). */
export function quotationsPagedByParam(totalElements = 25, size = 20) {
  return http.get(`${API}/quotations`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    return HttpResponse.json(
      pageOfQuotations([fakeQuotation({ id: page * 100 + 1, code: `P${page}` })], {
        totalElements,
        size,
        page,
      }),
    )
  })
}

// ----- Detalle (GET /quotations/:id) -----

/** Responde un detalle fijo + su header ETag (default `"<updatedAt>"`; se puede pasar uno
 * distinto para simular el desfase real header-vs-body de los microsegundos). */
export function quotationDetail(quotation: QuotationResponse, etag = `"${quotation.updatedAt}"`) {
  return http.get(`${API}/quotations/:id`, () =>
    HttpResponse.json(quotation, { headers: { ETag: etag } }),
  )
}

/** Detalle con delay (para observar el estado de carga). */
export function quotationDetailSlow(quotation: QuotationResponse, ms = 40, etag = `"${quotation.updatedAt}"`) {
  return http.get(`${API}/quotations/:id`, async () => {
    await delay(ms)
    return HttpResponse.json(quotation, { headers: { ETag: etag } })
  })
}

/** Error (Problem RFC 7807) en el detalle (ej. 404, 500). */
export function quotationDetailError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/quotations/:id`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo de prueba', ...problem },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Captura el path param `id` en `sink` (verifica que useParams alimenta el fetch). */
export function quotationDetailCapture(sink: { id?: string }, quotation: QuotationResponse) {
  return http.get(`${API}/quotations/:id`, ({ params }) => {
    sink.id = params.id as string
    return HttpResponse.json(quotation)
  })
}

// ----- Crear (POST /quotations) -----

/** POST /quotations OK: captura el body enviado en `sink` y responde 201 con el detalle. */
export function createQuotationSuccess(sink: { body?: QuotationRequest }, response?: QuotationResponse) {
  return http.post(`${API}/quotations`, async ({ request }) => {
    sink.body = (await request.json()) as QuotationRequest
    return HttpResponse.json(response ?? getQuotationResponse({ id: 99 }), { status: 201 })
  })
}

/** Error (Problem RFC 7807) al crear (ej. 400 validación, 409 anti-duplicado QUO-002). */
export function createQuotationError(status: number, problem: Partial<Problem> = {}) {
  return http.post(`${API}/quotations`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo de prueba', ...problem },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Crear con delay (para observar el botón "Guardando…" deshabilitado). */
export function createQuotationSlow(ms = 40, response?: QuotationResponse) {
  return http.post(`${API}/quotations`, async () => {
    await delay(ms)
    return HttpResponse.json(response ?? getQuotationResponse({ id: 99 }), { status: 201 })
  })
}

// ----- Editar (PUT /quotations/:id) -----

/** PUT /quotations/:id OK: captura el body + los headers (If-Match, Authorization) en `sink`
 * y responde 200 con el detalle actualizado + nuevo ETag. */
export function updateQuotationSuccess(
  sink: { body?: QuotationRequest; ifMatch?: string | null; authorization?: string | null },
  response?: QuotationResponse,
) {
  return http.put(`${API}/quotations/:id`, async ({ request }) => {
    sink.body = (await request.json()) as QuotationRequest
    sink.ifMatch = request.headers.get('If-Match')
    sink.authorization = request.headers.get('Authorization')
    const updated = response ?? getQuotationResponse()
    return HttpResponse.json(updated, { headers: { ETag: `"${updated.updatedAt}"` } })
  })
}

/** Error (Problem RFC 7807) al editar: 412 COM-004 (conflicto de versión), 400 COM-001/QUO-004,
 * 404 QUO-003. */
export function updateQuotationError(status: number, problem: Partial<Problem> = {}) {
  return http.put(`${API}/quotations/:id`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo de prueba', ...problem },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Editar con delay (para observar el botón "Guardando…" deshabilitado). */
export function updateQuotationSlow(ms = 40, response?: QuotationResponse) {
  return http.put(`${API}/quotations/:id`, async () => {
    await delay(ms)
    const updated = response ?? getQuotationResponse()
    return HttpResponse.json(updated, { headers: { ETag: `"${updated.updatedAt}"` } })
  })
}

// ----- PDF (GET /quotations/:id/pdf) -----

/** Responde un PDF binario (blob) OK. Captura el query `preview` y el header
 * `Cache-Control` del request en `sink` si se pasa. */
export function quotationPdf(
  sink?: { preview?: string | null; cacheControl?: string | null },
  content = '%PDF-1.4 mock',
) {
  return http.get(`${API}/quotations/:id/pdf`, ({ request }) => {
    if (sink) {
      sink.preview = new URL(request.url).searchParams.get('preview')
      sink.cacheControl = request.headers.get('cache-control')
    }
    return new HttpResponse(new Blob([content], { type: 'application/pdf' }), {
      status: 200,
      headers: {
        'Content-Type': 'application/pdf',
        'Content-Disposition': 'attachment; filename="cotizacion-2026-00001.pdf"',
      },
    })
  })
}

/** Error del PDF servido como Blob: igual que axios con `responseType: 'blob'`, donde
 * el body de error (Problem JSON) también llega como blob. */
export function quotationPdfError(status: number, problem: Partial<Problem> = {}) {
  return http.get(
    `${API}/quotations/:id/pdf`,
    () =>
      new HttpResponse(
        new Blob(
          [JSON.stringify({ type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo de prueba', ...problem })],
          { type: 'application/problem+json' },
        ),
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
  )
}

/** PDF con delay (para observar los botones deshabilitados mientras genera). */
export function quotationPdfSlow(ms = 40, content = '%PDF mock') {
  return http.get(`${API}/quotations/:id/pdf`, async () => {
    await delay(ms)
    return new HttpResponse(new Blob([content], { type: 'application/pdf' }), {
      status: 200,
      headers: { 'Content-Type': 'application/pdf' },
    })
  })
}
