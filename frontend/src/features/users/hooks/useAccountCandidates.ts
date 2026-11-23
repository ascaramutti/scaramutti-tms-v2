import { useQuery } from '@tanstack/react-query'
import { listUserAccountCandidates, type UserAccountCandidateResponse } from '../../../api'
import { isForbiddenError } from '../../../shared/utils/getApiErrorMessage'
import { userAccountKeys } from '../queryKeys'

async function fetchAccountCandidates(): Promise<UserAccountCandidateResponse[]> {
  const { data } = await listUserAccountCandidates({ throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /user-account-candidates')
  }
  return data
}

export function useAccountCandidates() {
  return useQuery({
    queryKey: userAccountKeys.candidates(),
    queryFn: fetchAccountCandidates,
    staleTime: 0,
    // Sin caché entre visitas: la lista vieja mostraría a quien se le acaba de crear la cuenta.
    gcTime: 0,
    retry: (intentos, error) => !isForbiddenError(error) && intentos < 1,
  })
}
