import { http, HttpResponse, delay } from 'msw'
import type {
  PageOfWarehouseKardexMovement,
  PageOfWarehouseProduct,
  Problem,
  UserResponse,
  WarehouseKardexMovementResponse,
  WarehouseProductCategoryResponse,
  WarehouseProductResponse,
  WarehouseStatsResponse,
} from '../../../api'

const API = 'http://localhost:8080/api/v1'

/** ETag del producto por defecto: NO coincide con el `updatedAt` del body. */
export const DEFAULT_PRODUCT_ETAG = '"2026-05-20T10:00:00.392890Z"'

const AUDIT_USER: UserResponse = {
  id: 1,
  username: 'admin',
  fullName: 'Admin TMS',
  position: 'Administrador',
  role: 'admin',
  isActive: true,
}

/** Fixture base de un producto con stock. Override con `overrides`. */
export function fakeProduct(
  overrides: Partial<WarehouseProductResponse> = {},
): WarehouseProductResponse {
  return {
    id: 1,
    code: 'PRO-0001',
    name: 'Filtro de aceite XYZ',
    category: { id: 7, name: 'Filtros' },
    unitOfMeasure: { id: 1, code: 'UND', name: 'Unidad' },
    brand: 'Bosch',
    partNumber: 'F026407123',
    attributes: {},
    minStock: 4,
    observations: null,
    isActive: true,
    stock: 12,
    lowStock: false,
    createdBy: AUDIT_USER,
    createdAt: '2026-05-20T10:00:00Z',
    updatedAt: '2026-05-20T10:00:00Z',
    ...overrides,
  }
}

