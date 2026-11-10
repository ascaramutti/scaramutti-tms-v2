import { useMutation, useQueryClient } from '@tanstack/react-query'
import { resetUserAccountPassword, type UserAccountTemporaryPasswordResponse } from '../../../api'
import { refreshAfterAccountSaved } from './refreshAfterAccountSaved'

export function useResetUserAccountPassword(id: number) {
  const queryClient = useQueryClient()
  return useMutation<UserAccountTemporaryPasswordResponse, unknown, void>({
    mutationFn: async () => {
      const { data } = await resetUserAccountPassword({ path: { id }, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en POST /users/{id}/reset-password')
      }
      return data
    },
    // Al detalle va solo la cuenta: la temporal no entra en la caché de consultas.
    onSuccess: (response) => refreshAfterAccountSaved(queryClient, response.account),
    // gcTime 0: la caché de mutaciones guarda la respuesta, y acá trae la temporal.
    gcTime: 0,
  })
}
