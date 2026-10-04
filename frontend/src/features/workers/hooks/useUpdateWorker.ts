import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateWorker, type WorkerDetailResponse, type WorkerUpdateRequest } from '../../../api'
import { workerKeys } from '../queryKeys'
import { refreshAfterWorkerSaved } from './refreshAfterWorkerSaved'

/** La edición de un trabajador: un reemplazo, con las mismas invalidaciones que el alta. */
export function useUpdateWorker(id: number) {
  const queryClient = useQueryClient()
  return useMutation<WorkerDetailResponse, unknown, WorkerUpdateRequest>({
    mutationKey: workerKeys.update(id),
    mutationFn: async (body) => {
      const { data } = await updateWorker({ path: { id }, body, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en PUT /workers/{id}')
      }
      return data
    },
    onSuccess: (updated) => refreshAfterWorkerSaved(queryClient, updated),
  })
}
