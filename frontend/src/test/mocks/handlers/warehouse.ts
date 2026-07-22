import { http, HttpResponse, delay } from 'msw'
import type {
  PageMeta,
  PageOfWarehouseKardexMovement,
  PageOfWarehouseProduct,
  PageOfWarehousePurchaseInvoiceSummary,
  PageOfWarehouseSupplier,
  Problem,
  UserResponse,
  WarehouseKardexMovementResponse,
  WarehouseProductCategoryResponse,
  WarehouseProductResponse,
  WarehousePurchaseInvoiceResponse,
  WarehousePurchaseInvoiceSummary,
  WarehouseStatsResponse,
  WarehouseSupplierResponse,
  WarehouseUnitOfMeasureResponse,
} from '../../../api'

const API = 'http://localhost:8080/api/v1'

/** ETag del producto por defecto: NO coincide con el `updatedAt` del body. */
export const DEFAULT_PRODUCT_ETAG = '"2026-05-20T10:00:00.392890Z"'
/**
 * ETag por defecto de la entrada. El cero final NO aparece en el `updatedAt` del
 * body (Jackson lo recorta), así que si el `If-Match` armado desde el header
 * coincidiera con el body sería por accidente: fija que el cancel usa el header.
 */
export const DEFAULT_INVOICE_ETAG = '"2026-07-02T10:00:00.392890Z"'

