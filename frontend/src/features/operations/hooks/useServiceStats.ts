import { useQuery } from '@tanstack/react-query'
import { getServiceStats, type ServiceStatsResponse } from '../../../api'
import { operationsKeys } from '../queryKeys'

/**
 * Medio minuto, menos que el del almacén a propósito: "En ruta" y "Conductores en
 * ruta" cambian con cada arranque y cada cierre de viaje durante el día.
 */
const STATS_STALE_TIME_MS = 30_000

async function fetchServiceStats(): Promise<ServiceStatsResponse> {
  const { data } = await getServiceStats({ throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /services/stats')
  }
  return data
}

/**
 * Indicadores del tablero operativo. No recibe los filtros de la tabla: el contrato
 * no acepta parámetros, así que los contadores no reaccionan a lo que se filtre
 * abajo. El strip lo dice a la vista, no solo en su rótulo accesible.
 */
export function useServiceStats() {
  return useQuery({
    queryKey: operationsKeys.serviceStats(),
    queryFn: fetchServiceStats,
    staleTime: STATS_STALE_TIME_MS,
  })
}
