import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  updateWarehouseWithdrawal,
  type FleetUnitRef,
  type WarehouseWithdrawalUpdateRequest,
} from '../../../api'
import { readEtag } from '../../../shared/utils/etag'
import { trimToNull } from '../../../shared/utils/trimToNull'
import { warehouseKeys } from '../queryKeys'
import { fleetUnitToWithdrawalFields } from './useCreateWarehouseWithdrawal'
import type { WarehouseWithdrawalWithEtag } from './useWarehouseWithdrawal'
import type { WithdrawalEditFormInput } from '../schemas/withdrawal.schema'

interface UpdateWithdrawalVariables {
  id: number
  /** ETag OPACO del header del GET (no el `updatedAt` del body), para el header `If-Match`. */
  ifMatch: string
  body: WarehouseWithdrawalUpdateRequest
}

/**
 * Form de edición → body del PUT.
 *
 * - NO incluye `productId`: el producto es INMUTABLE (RN-WH4) y el contrato no lo
 *   acepta en `WarehouseWithdrawalUpdateRequest`. Un producto equivocado se
 *   resuelve anulando el retiro y registrando otro.
 * - CON `reason`: la justificación obligatoria que va a la auditoría.
 * - La unidad de flota se REEMPLAZA: la elegida va a su campo según el `kind` y los
 *   tres en null la quitan (mismo reparto que en el alta).
 */
export function toWithdrawalUpdateRequest(
  input: WithdrawalEditFormInput,
  fleetUnit: Pick<FleetUnitRef, 'kind' | 'id'> | null,
): WarehouseWithdrawalUpdateRequest {
  return {
    quantity: input.quantity,
    receivedByWorkerId: input.receivedByWorkerId,
    ...fleetUnitToWithdrawalFields(fleetUnit),
    observations: trimToNull(input.observations),
    reason: input.reason.trim(),
  }
}

async function performUpdateWithdrawal({
  id,
  ifMatch,
  body,
}: UpdateWithdrawalVariables): Promise<WarehouseWithdrawalWithEtag> {
  const { data, headers } = await updateWarehouseWithdrawal({
    path: { id },
    headers: { 'If-Match': ifMatch },
    body,
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en PUT /warehouse/withdrawals/{id}')
  }
  // El PUT devuelve un ETag nuevo en el header: lo adjunto para refrescar la cache
  // del detalle con la versión vigente (una segunda edición seguida no choca 412).
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Edita un retiro (`PUT /warehouse/withdrawals/{id}`) con optimistic locking vía
 * `If-Match`. Los errores llegan como AxiosError para que el form distinga el 412
 * (otro usuario cambió el retiro primero) del 409 (WH-001 si subir la cantidad
 * excede el disponible sin contar este retiro, WH-008 si está anulado).
 *
 * Editar puede mover el stock, así que en éxito refresca el detalle con el ETag
 * nuevo e invalida lo derivado del stock: el listado, los productos (Existencias,
 * ficha, combobox), los indicadores y el kardex.
 */
export function useUpdateWarehouseWithdrawal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performUpdateWithdrawal,
    onSuccess: (updated) => {
      queryClient.setQueryData(warehouseKeys.withdrawalDetail(updated.id), updated)
      queryClient.invalidateQueries({ queryKey: warehouseKeys.withdrawalLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.products() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.kardexes() })
    },
  })
}