export const AUDIT_USER: UserResponse = {
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

/**
 * Metadatos de una página a partir de la cantidad de filas y los overrides. Lo
 * comparten todos los `pageOf*`: la aritmética de paginado es la misma para
 * productos, kardex, proveedores y facturas.
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

/** Envuelve un array de productos en una página completa (PageMeta + content). */
export function pageOfProducts(
  content: WarehouseProductResponse[],
  meta: Partial<PageOfWarehouseProduct> = {},
): PageOfWarehouseProduct {
  return { ...pageMeta(content.length, meta), content }
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
  return { ...pageMeta(content.length, meta), content }
}

/** Fixture de proveedor. */
export function fakeSupplier(
  overrides: Partial<WarehouseSupplierResponse> = {},
): WarehouseSupplierResponse {
  return {
    id: 4,
    name: 'REPUESTOS DIÉSEL S.A.C.',
    ruc: '20512345678',
    phone: '987654321',
    contactName: 'Marta Ríos',
    isActive: true,
    createdAt: '2026-04-01T10:00:00Z',
    ...overrides,
  }
}

/** Envuelve proveedores en una página completa. */
export function pageOfSuppliers(
  content: WarehouseSupplierResponse[],
  meta: Partial<PageOfWarehouseSupplier> = {},
): PageOfWarehouseSupplier {
  return { ...pageMeta(content.length, meta), content }
}

/** Catálogo de unidades de medida, ordenado por código como lo devuelve el backend. */
export const UNITS_OF_MEASURE_SEED: WarehouseUnitOfMeasureResponse[] = [
  { id: 5, code: 'CJA', name: 'Caja', isActive: true },
  { id: 2, code: 'GAL', name: 'Galón', isActive: true },
  { id: 4, code: 'KG', name: 'Kilogramo', isActive: true },
  { id: 3, code: 'LT', name: 'Litro', isActive: true },
  { id: 1, code: 'UND', name: 'Unidad', isActive: true },
]

/** Fixture de fila del listado de entradas. */
export function fakeInvoiceSummary(
  overrides: Partial<WarehousePurchaseInvoiceSummary> = {},
): WarehousePurchaseInvoiceSummary {
  return {
    id: 1,
    supplier: { id: 4, name: 'REPUESTOS DIÉSEL S.A.C.' },
    invoiceNumber: 'F001-00123',
    invoiceDate: '2026-07-02',
    guideNumber: 'T001-0004567',
    currencyCode: 'PEN',
    itemsCount: 2,
    total: 894,
    status: 'ACTIVE',
    cancelReason: null,
    registeredBy: AUDIT_USER,
    createdAt: '2026-07-02T10:00:00Z',
    ...overrides,
  }
}

/** Envuelve facturas en una página completa. */
export function pageOfInvoices(
  content: WarehousePurchaseInvoiceSummary[],
  meta: Partial<PageOfWarehousePurchaseInvoiceSummary> = {},
): PageOfWarehousePurchaseInvoiceSummary {
  return { ...pageMeta(content.length, meta), content }
}

/** Fixture de la factura completa que devuelve el POST. */
export function fakeInvoice(
  overrides: Partial<WarehousePurchaseInvoiceResponse> = {},
): WarehousePurchaseInvoiceResponse {
  return {
    id: 1,
    supplier: { id: 4, name: 'REPUESTOS DIÉSEL S.A.C.', ruc: '20512345678' },
    invoiceNumber: 'F001-00123',
    invoiceDate: '2026-07-02',
    guideNumber: 'T001-0004567',
    currency: { id: 2, code: 'PEN', symbol: 'S/' },
    observations: null,
    items: [
      {
        id: 1,
        product: { id: 1, code: 'PRO-0001', name: 'Filtro de aceite XYZ', unitCode: 'UND' },
        quantity: 10,
        unitPrice: 45,
        subtotal: 450,
      },
    ],
    total: 450,
    status: 'ACTIVE',
    registeredBy: AUDIT_USER,
    createdAt: '2026-07-02T10:00:00Z',
    updatedAt: '2026-07-02T10:00:00Z',
    ...overrides,
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
  http.post(`${API}/warehouse/products`, () =>
    HttpResponse.json(fakeProduct({ id: 42, code: 'PRO-0042' }), { status: 201 }),
  ),
  http.get(`${API}/warehouse/units-of-measure`, () => HttpResponse.json(UNITS_OF_MEASURE_SEED)),
  http.get(`${API}/warehouse/suppliers`, () =>
    HttpResponse.json(
      pageOfSuppliers([
        fakeSupplier(),
        fakeSupplier({ id: 5, name: 'LUBRICANTES DEL NORTE E.I.R.L.', ruc: '20698765432' }),
      ]),
    ),
  ),
  http.post(`${API}/warehouse/suppliers`, () =>
    HttpResponse.json(fakeSupplier({ id: 99, name: 'Proveedor nuevo' }), { status: 201 }),
  ),
  http.get(`${API}/warehouse/purchase-invoices`, () =>
    HttpResponse.json(
      pageOfInvoices([
        fakeInvoiceSummary(),
        fakeInvoiceSummary({
          id: 2,
          invoiceNumber: 'F001-00124',
          supplier: { id: 5, name: 'LUBRICANTES DEL NORTE E.I.R.L.' },
          guideNumber: null,
          itemsCount: 1,
          total: 320.5,
        }),
        fakeInvoiceSummary({
          id: 3,
          invoiceNumber: 'F002-00001',
          status: 'CANCELLED',
          cancelReason: 'Factura cargada dos veces',
        }),
      ]),
    ),
  ),
  http.post(`${API}/warehouse/purchase-invoices`, () =>
    HttpResponse.json(fakeInvoice(), { status: 201 }),
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

// ----- Entradas (facturas de compra), proveedores y unidades de medida -----

/** Sink de un POST: body enviado y cantidad de llamadas observadas. */
export interface BodyCaptureSink {
  body?: Record<string, unknown>
  calls?: Record<string, unknown>[]
}

/** Responde una página fija de entradas. */
export function warehouseInvoicesPage(
  content: WarehousePurchaseInvoiceSummary[],
  meta?: Partial<PageOfWarehousePurchaseInvoiceSummary>,
) {
  return http.get(`${API}/warehouse/purchase-invoices`, () =>
    HttpResponse.json(pageOfInvoices(content, meta)),
  )
}

/** Responde un listado de entradas vacío. */
export function warehouseInvoicesEmpty() {
  return http.get(`${API}/warehouse/purchase-invoices`, () =>
    HttpResponse.json(pageOfInvoices([])),
  )
}

/** Responde el listado de entradas con un delay (para observar el estado de carga). */
export function warehouseInvoicesSlow(
  content: WarehousePurchaseInvoiceSummary[] = [],
  ms = 40,
) {
  return http.get(`${API}/warehouse/purchase-invoices`, async () => {
    await delay(ms)
    return HttpResponse.json(pageOfInvoices(content))
  })
}

/** Responde un error en el listado de entradas (Problem RFC 7807). */
export function warehouseInvoicesError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/purchase-invoices`, () => problemResponse(status, problem))
}

/** Responde el detalle de una entrada con su ETag en el header. */
export function warehouseInvoiceDetail(
  invoice: WarehousePurchaseInvoiceResponse = fakeInvoice(),
  etag: string = DEFAULT_INVOICE_ETAG,
) {
  return http.get(`${API}/warehouse/purchase-invoices/:id`, () =>
    HttpResponse.json(invoice, { headers: { ETag: etag } }),
  )
}

/** Responde el detalle SIN el header ETag (gateway que no lo expone). */
export function warehouseInvoiceDetailWithoutEtag(
  invoice: WarehousePurchaseInvoiceResponse = fakeInvoice(),
) {
  return http.get(`${API}/warehouse/purchase-invoices/:id`, () => HttpResponse.json(invoice))
}

/** Responde el detalle con un delay (para observar el estado de carga). */
export function warehouseInvoiceDetailSlow(
  invoice: WarehousePurchaseInvoiceResponse = fakeInvoice(),
  ms = 40,
) {
  return http.get(`${API}/warehouse/purchase-invoices/:id`, async () => {
    await delay(ms)
    return HttpResponse.json(invoice, { headers: { ETag: DEFAULT_INVOICE_ETAG } })
  })
}

/** Responde un error en el detalle (404 entrada inexistente WH-003, 500 fallo). */
export function warehouseInvoiceDetailError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/purchase-invoices/:id`, () => problemResponse(status, problem))
}

/** Primera respuesta del detalle y siguientes: para probar la recarga tras un 412. */
export function warehouseInvoiceDetailSequence(
  responses: { invoice: WarehousePurchaseInvoiceResponse; etag: string }[],
) {
  let call = 0
  return http.get(`${API}/warehouse/purchase-invoices/:id`, () => {
    const current = responses[Math.min(call, responses.length - 1)]
    call += 1
    return HttpResponse.json(current.invoice, { headers: { ETag: current.etag } })
  })
}

/** Captura el body `{ reason }` y el If-Match del POST de anulación. */
export function cancelInvoiceSuccess(
  sink: UpdateCaptureSink,
  response: WarehousePurchaseInvoiceResponse = fakeInvoice({
    status: 'CANCELLED',
    cancelReason: 'Factura cargada dos veces por error',
    cancelledBy: AUDIT_USER,
    cancelledAt: '2026-07-06T10:00:00Z',
  }),
) {
  return http.post(`${API}/warehouse/purchase-invoices/:id/cancel`, async ({ request }) => {
    sink.body = (await request.json()) as Record<string, unknown>
    sink.ifMatch = request.headers.get('If-Match')
    return HttpResponse.json(response, { headers: { ETag: '"v2"' } })
  })
}

/** Responde el POST de anulación con un delay (para observar el estado en vuelo). */
export function cancelInvoiceSlow(sink: UpdateCaptureSink, ms = 40) {
  return http.post(`${API}/warehouse/purchase-invoices/:id/cancel`, async ({ request }) => {
    sink.body = (await request.json()) as Record<string, unknown>
    sink.ifMatch = request.headers.get('If-Match')
    await delay(ms)
    return HttpResponse.json(
      fakeInvoice({ status: 'CANCELLED', cancelReason: 'x'.repeat(12), cancelledBy: AUDIT_USER }),
      { headers: { ETag: '"v2"' } },
    )
  })
}

/** Responde un error en la anulación (412 versión vencida, 409 WH-006/WH-008, 400, 500). */
export function cancelInvoiceError(status: number, problem: Partial<Problem> = {}) {
  return http.post(`${API}/warehouse/purchase-invoices/:id/cancel`, () =>
    problemResponse(status, problem),
  )
}

/** Captura el body y el If-Match del PUT de edición de la entrada. */
export function updateInvoiceSuccess(
  sink: UpdateCaptureSink,
  response: WarehousePurchaseInvoiceResponse = fakeInvoice(),
) {
  return http.put(`${API}/warehouse/purchase-invoices/:id`, async ({ request }) => {
    sink.body = (await request.json()) as Record<string, unknown>
    sink.ifMatch = request.headers.get('If-Match')
    return HttpResponse.json(response, { headers: { ETag: '"v2"' } })
  })
}

/** Responde el PUT de edición con un delay (para observar el estado "Guardando…"). */
export function updateInvoiceSlow(sink: UpdateCaptureSink, ms = 40) {
  return http.put(`${API}/warehouse/purchase-invoices/:id`, async ({ request }) => {
    sink.body = (await request.json()) as Record<string, unknown>
    sink.ifMatch = request.headers.get('If-Match')
    await delay(ms)
    return HttpResponse.json(fakeInvoice(), { headers: { ETag: '"v2"' } })
  })
}

/** Responde un error en la edición (412 COM-004, 409 WH-002/WH-006/WH-008, 400, 500). */
export function updateInvoiceError(status: number, problem: Partial<Problem> = {}) {
  return http.put(`${API}/warehouse/purchase-invoices/:id`, () => problemResponse(status, problem))
}

/** Captura los query params de cada request del listado de entradas. */
export function warehouseInvoicesCapture(
  sink: ProductsCaptureSink,
  content: WarehousePurchaseInvoiceSummary[] = [fakeInvoiceSummary()],
  meta?: Partial<PageOfWarehousePurchaseInvoiceSummary>,
) {
  sink.calls = []
  return http.get(`${API}/warehouse/purchase-invoices`, ({ request }) => {
    const params = new URL(request.url).searchParams
    sink.params = params
    sink.calls = [...(sink.calls ?? []), params]
    return HttpResponse.json(pageOfInvoices(content, meta))
  })
}

/** Responde según el `page` solicitado (para testear navegación entre páginas). */
export function warehouseInvoicesPagedByParam(totalElements = 25, size = 10) {
  return http.get(`${API}/warehouse/purchase-invoices`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    return HttpResponse.json(
      pageOfInvoices([fakeInvoiceSummary({ id: page * 100 + 1, invoiceNumber: `F00${page}-1` })], {
        totalElements,
        size,
        page,
      }),
    )
  })
}

/** OK en la página 0, error en las siguientes (un refetch fallido no borra la tabla). */
export function warehouseInvoicesOkThenErrorOnNextPage(
  content: WarehousePurchaseInvoiceSummary[],
  meta: Partial<PageOfWarehousePurchaseInvoiceSummary> = {},
) {
  return http.get(`${API}/warehouse/purchase-invoices`, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    if (page === 0) return HttpResponse.json(pageOfInvoices(content, meta))
    return problemResponse(500, { detail: 'Fallo al paginar' })
  })
}

