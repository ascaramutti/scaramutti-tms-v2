import { useQuery } from '@tanstack/react-query'
import { getWorker, type WorkerDetailResponse } from '../../../api'
import { isForbiddenError, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { workerKeys } from '../queryKeys'

/**
 * Un trabajador por id, para su ficha. No sube el tiempo de frescura heredado:
 * la edición va a vivir en esta misma pantalla. Y no reintenta el 404 ni el 403, que
 * son definitivos: los trabajadores no se borran y el rol no cambia al repetir.
 */
export function useWorker(id: number) {
  return useQuery<WorkerDetailResponse>({
    queryKey: workerKeys.detail(id),
    queryFn: async () => {
      const { data } = await getWorker({ path: { id }, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en GET /workers/{id}')
      }
      return data
    },
    enabled: Number.isInteger(id) && id > 0,
    retry: (intentos, error) => !isNotFoundError(error) && !isForbiddenError(error) && intentos < 1,
  })
}
