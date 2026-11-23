import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listUserAccounts, type UserAccountSummaryResponse } from '../../../api'
import { isForbiddenError } from '../../../shared/utils/getApiErrorMessage'
import { userAccountKeys, type UserAccountListParams } from '../queryKeys'

async function fetchUserAccounts(params: UserAccountListParams): Promise<UserAccountSummaryResponse[]> {
  const { data } = await listUserAccounts({ query: params, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /users')
  }
  return data
}

export function useUserAccounts(isActive: boolean | undefined) {
  const params: UserAccountListParams = isActive === undefined ? {} : { isActive }
  return useQuery({
    queryKey: userAccountKeys.list(params),
    queryFn: () => fetchUserAccounts(params),
    placeholderData: keepPreviousData,
    staleTime: 0,
    retry: (intentos, error) => !isForbiddenError(error) && intentos < 1,
  })
}