/** Captura el body del POST de la entrada. */
export function createInvoiceCapture(
  sink: BodyCaptureSink,
  response: WarehousePurchaseInvoiceResponse = fakeInvoice(),
) {
  sink.calls = []
  return http.post(`${API}/warehouse/purchase-invoices`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>
    sink.body = body
    sink.calls = [...(sink.calls ?? []), body]
    return HttpResponse.json(response, { status: 201 })
  })
}

/** Responde el POST de la entrada con un delay (para observar el estado en vuelo). */
export function createInvoiceSlow(sink: BodyCaptureSink, ms = 40) {
  sink.calls = []
  return http.post(`${API}/warehouse/purchase-invoices`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>
    sink.calls = [...(sink.calls ?? []), body]
    await delay(ms)
    return HttpResponse.json(fakeInvoice(), { status: 201 })
  })
}

/** Responde un error en el POST de la entrada (409 WH-002, 400 WH-004, 500). */
export function createInvoiceError(status: number, problem: Partial<Problem> = {}) {
  return http.post(`${API}/warehouse/purchase-invoices`, () => problemResponse(status, problem))
}

/** Responde la búsqueda de proveedores con la página dada. */
export function warehouseSuppliersPage(content: WarehouseSupplierResponse[]) {
  return http.get(`${API}/warehouse/suppliers`, () =>
    HttpResponse.json(pageOfSuppliers(content)),
  )
}

