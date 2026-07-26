import { useQuery } from '@tanstack/react-query'
import {
  getWarehousePurchaseInvoice,
  type WarehousePurchaseInvoiceResponse,
} from '../../../api'
import { readEtag, type WithEtag } from '../../../shared/utils/etag'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { warehouseKeys } from '../queryKeys'

/** Factura + el ETag opaco del header, que es lo que exige el `If-Match` del cancel. */
export type WarehousePurchaseInvoiceWithEtag = WithEtag<WarehousePurchaseInvoiceResponse>

async function fetchPurchaseInvoice(id: number): Promise<WarehousePurchaseInvoiceWithEtag> {
  const { data, headers } = await getWarehousePurchaseInvoice({
    path: { id },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/purchase-invoices/{id}')
  }
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Detalle de una entrada (factura de compra) + su ETag para el `If-Match` de la
 * anulación. Hereda el `staleTime` global: el estado y los totales pueden cambiar
 * (una anulación en otra pestaña), así que conviene refrescar al revisitar.
 *
 * No reintenta ante 404 para que el estado "no encontrado" aparezca de inmediato.
 */
export function useWarehousePurchaseInvoice(id: number) {
  return useQuery({
    queryKey: warehouseKeys.purchaseInvoiceDetail(id),
    queryFn: () => fetchPurchaseInvoice(id),
    enabled: Number.isInteger(id) && id > 0,
    retry: (failureCount, error) => !isNotFoundError(error) && failureCount < 1,
  })
}
