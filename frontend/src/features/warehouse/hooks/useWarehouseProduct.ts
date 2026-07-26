import { useQuery } from '@tanstack/react-query'
import { getWarehouseProduct, type WarehouseProductResponse } from '../../../api'
import { readEtag, type WithEtag } from '../../../shared/utils/etag'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { warehouseKeys } from '../queryKeys'

/** Producto + el ETag opaco del header, que es lo que exige el `If-Match` del PUT. */
export type WarehouseProductWithEtag = WithEtag<WarehouseProductResponse>

async function fetchWarehouseProduct(id: number): Promise<WarehouseProductWithEtag> {
  const { data, headers } = await getWarehouseProduct({ path: { id }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/products/{id}')
  }
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Detalle de un producto (+ su ETag para el optimistic locking de la edición).
 * Hereda el `staleTime` global: `stock` sale de una vista y se mueve con cada
 * entrada y retiro, así que conviene que refresque solo al revisitar.
 *
 * No reintenta ante 404 para que el estado "no encontrado" aparezca de inmediato.
 */
export function useWarehouseProduct(id: number) {
  return useQuery({
    queryKey: warehouseKeys.productDetail(id),
    queryFn: () => fetchWarehouseProduct(id),
    enabled: Number.isInteger(id) && id > 0,
    retry: (failureCount, error) => !isNotFoundError(error) && failureCount < 1,
  })
}