/** Captura los query params de la búsqueda de proveedores. */
export function warehouseSuppliersCapture(
  sink: ProductsCaptureSink,
  content: WarehouseSupplierResponse[] = [fakeSupplier()],
) {
  sink.calls = []
  return http.get(`${API}/warehouse/suppliers`, ({ request }) => {
    const params = new URL(request.url).searchParams
    sink.params = params
    sink.calls = [...(sink.calls ?? []), params]
    return HttpResponse.json(pageOfSuppliers(content))
  })
}

/** Responde un error en la búsqueda de proveedores. */
export function warehouseSuppliersError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/suppliers`, () => problemResponse(status, problem))
}

/** Captura el body del POST de proveedor (crear al vuelo). */
export function createSupplierCapture(
  sink: BodyCaptureSink,
  response: WarehouseSupplierResponse = fakeSupplier({ id: 99, name: 'Proveedor nuevo' }),
) {
  sink.calls = []
  return http.post(`${API}/warehouse/suppliers`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>
    sink.body = body
    sink.calls = [...(sink.calls ?? []), body]
    return HttpResponse.json(response, { status: 201 })
  })
}

/** Responde un error al crear el proveedor (409 WH-010 duplicado). */
export function createSupplierError(status: number, problem: Partial<Problem> = {}) {
  return http.post(`${API}/warehouse/suppliers`, () => problemResponse(status, problem))
}

/** Captura el body del POST de producto (alta al vuelo). */
export function createWarehouseProductCapture(
  sink: BodyCaptureSink,
  response: WarehouseProductResponse = fakeProduct({ id: 42, code: 'PRO-0042' }),
) {
  sink.calls = []
  return http.post(`${API}/warehouse/products`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>
    sink.body = body
    sink.calls = [...(sink.calls ?? []), body]
    return HttpResponse.json(response, { status: 201 })
  })
}

/** Responde un error al crear el producto (409 WH-010 identidad duplicada). */
export function createWarehouseProductError(status: number, problem: Partial<Problem> = {}) {
  return http.post(`${API}/warehouse/products`, () => problemResponse(status, problem))
}

/** Responde el catálogo de unidades de medida con la lista dada. */
export function warehouseUnitsOfMeasure(units: WarehouseUnitOfMeasureResponse[]) {
  return http.get(`${API}/warehouse/units-of-measure`, () => HttpResponse.json(units))
}

/** Responde un error en el catálogo de unidades de medida. */
export function warehouseUnitsOfMeasureError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/units-of-measure`, () => problemResponse(status, problem))
}
