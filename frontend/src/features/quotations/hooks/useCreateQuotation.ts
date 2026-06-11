import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createQuotation, type QuotationRequest, type QuotationResponse } from '../../../api'
import { quotationKeys } from '../queryKeys'

async function performCreateQuotation(body: QuotationRequest): Promise<QuotationResponse> {
  const { data } = await createQuotation({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /quotations')
  }
  return data
}

/**
 * Crea una cotización (`POST /quotations`). `throwOnError: true` para que el error
 * (ej. 400 validación, 409 anti-duplicado QUO-002) llegue como AxiosError y el wizard
 * lo muestre con `getApiErrorMessage`. Invalida los listados para que la nueva aparezca.
 */
export function useCreateQuotation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateQuotation,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: quotationKeys.lists() })
    },
  })
}
