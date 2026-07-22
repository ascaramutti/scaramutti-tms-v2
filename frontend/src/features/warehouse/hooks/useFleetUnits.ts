import { useQuery } from '@tanstack/react-query'
import { listFleetUnits, type FleetUnitResponse } from '../../../api'
import { warehouseKeys } from '../queryKeys'

/** La flota cambia poco: una carga sirve para varias aperturas del combobox. */
const FLEET_UNITS_STALE_TIME_MS = 5 * 60_000

async function fetchFleetUnits(): Promise<FleetUnitResponse[]> {
  const { data } = await listFleetUnits({
    // Solo unidades vigentes: un retiro no se asigna a una unidad dada de baja.
    query: { isActive: true },
    throwOnError: true,
  })
  return data ?? []
}

/**
 * Carga las unidades de flota activas (tractos, carretas y escoltas) para el
 * combobox opcional del retiro. El contrato de `/fleet-units` NO acepta `q`: la
 * flota es chica y sin paginar, así que se trae entera una vez y el combobox
 * filtra en cliente por placa/marca/modelo.
 */
export function useFleetUnits() {
  return useQuery({
    queryKey: warehouseKeys.fleetUnits(),
    queryFn: fetchFleetUnits,
    staleTime: FLEET_UNITS_STALE_TIME_MS,
  })
}
