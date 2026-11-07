import { useQuery } from '@tanstack/react-query'
import { getUserAccount, type UserAccountDetailResponse } from '../../../api'
import { isForbiddenError, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { userAccountKeys } from '../queryKeys'

export function useUserAccount(id: number) {
  return useQuery<UserAccountDetailResponse>({
    queryKey: userAccountKeys.detail(id),
    queryFn: async () => {
      const { data } = await getUserAccount({ path: { id }, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en GET /users/{id}')
      }
      return data
    },
    enabled: Number.isInteger(id) && id > 0,
    staleTime: 0,
    retry: (intentos, error) => !isNotFoundError(error) && !isForbiddenError(error) && intentos < 1,
  })
}
