import { useQuery } from '@tanstack/react-query'
import { getWarehouseStats, type WarehouseStatsResponse } from '../../../api'
import { warehouseKeys } from '../queryKeys'

/** Los contadores son del mes en curso: no vale la pena refetchear por foco. */
const STATS_STALE_TIME_MS = 60_000

async function fetchWarehouseStats(): Promise<WarehouseStatsResponse> {
  const { data } = await getWarehouseStats({ throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/stats')
  }
  return data
}

/**
 * KPIs del strip de Existencias. No recibe los filtros de la tabla: el contrato
 * no acepta parámetros y los KPIs son totales de TODO el almacén (por eso los
 * rótulos lo dicen explícitamente).
 */
export function useWarehouseStats() {
  return useQuery({
    queryKey: warehouseKeys.stats(),
    queryFn: fetchWarehouseStats,
    staleTime: STATS_STALE_TIME_MS,
  })
}
