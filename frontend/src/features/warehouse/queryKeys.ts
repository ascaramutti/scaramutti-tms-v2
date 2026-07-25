import type {
  GetWarehouseProductKardexData,
  ListWarehouseProductsData,
  ListWarehousePurchaseInvoicesData,
  ListWarehouseWithdrawalsData,
} from '../../api'

type WarehouseProductListParams = NonNullable<ListWarehouseProductsData['query']>
type WarehouseKardexParams = NonNullable<GetWarehouseProductKardexData['query']>
type WarehousePurchaseInvoiceListParams = NonNullable<
  ListWarehousePurchaseInvoicesData['query']
>
type WarehouseWithdrawalListParams = NonNullable<ListWarehouseWithdrawalsData['query']>

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
  // Stock disponible en vivo de un producto (para validar la cantidad del retiro).
  // Cuelga de `products()` para que registrar una entrada/retiro (que invalida esa
  // rama) también refresque el disponible mostrado.
  productStock: (id: number) => [...warehouseKeys.products(), 'stock', id] as const,
  kardexes: () => [...warehouseKeys.all, 'kardex'] as const,
  kardex: (productId: number, params: WarehouseKardexParams) =>
    [...warehouseKeys.kardexes(), productId, params] as const,
  stats: () => [...warehouseKeys.all, 'stats'] as const,
  productCategories: () => [...warehouseKeys.all, 'product-categories'] as const,
  purchaseInvoices: () => [...warehouseKeys.all, 'purchase-invoices'] as const,
  purchaseInvoiceLists: () => [...warehouseKeys.purchaseInvoices(), 'list'] as const,
  purchaseInvoiceList: (params: WarehousePurchaseInvoiceListParams) =>
    [...warehouseKeys.purchaseInvoiceLists(), params] as const,
  purchaseInvoiceDetail: (id: number) =>
    [...warehouseKeys.purchaseInvoices(), 'detail', id] as const,
  suppliers: () => [...warehouseKeys.all, 'suppliers'] as const,
  supplierSearches: () => [...warehouseKeys.suppliers(), 'search'] as const,
  supplierSearch: (q: string) => [...warehouseKeys.supplierSearches(), q] as const,
  unitsOfMeasure: () => [...warehouseKeys.all, 'units-of-measure'] as const,
  withdrawals: () => [...warehouseKeys.all, 'withdrawals'] as const,
  withdrawalLists: () => [...warehouseKeys.withdrawals(), 'list'] as const,
  withdrawalList: (params: WarehouseWithdrawalListParams) =>
    [...warehouseKeys.withdrawalLists(), params] as const,
  withdrawalDetail: (id: number) => [...warehouseKeys.withdrawals(), 'detail', id] as const,
  // Catálogos COMPARTIDOS con operaciones (public.workers / fleet_units), no del
  // schema almacén, pero se consultan desde acá: el combobox de "quién recibe" y el
  // de unidad de flota. Rama propia para no cruzarlos con nada del almacén.
  workerSearches: () => [...warehouseKeys.all, 'workers', 'search'] as const,
  workerSearch: (q: string) => [...warehouseKeys.workerSearches(), q] as const,
  fleetUnits: () => [...warehouseKeys.all, 'fleet-units'] as const,
}
