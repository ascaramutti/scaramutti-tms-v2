import type {
  GetWarehouseProductKardexData,
  ListWarehouseProductsData,
} from '../../api'

type WarehouseProductListParams = NonNullable<ListWarehouseProductsData['query']>
type WarehouseKardexParams = NonNullable<GetWarehouseProductKardexData['query']>

/**
 * Query keys del dominio Almacén. Factory centralizado para que las
 * invalidaciones y los caches usen siempre la misma forma de key.
 */
export const warehouseKeys = {
  all: ['warehouse'] as const,
  products: () => [...warehouseKeys.all, 'products'] as const,
  productLists: () => [...warehouseKeys.products(), 'list'] as const,
  productList: (params: WarehouseProductListParams) =>
    [...warehouseKeys.productLists(), params] as const,
  productDetail: (id: number) => [...warehouseKeys.products(), 'detail', id] as const,
  kardexes: () => [...warehouseKeys.all, 'kardex'] as const,
  kardex: (productId: number, params: WarehouseKardexParams) =>
    [...warehouseKeys.kardexes(), productId, params] as const,
  stats: () => [...warehouseKeys.all, 'stats'] as const,
  productCategories: () => [...warehouseKeys.all, 'product-categories'] as const,
}
