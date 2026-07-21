import type { ListWarehouseProductsData } from '../../api'

type WarehouseProductListParams = NonNullable<ListWarehouseProductsData['query']>

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
  stats: () => [...warehouseKeys.all, 'stats'] as const,
  productCategories: () => [...warehouseKeys.all, 'product-categories'] as const,
}
