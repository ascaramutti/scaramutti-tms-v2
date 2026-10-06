import { useMutation, useQueryClient } from '@tanstack/react-query'
import { deactivateWorker, reactivateWorker, type WorkerDetailResponse } from '../../../api'
import { refreshAfterWorkerSaved } from './refreshAfterWorkerSaved'

export type WorkerStatusAction = 'deactivate' | 'reactivate'

/** Desactivar o reactivar, con las invalidaciones de la edición: también los viajes donde pudo estar. */
export function useChangeWorkerStatus(id: number) {
  const queryClient = useQueryClient()
  return useMutation<WorkerDetailResponse, unknown, WorkerStatusAction>({
    mutationFn: async (action) => {
      const send = action === 'deactivate' ? deactivateWorker : reactivateWorker
      const { data } = await send({ path: { id }, throwOnError: true })
      if (!data) {
        throw new Error(`Respuesta vacía del backend en POST /workers/{id}/${action}`)
      }
      return data
    },
    onSuccess: (changed) => refreshAfterWorkerSaved(queryClient, changed, { mayBeAssigned: true }),
  })
}
