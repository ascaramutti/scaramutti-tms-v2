import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listWarehouseProducts, type PageOfWarehouseProduct } from '../../../api'
import { warehouseKeys } from '../queryKeys'

/** Mínimo de caracteres para que la búsqueda de productos golpee el backend. */
export const PRODUCT_SEARCH_MIN_LENGTH = 3
/** Ventana en la que una misma búsqueda se sirve del cache, sin refetch. */
const SEARCH_STALE_TIME_MS = 60_000
/** Tamaño de página del combobox: suficiente para elegir, no es un listado. */
const PRODUCT_SEARCH_PAGE_SIZE = 10

async function fetchWarehouseProducts(query: string): Promise<PageOfWarehouseProduct> {
  const { data } = await listWarehouseProducts({
    // Solo productos vigentes: una entrada no puede sumar stock a uno dado de baja.
    query: { q: query, isActive: true, size: PRODUCT_SEARCH_PAGE_SIZE },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/products')
  }
  return data
}

/**
 * Búsqueda de productos para el combobox de los ítems de la entrada. Solo
 * dispara con >= 3 chars; `keepPreviousData` evita el parpadeo entre tecleos.
 */
export function useWarehouseProductsSearch(query: string) {
  const trimmed = query.trim()
  return useQuery({
    queryKey: warehouseKeys.productSearch(trimmed),
    queryFn: () => fetchWarehouseProducts(trimmed),
    enabled: trimmed.length >= PRODUCT_SEARCH_MIN_LENGTH,
    placeholderData: keepPreviousData,
    // Reabrir el combobox con el mismo texto no vuelve a golpear el backend: el
    // catálogo de productos cambia poco dentro de una misma carga de factura.
    staleTime: SEARCH_STALE_TIME_MS,
  })
}
