import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listWorkers, type WorkerResponse } from '../../../api'
import { warehouseKeys } from '../queryKeys'

/** Mínimo de caracteres para que la búsqueda de trabajadores golpee el backend. */
export const WORKER_SEARCH_MIN_LENGTH = 3
/** Ventana en la que una misma búsqueda se sirve del cache, sin refetch. */
const SEARCH_STALE_TIME_MS = 60_000

async function fetchWorkers(query: string): Promise<WorkerResponse[]> {
  const { data } = await listWorkers({
    // Solo trabajadores vigentes: un retiro se recibe por alguien activo. El `q`
    // es multi-palabra (cada palabra matchea nombre o apellido, RN-WH14).
    query: { q: query, isActive: true },
    throwOnError: true,
  })
  return data ?? []
}

/**
 * Búsqueda de trabajadores para el combobox "quién recibe" del retiro. Solo
 * dispara con >= 3 chars; `keepPreviousData` evita el parpadeo entre tecleos.
 *
 * A diferencia de proveedores/productos, los trabajadores NO se crean al vuelo
 * desde almacén (RN-WH9): el combobox solo busca y selecciona. La respuesta es un
 * array plano (no paginado): es un catálogo compartido chico.
 */
export function useWorkersSearch(query: string) {
  const trimmed = query.trim()
  return useQuery({
    queryKey: warehouseKeys.workerSearch(trimmed),
    queryFn: () => fetchWorkers(trimmed),
    enabled: trimmed.length >= WORKER_SEARCH_MIN_LENGTH,
    placeholderData: keepPreviousData,
    staleTime: SEARCH_STALE_TIME_MS,
  })
}
