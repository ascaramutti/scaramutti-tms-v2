import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createUserAccount,
  type UserAccountCreateRequest,
  type UserAccountTemporaryPasswordResponse,
} from '../../../api'
import { workerKeys } from '../../workers/queryKeys'
import { userAccountKeys } from '../queryKeys'
import { refreshAfterAccountSaved } from './refreshAfterAccountSaved'

export function useCreateUserAccount() {
  const queryClient = useQueryClient()
  return useMutation<UserAccountTemporaryPasswordResponse, unknown, UserAccountCreateRequest>({
    mutationKey: userAccountKeys.create(),
    mutationFn: async (body) => {
      const { data } = await createUserAccount({ body, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en POST /users')
      }
      return data
    },
    // Al detalle va solo la cuenta: la temporal no entra en la caché de consultas. La ficha del
    // trabajador dice si tiene usuario, y eso acaba de cambiar.
    onSuccess: async (created) => {
      await refreshAfterAccountSaved(queryClient, created.account)
      await queryClient.invalidateQueries({ queryKey: workerKeys.detail(created.account.worker.id), refetchType: 'none' })
    },
    // gcTime 0: la caché de mutaciones guarda la respuesta, y acá trae la temporal.
    gcTime: 0,
  })
}
