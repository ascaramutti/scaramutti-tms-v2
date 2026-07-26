import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWarehouseWithdrawal,
  type FleetUnitRef,
  type WarehouseWithdrawalRequest,
  type WarehouseWithdrawalResponse,
} from '../../../api'
import { trimToNull } from '../../../shared/utils/trimToNull'
import { warehouseKeys } from '../queryKeys'
import type { WithdrawalFormInput } from '../schemas/withdrawal.schema'

/** El trío disyunto del contrato: la unidad elegida va a su campo según el `kind`. */
type FleetUnitFields = Pick<
  WarehouseWithdrawalRequest,
  'tractorId' | 'trailerId' | 'escortVehicleId'
>

/**
 * Reparte la unidad de flota elegida en los tres campos disyuntos del contrato
 * (a lo sumo uno no nulo, RN-WH2). Sin unidad → los tres en null. Reutilizable
 * por el filtro del listado, que también desglosa la unidad por `kind`.
 */
export function fleetUnitToWithdrawalFields(
  fleetUnit: Pick<FleetUnitRef, 'kind' | 'id'> | null,
): FleetUnitFields {
  return {
    tractorId: fleetUnit?.kind === 'TRACTOR' ? fleetUnit.id : null,
    trailerId: fleetUnit?.kind === 'TRAILER' ? fleetUnit.id : null,
    escortVehicleId: fleetUnit?.kind === 'ESCORT' ? fleetUnit.id : null,
  }
}

/** Form → body del POST. La unidad de flota es opcional (puede venir null). */
export function toWithdrawalRequest(
  input: WithdrawalFormInput,
  fleetUnit: Pick<FleetUnitRef, 'kind' | 'id'> | null,
): WarehouseWithdrawalRequest {
  return {
    productId: input.productId,
    quantity: input.quantity,
    receivedByWorkerId: input.receivedByWorkerId,
    ...fleetUnitToWithdrawalFields(fleetUnit),
    observations: trimToNull(input.observations),
  }
}

async function performCreateWarehouseWithdrawal(
  body: WarehouseWithdrawalRequest,
): Promise<WarehouseWithdrawalResponse> {
  const { data } = await createWarehouseWithdrawal({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/withdrawals')
  }
  return data
}

/**
 * Registra un retiro. El error llega como AxiosError (`throwOnError`) para que el
 * form distinga el 409 (stock insuficiente, WH-001) del 400.
 *
 * Retirar mueve el stock, así que invalida todo lo que se deriva de él: el listado
 * de retiros, las existencias, el kardex y los indicadores. Simétrico con el
 * registro de una entrada.
 */
export function useCreateWarehouseWithdrawal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateWarehouseWithdrawal,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: warehouseKeys.withdrawalLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.products() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.kardexes() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
    },
  })
}
