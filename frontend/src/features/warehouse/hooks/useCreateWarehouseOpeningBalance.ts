import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWarehouseOpeningBalance,
  type WarehouseOpeningBalanceRequest,
  type WarehouseOpeningBalanceResponse,
} from '../../../api'
import { trimToNull } from '../../../shared/utils/trimToNull'
import { warehouseKeys } from '../queryKeys'
import type { OpeningBalanceFormInput } from '../schemas/opening-balance.schema'

/** Form → body del POST. */
export function toOpeningBalanceRequest(
  input: OpeningBalanceFormInput,
): WarehouseOpeningBalanceRequest {
  return {
    productId: input.productId,
    quantity: input.quantity,
    observations: trimToNull(input.observations),
  }
}

async function performCreateWarehouseOpeningBalance(
  body: WarehouseOpeningBalanceRequest,
): Promise<WarehouseOpeningBalanceResponse> {
  const { data } = await createWarehouseOpeningBalance({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/opening-balances')
  }
  return data
}

/**
 * Registra el corte inicial de un producto. El error llega como AxiosError
 * (`throwOnError`) para que el form distinga los dos 409 (WH-009 apertura ya
 * registrada, WH-011 producto con movimientos) del 400.
 *
 * La apertura es el primer movimiento del kardex y fija el stock de arranque, así
 * que invalida todo lo que se deriva del stock, igual que una entrada o un retiro,
 * más su propio listado.
 */
export function useCreateWarehouseOpeningBalance() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateWarehouseOpeningBalance,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: warehouseKeys.openingBalanceLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.products() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.kardexes() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
    },
  })
}
