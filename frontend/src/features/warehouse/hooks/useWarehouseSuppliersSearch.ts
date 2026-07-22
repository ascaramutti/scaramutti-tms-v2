import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listWarehouseSuppliers, type PageOfWarehouseSupplier } from '../../../api'
import { warehouseKeys } from '../queryKeys'

/** Mínimo de caracteres para que la búsqueda de proveedores golpee el backend. */
export const SUPPLIER_SEARCH_MIN_LENGTH = 3
/** Ventana en la que una misma búsqueda se sirve del cache, sin refetch. */
const SEARCH_STALE_TIME_MS = 60_000
/** Tamaño de página del combobox: suficiente para elegir, no es un listado. */
const SUPPLIER_SEARCH_PAGE_SIZE = 10

async function fetchWarehouseSuppliers(query: string): Promise<PageOfWarehouseSupplier> {
  const { data } = await listWarehouseSuppliers({
    query: { q: query, isActive: true, size: SUPPLIER_SEARCH_PAGE_SIZE },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/suppliers')
  }
  return data
}

/**
 * Búsqueda de proveedores para el combobox de la entrada. Solo dispara con >= 3
 * chars (regla del proyecto); `keepPreviousData` evita el parpadeo entre tecleos.
 */
export function useWarehouseSuppliersSearch(query: string) {
  const trimmed = query.trim()
  return useQuery({
    queryKey: warehouseKeys.supplierSearch(trimmed),
    queryFn: () => fetchWarehouseSuppliers(trimmed),
    enabled: trimmed.length >= SUPPLIER_SEARCH_MIN_LENGTH,
    placeholderData: keepPreviousData,
    // Reabrir el combobox con el mismo texto no vuelve a golpear el backend: el
    // catálogo de proveedores cambia poco dentro de una misma carga de factura.
    staleTime: SEARCH_STALE_TIME_MS,
  })
}
