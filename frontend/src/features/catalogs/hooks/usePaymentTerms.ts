import { useQuery } from '@tanstack/react-query'
import { listPaymentTerms, type PaymentTermResponse } from '../../../api'
import { catalogKeys } from '../queryKeys'

async function fetchPaymentTerms(): Promise<PaymentTermResponse[]> {
  const { data } = await listPaymentTerms({ query: { isActive: true }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /payment-terms')
  }
  return data
}

/** Condiciones de pago activas. Catálogo casi inmutable → `staleTime: Infinity`. */
export function usePaymentTerms() {
  return useQuery({
    queryKey: catalogKeys.paymentTerms(),
    queryFn: fetchPaymentTerms,
    staleTime: Infinity,
  })
}
