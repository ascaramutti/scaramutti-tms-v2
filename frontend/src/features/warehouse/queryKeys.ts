import type {
  GetWarehouseProductKardexData,
  ListWarehouseProductsData,
  ListWarehousePurchaseInvoicesData,
} from '../../api'

type WarehouseProductListParams = NonNullable<ListWarehouseProductsData['query']>
type WarehouseKardexParams = NonNullable<GetWarehouseProductKardexData['query']>
type WarehousePurchaseInvoiceListParams = NonNullable<
  ListWarehousePurchaseInvoicesData['query']
>

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
  // Rama aparte de `productList` a propósito: la búsqueda del combobox usa otros
  // params y otra vida de cache, y compartir la key haría que una invalidación
  // del listado tirara abajo el dropdown abierto (y al revés).
  productSearches: () => [...warehouseKeys.products(), 'search'] as const,
  productSearch: (q: string) => [...warehouseKeys.productSearches(), q] as const,
  productDetail: (id: number) => [...warehouseKeys.products(), 'detail', id] as const,
  kardexes: () => [...warehouseKeys.all, 'kardex'] as const,
  kardex: (productId: number, params: WarehouseKardexParams) =>
    [...warehouseKeys.kardexes(), productId, params] as const,
  stats: () => [...warehouseKeys.all, 'stats'] as const,
  productCategories: () => [...warehouseKeys.all, 'product-categories'] as const,
  purchaseInvoices: () => [...warehouseKeys.all, 'purchase-invoices'] as const,
  purchaseInvoiceLists: () => [...warehouseKeys.purchaseInvoices(), 'list'] as const,
  purchaseInvoiceList: (params: WarehousePurchaseInvoiceListParams) =>
    [...warehouseKeys.purchaseInvoiceLists(), params] as const,
  suppliers: () => [...warehouseKeys.all, 'suppliers'] as const,
  supplierSearches: () => [...warehouseKeys.suppliers(), 'search'] as const,
  supplierSearch: (q: string) => [...warehouseKeys.supplierSearches(), q] as const,
  unitsOfMeasure: () => [...warehouseKeys.all, 'units-of-measure'] as const,
}
