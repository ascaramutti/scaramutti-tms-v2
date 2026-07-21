import { http, HttpResponse, delay } from 'msw'
import type {
  PageOfWarehouseProduct,
  Problem,
  UserResponse,
  WarehouseProductCategoryResponse,
  WarehouseProductResponse,
  WarehouseStatsResponse,
} from '../../../api'

const API = 'http://localhost:8080/api/v1'

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
  http.get(`${API}/warehouse/product-categories`, () =>
    HttpResponse.json([
      fakeProductCategory({ id: 7, name: 'Filtros' }),
      fakeProductCategory({ id: 8, name: 'Lubricantes' }),
    ]),
  ),
]

// ----- Overrides para server.use(...) -----

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
  return http.get(`${API}/warehouse/products`, () =>
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
  return http.get(`${API}/warehouse/stats`, () =>
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

/** Responde un error en el catálogo de categorías (Problem RFC 7807). */
export function warehouseProductCategoriesError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/warehouse/product-categories`, () =>
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

/** Responde el catálogo de categorías con la lista dada. */
export function warehouseProductCategories(categories: WarehouseProductCategoryResponse[]) {
  return http.get(`${API}/warehouse/product-categories`, () => HttpResponse.json(categories))
}
