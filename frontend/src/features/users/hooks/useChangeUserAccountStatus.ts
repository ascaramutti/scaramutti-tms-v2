import { useMutation, useQueryClient } from '@tanstack/react-query'
import { deactivateUserAccount, reactivateUserAccount, type UserAccountDetailResponse } from '../../../api'
import { refreshAfterAccountSaved } from './refreshAfterAccountSaved'

export type AccountStatusAction = 'deactivate' | 'reactivate'

export function useChangeUserAccountStatus(id: number) {
  const queryClient = useQueryClient()
  return useMutation<UserAccountDetailResponse, unknown, AccountStatusAction>({
    mutationFn: async (action) => {
      const send = action === 'deactivate' ? deactivateUserAccount : reactivateUserAccount
      const { data } = await send({ path: { id }, throwOnError: true })
      if (!data) {
        throw new Error(`Respuesta vacía del backend en POST /users/{id}/${action}`)
      }
      return data
    },
    onSuccess: (changed) => refreshAfterAccountSaved(queryClient, changed),
  })
}
