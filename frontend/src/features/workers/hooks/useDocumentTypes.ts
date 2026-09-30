import { useQuery } from '@tanstack/react-query'
import { listDocumentTypes, type DocumentTypeResponse } from '../../../api'
import { isForbiddenError } from '../../../shared/utils/getApiErrorMessage'
import { workerKeys } from '../queryKeys'

/** Los tipos de documento vigentes, con el largo y el patrón que valida el backend. */
export function useDocumentTypes() {
  return useQuery<DocumentTypeResponse[]>({
    queryKey: workerKeys.documentTypes(),
    queryFn: async () => {
      const { data } = await listDocumentTypes({ throwOnError: true })
      return data ?? []
    },
    staleTime: Infinity,
    retry: (intentos, error) => !isForbiddenError(error) && intentos < 1,
  })
}
