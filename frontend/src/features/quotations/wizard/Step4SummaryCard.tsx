import { useFormContext } from 'react-hook-form'
import { QuotationSummaryCard } from '../components/QuotationSummaryCard'
import type { WizardFormInput } from './quotation-wizard.schema'
import type { ClientResponse, CurrencyResponse, PaymentTermResponse } from '../../../api'

interface Step4SummaryCardProps {
  selectedClient: ClientResponse | null
  currencies: CurrencyResponse[]
  paymentTerms: PaymentTermResponse[]
}

/**
 * Adaptador del Resumen: lee los datos del FORM (+ catálogos) y delega el render en
 * {@link QuotationSummaryCard} (mismo look que el Detalle, que los arma desde la API). El wizard
 * no tiene un `QuotationResponse` todavía, por eso resuelve currency/paymentTerm de los catálogos.
 */
export function Step4SummaryCard({ selectedClient, currencies, paymentTerms }: Step4SummaryCardProps) {
  const { watch } = useFormContext<WizardFormInput>()
  const quotationType = watch('quotationType')
  const contactName = watch('contactName')
  const contactPhone = watch('contactPhone')
  const currencyId = watch('currencyId')
  const paymentTermId = watch('paymentTermId')
  const validityDays = watch('validityDays')
  const origin = watch('origin')
  const destination = watch('destination')
  const tentativeServiceDate = watch('tentativeServiceDate')

  const currency = currencies.find((item) => item.id === currencyId)
  const paymentTerm = paymentTerms.find((item) => item.id === paymentTermId)

  return (
    <QuotationSummaryCard
      clientName={selectedClient?.name ?? null}
      clientRuc={selectedClient?.ruc ?? null}
      contactName={contactName}
      contactPhone={contactPhone}
      quotationType={quotationType}
      origin={origin}
      destination={destination}
      currencyCode={currency?.code ?? null}
      paymentTermName={paymentTerm?.name ?? null}
      validityDays={validityDays}
      tentativeServiceDate={tentativeServiceDate}
    />
  )
}
