import { http, HttpResponse, delay } from 'msw'
import type { PageOfQuotationSummary, Problem, QuotationSummary } from '../../../api'

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

/** Default happy-path: 3 cotizaciones en una sola página. */
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
