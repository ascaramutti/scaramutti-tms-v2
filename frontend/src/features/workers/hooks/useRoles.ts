import { useQuery } from '@tanstack/react-query'
import { listRoles, type RoleResponse } from '../../../api'
import { isForbiddenError } from '../../../shared/utils/getApiErrorMessage'
import { workerKeys } from '../queryKeys'

/** El organigrama. Cambia solo por migración: no vence solo, y un alta lo marca viejo. */
export function useRoles() {
  return useQuery<RoleResponse[]>({
    queryKey: workerKeys.roles(),
    queryFn: async () => {
      const { data } = await listRoles({ throwOnError: true })
      return data ?? []
    },
    staleTime: Infinity,
    retry: (intentos, error) => !isForbiddenError(error) && intentos < 1,
  })
}
