import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createWorker, type WorkerDetailResponse, type WorkerRequest } from '../../../api'
import { workerKeys } from '../queryKeys'
import { refreshAfterWorkerSaved } from './refreshAfterWorkerSaved'

/** El alta de un trabajador. */
export function useCreateWorker() {
  const queryClient = useQueryClient()
  return useMutation<WorkerDetailResponse, unknown, WorkerRequest>({
    mutationKey: workerKeys.create(),
    mutationFn: async (body) => {
      const { data } = await createWorker({ body, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en POST /workers')
      }
      return data
    },
    onSuccess: (created) => refreshAfterWorkerSaved(queryClient, created),
  })
}
