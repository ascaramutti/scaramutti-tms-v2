import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listWorkers, type WorkerResponse } from '../../../api'
import { workerKeys, type WorkerSearchParams } from '../queryKeys'

/** Mínimo de caracteres para que la búsqueda golpee el backend: con uno o dos, 400. */
export const REGISTRY_SEARCH_MIN_LENGTH = 3
/** Espejo del máximo que el backend exige; sin él, un término largo vuelve con 400. */
export const REGISTRY_SEARCH_MAX_LENGTH = 200

async function fetchWorkers(params: WorkerSearchParams): Promise<WorkerResponse[]> {
  const { data } = await listWorkers({
    query: {
      q: params.q,
      ...(params.isActive !== undefined && { isActive: params.isActive }),
    },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /workers')
  }
  return data
}

/**
 * Búsqueda del padrón. No reusa la de almacén: aquella fija activos para el
 * combobox de retiros, y acá el estado lo elige quien busca.
 */
export function useWorkerRegistrySearch(query: string, isActive: boolean | undefined) {
  const trimmed = query.trim()
  const params: WorkerSearchParams = {
    q: trimmed,
    ...(isActive !== undefined && { isActive }),
  }
  return useQuery({
    queryKey: workerKeys.search(params),
    queryFn: () => fetchWorkers(params),
    enabled: trimmed.length >= REGISTRY_SEARCH_MIN_LENGTH,
    placeholderData: keepPreviousData,
  })
}