/** Envuelve un array de productos en una página completa (PageMeta + content). */
export function pageOfProducts(
  content: WarehouseProductResponse[],
  meta: Partial<PageOfWarehouseProduct> = {},
): PageOfWarehouseProduct {
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

/** Fixture de los KPIs del strip. */
export function fakeWarehouseStats(
  overrides: Partial<WarehouseStatsResponse> = {},
): WarehouseStatsResponse {
  return {
    activeProducts: 120,
    lowStockCount: 7,
    entriesThisMonth: 12,
    withdrawalsThisMonth: 31,
    ...overrides,
  }
}

/** Fixture de movimiento del kardex. Default: una entrada de compra. */
export function fakeKardexMovement(
  overrides: Partial<WarehouseKardexMovementResponse> = {},
): WarehouseKardexMovementResponse {
  return {
    movementType: 'ENTRADA',
    quantity: 10,
    balance: 16,
    movedAt: '2026-07-05T14:00:00Z',
    sourceId: 5,
    reference: 'Factura F001-123 · REPUESTOS DIÉSEL S.A.C.',
    registeredBy: AUDIT_USER,
    ...overrides,
  }
}

/** Envuelve movimientos del kardex en una página completa. */
export function pageOfKardex(
  content: WarehouseKardexMovementResponse[],
  meta: Partial<PageOfWarehouseKardexMovement> = {},
): PageOfWarehouseKardexMovement {
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

/** Fixture de categoría de producto. */
export function fakeProductCategory(
  overrides: Partial<WarehouseProductCategoryResponse> = {},
): WarehouseProductCategoryResponse {
  return { id: 7, name: 'Filtros', description: null, isActive: true, ...overrides }
}

/** Default happy-path: listado de productos + KPIs + catálogo de categorías. */
export const warehouseHandlers = [
  http.get(`${API}/warehouse/products`, () =>
    HttpResponse.json(
      pageOfProducts([
        fakeProduct({ id: 1, code: 'PRO-0001' }),
        fakeProduct({
          id: 2,
          code: 'PRO-0002',
          name: 'Aceite 15W40',
          category: { id: 8, name: 'Lubricantes' },
          unitOfMeasure: { id: 2, code: 'GAL', name: 'Galón' },
          stock: 2,
          minStock: 5,
          lowStock: true,
        }),
        fakeProduct({
          id: 3,
          code: 'PRO-0003',
          name: 'Faja de alternador',
          brand: null,
          partNumber: null,
        }),
      ]),
    ),
  ),
  http.get(`${API}/warehouse/stats`, () => HttpResponse.json(fakeWarehouseStats())),
  // El ETag del header difiere del `updatedAt` del body a propósito (un cero final
  // que Jackson recorta): es la única forma de que un test cace el If-Match armado
  // desde el body en vez del header.
  http.get(`${API}/warehouse/products/:id`, () =>
    HttpResponse.json(fakeProduct(), { headers: { ETag: DEFAULT_PRODUCT_ETAG } }),
  ),
  http.get(`${API}/warehouse/products/:id/kardex`, () =>
    HttpResponse.json(
      pageOfKardex([
        fakeKardexMovement({
          movementType: 'SALIDA',
          quantity: 4,
          balance: 12,
          sourceId: 9,
          movedAt: '2026-07-10T15:00:00Z',
          reference: 'Retiro RET-0009 · Juan Pérez',
        }),
        fakeKardexMovement(),
        fakeKardexMovement({
          movementType: 'APERTURA',
          quantity: 6,
          balance: 6,
          sourceId: null,
          movedAt: '2026-06-01T13:00:00Z',
          reference: 'Corte inicial',
        }),
      ]),
    ),
  ),
  http.put(`${API}/warehouse/products/:id`, () =>
    HttpResponse.json(fakeProduct(), { headers: { ETag: '"v2"' } }),
  ),
  http.post(`${API}/warehouse/product-categories`, () =>
    HttpResponse.json(fakeProductCategory({ id: 99, name: 'Categoría nueva' }), { status: 201 }),
  ),
  http.get(`${API}/warehouse/product-categories`, () =>
    HttpResponse.json([
      fakeProductCategory({ id: 7, name: 'Filtros' }),
      fakeProductCategory({ id: 8, name: 'Lubricantes' }),
    ]),
  ),
]

// ----- Overrides para server.use(...) -----

/** Respuesta de error del backend (Problem RFC 7807). Un solo lugar para el
 * content-type y la forma del body: los overrides solo eligen status y detalle. */
function problemResponse(status: number, problem: Partial<Problem> = {}) {
  return HttpResponse.json(
    { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo de prueba', ...problem },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** Sink del kardex: además de los params, el id de producto pedido. */
export interface KardexCaptureSink extends ProductsCaptureSink {
  productId?: string
}

/** Sink del PUT: body enviado y el If-Match con el que viajó. */
export interface UpdateCaptureSink {
  body?: Record<string, unknown>
  ifMatch?: string | null
}

export interface ProductsCaptureSink {
  /** Params de la última request observada. */
  params?: URLSearchParams
  /** Params de TODAS las requests observadas, en orden. */
  calls?: URLSearchParams[]
}

/** Responde una página fija con el content/meta dados. */
export function warehouseProductsPage(
  content: WarehouseProductResponse[],
  meta?: Partial<PageOfWarehouseProduct>,
) {
  return http.get(`${API}/warehouse/products`, () =>
    HttpResponse.json(pageOfProducts(content, meta)),
  )
}

/** Responde un listado vacío. */
export function warehouseProductsEmpty() {
  return http.get(`${API}/warehouse/products`, () => HttpResponse.json(pageOfProducts([])))
}

/** Responde un error (Problem RFC 7807). */
export function warehouseProductsError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/products`, () => problemResponse(status, problem))
}

/** Responde con un delay (para observar el estado de carga). */
export function warehouseProductsSlow(content: WarehouseProductResponse[] = [], ms = 40) {
  return http.get(`${API}/warehouse/products`, async () => {
    await delay(ms)
    return HttpResponse.json(pageOfProducts(content))
  })
}

/**
 * Captura los query params de cada request en `sink` (filtros/paginación/búsqueda).
 * Además de los últimos params guarda el historial: sin él, aseverar que un
 * parámetro está ausente no distingue "no se disparó la request" de "se disparó
 * sin ese parámetro".
 */
export function warehouseProductsCapture(
  sink: ProductsCaptureSink,
  content: WarehouseProductResponse[] = [],
  meta?: Partial<PageOfWarehouseProduct>,
) {
  sink.calls = []
  return http.get(`${API}/warehouse/products`, ({ request }) => {
    const params = new URL(request.url).searchParams
    sink.params = params
    sink.calls = [...(sink.calls ?? []), params]
    return HttpResponse.json(pageOfProducts(content, meta))
  })
}

/** OK en la página 0, error 500 en las páginas siguientes (para testear que un
 * refetch fallido al paginar no borra la tabla previa). */
export function warehouseProductsOkThenErrorOnNextPage(
  content: WarehouseProductResponse[],
  meta: Partial<PageOfWarehouseProduct> = {},
) {
  return http.get(`${API}/warehouse/products`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    if (page === 0) {
      return HttpResponse.json(pageOfProducts(content, meta))
    }
    return HttpResponse.json(
      { type: 'urn:tms:error:test', title: 'Error', status: 500, detail: 'Fallo al paginar' },
      { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
    )
  })
}

/** Responde según el `page` solicitado (para testear navegación entre páginas). */
export function warehouseProductsPagedByParam(totalElements = 25, size = 10) {
  return http.get(`${API}/warehouse/products`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    return HttpResponse.json(
      pageOfProducts([fakeProduct({ id: page * 100 + 1, code: `P${page}` })], {
        totalElements,
        size,
        page,
      }),
    )
  })
}

/** Responde los KPIs con los valores dados. */
export function warehouseStats(overrides: Partial<WarehouseStatsResponse> = {}) {
  return http.get(`${API}/warehouse/stats`, () =>
    HttpResponse.json(fakeWarehouseStats(overrides)),
  )
}

/** Responde los KPIs con un delay (para observar el estado de carga del strip). */
export function warehouseStatsSlow(ms = 40) {
  return http.get(`${API}/warehouse/stats`, async () => {
    await delay(ms)
    return HttpResponse.json(fakeWarehouseStats())
  })
}

/** Responde un error en los KPIs (Problem RFC 7807). */
export function warehouseStatsError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/stats`, () => problemResponse(status, problem))
}

/** Responde un error en el catálogo de categorías (Problem RFC 7807). */
export function warehouseProductCategoriesError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/product-categories`, () => problemResponse(status, problem))
}

/** Responde el catálogo de categorías con la lista dada. */
export function warehouseProductCategories(categories: WarehouseProductCategoryResponse[]) {
  return http.get(`${API}/warehouse/product-categories`, () => HttpResponse.json(categories))
}

/** Responde el detalle de un producto con su ETag en el header. */
export function warehouseProductDetail(
  product: WarehouseProductResponse = fakeProduct(),
  etag: string = DEFAULT_PRODUCT_ETAG,
) {
  return http.get(`${API}/warehouse/products/:id`, () =>
    HttpResponse.json(product, { headers: { ETag: etag } }),
  )
}

/** Responde el detalle SIN el header ETag (gateway que no lo expone). */
export function warehouseProductDetailWithoutEtag(
  product: WarehouseProductResponse = fakeProduct(),
) {
  return http.get(`${API}/warehouse/products/:id`, () => HttpResponse.json(product))
}

/** Responde el detalle con un delay (para observar el estado de carga). */
export function warehouseProductDetailSlow(
  product: WarehouseProductResponse = fakeProduct(),
  ms = 40,
) {
  return http.get(`${API}/warehouse/products/:id`, async () => {
    await delay(ms)
    return HttpResponse.json(product, { headers: { ETag: DEFAULT_PRODUCT_ETAG } })
  })
}

/** Responde un error en el detalle (404 producto inexistente, 500 fallo). */
export function warehouseProductDetailError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/products/:id`, () => problemResponse(status, problem))
}

/** Primera respuesta del detalle y siguientes: para probar la recarga tras un 412. */
export function warehouseProductDetailSequence(
  responses: { product: WarehouseProductResponse; etag: string }[],
) {
  let call = 0
  return http.get(`${API}/warehouse/products/:id`, () => {
    const current = responses[Math.min(call, responses.length - 1)]
    call += 1
    return HttpResponse.json(current.product, { headers: { ETag: current.etag } })
  })
}

/** Responde el kardex con los movimientos dados. */
export function warehouseProductKardex(
  content: WarehouseKardexMovementResponse[],
  meta?: Partial<PageOfWarehouseKardexMovement>,
) {
  return http.get(`${API}/warehouse/products/:id/kardex`, () =>
    HttpResponse.json(pageOfKardex(content, meta)),
  )
}

/** Responde un kardex sin movimientos. */
export function warehouseProductKardexEmpty() {
  return http.get(`${API}/warehouse/products/:id/kardex`, () =>
    HttpResponse.json(pageOfKardex([])),
  )
}

/** Responde un error en el kardex. */
export function warehouseProductKardexError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/products/:id/kardex`, () => problemResponse(status, problem))
}

/** Captura el id del producto y los query params de cada request del kardex. */
export function warehouseProductKardexCapture(
  sink: KardexCaptureSink,
  content: WarehouseKardexMovementResponse[] = [fakeKardexMovement()],
  meta?: Partial<PageOfWarehouseKardexMovement>,
) {
  sink.calls = []
  return http.get(`${API}/warehouse/products/:id/kardex`, ({ request, params }) => {
    const searchParams = new URL(request.url).searchParams
    sink.productId = String(params.id)
    sink.params = searchParams
    sink.calls = [...(sink.calls ?? []), searchParams]
    return HttpResponse.json(pageOfKardex(content, meta))
  })
}

/** Responde el kardex según el `page` solicitado. */
export function warehouseProductKardexPagedByParam(totalElements = 25, size = 10) {
  return http.get(`${API}/warehouse/products/:id/kardex`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    return HttpResponse.json(
      pageOfKardex([fakeKardexMovement({ reference: `Movimiento P${page}` })], {
        totalElements,
        size,
        page,
      }),
    )
  })
}

/** Captura el body y el If-Match del PUT del producto. */
export function updateWarehouseProductSuccess(
  sink: UpdateCaptureSink,
  response: WarehouseProductResponse = fakeProduct(),
) {
  return http.put(`${API}/warehouse/products/:id`, async ({ request }) => {
    sink.body = (await request.json()) as Record<string, unknown>
    sink.ifMatch = request.headers.get('If-Match')
    return HttpResponse.json(response, { headers: { ETag: '"v2"' } })
  })
}

/** Responde un error en el PUT (412 versión vencida, 409 WH-010, 400 validación). */
export function updateWarehouseProductError(status: number, problem: Partial<Problem> = {}) {
  return http.put(`${API}/warehouse/products/:id`, () => problemResponse(status, problem))
}

/** Captura el body del POST de categoría (crear al vuelo). */
export function createWarehouseProductCategorySuccess(
  sink: { body?: Record<string, unknown> },
  response: WarehouseProductCategoryResponse = fakeProductCategory({ id: 99, name: 'Sellos' }),
) {
  return http.post(`${API}/warehouse/product-categories`, async ({ request }) => {
    sink.body = (await request.json()) as Record<string, unknown>
    return HttpResponse.json(response, { status: 201 })
  })
}

/** Responde un error al crear la categoría (409 WH-010 nombre repetido). */
export function createWarehouseProductCategoryError(
  status: number,
  problem: Partial<Problem> = {},
) {
  return http.post(`${API}/warehouse/product-categories`, () => problemResponse(status, problem))
}
