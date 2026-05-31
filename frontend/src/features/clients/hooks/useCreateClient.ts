import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createClient, type ClientRequest, type ClientResponse } from '../../../api'
import { clientKeys } from '../queryKeys'

async function performCreateClient(body: ClientRequest): Promise<ClientResponse> {
  const { data } = await createClient({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /clients')
  }
  return data
}

/**
 * Crea un cliente al vuelo (desde el wizard). `throwOnError: true` para que el
 * error (ej. 409 RUC duplicado) llegue como AxiosError y el form lo mapee con
 * `handleApiFormError`. Invalida las búsquedas para que el nuevo aparezca.
 */
export function useCreateClient() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateClient,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: clientKeys.searches() })
    },
  })
}
