import { useCurrencies } from '../../catalogs/hooks/useCurrencies'
import { usePaymentTerms } from '../../catalogs/hooks/usePaymentTerms'
import { useQuotationServiceTypes } from '../../catalogs/hooks/useQuotationServiceTypes'
import { useQuotationConditions } from '../../catalogs/hooks/useQuotationConditions'
import { useQuotationConfig } from '../hooks/useQuotationConfig'
import type {
  ConditionResponse,
  CurrencyResponse,
  PaymentTermResponse,
  QuotationServiceTypeResponse,
} from '../../../api'

/** Catálogos + config que el wizard necesita, ya resueltos (sin estados de carga). */
export interface WizardCatalogs {
  currencies: CurrencyResponse[]
  paymentTerms: PaymentTermResponse[]
  serviceTypes: QuotationServiceTypeResponse[]
  conditions: ConditionResponse[]
  defaultValidityDays: number
  igvPercentage: number
  maxRootItems: number
}

interface WizardCatalogsState {
  isLoading: boolean
  /** Catálogos consolidados cuando TODOS cargaron; `null` mientras falte alguno o haya error. */
  data: WizardCatalogs | null
  error: unknown
  refetch: () => void
}

/**
 * Carga en paralelo los catálogos + config del wizard (currencies, payment terms, service
 * types, config de cotización) y los consolida en un estado único para gatear el render con
 * spinner/error antes de montar el formulario. Extraído de `CotizacionWizardPage` para
 * compartirlo entre la creación y la edición (ambas montan el mismo `WizardForm`).
 */
export function useWizardCatalogs(): WizardCatalogsState {
  const currencies = useCurrencies()
  const paymentTerms = usePaymentTerms()
  const serviceTypes = useQuotationServiceTypes()
  const conditions = useQuotationConditions()
  const config = useQuotationConfig()

  const isLoading =
    currencies.isLoading ||
    paymentTerms.isLoading ||
    serviceTypes.isLoading ||
    conditions.isLoading ||
    config.isLoading

  const data: WizardCatalogs | null =
    currencies.data && paymentTerms.data && serviceTypes.data && conditions.data && config.data
      ? {
          currencies: currencies.data,
          paymentTerms: paymentTerms.data,
          serviceTypes: serviceTypes.data,
          conditions: conditions.data,
          defaultValidityDays: config.data.defaultValidityDays,
          igvPercentage: config.data.igvPercentage,
          maxRootItems: config.data.maxRootItems,
        }
      : null

  return {
    isLoading,
    data,
    error:
      currencies.error ?? paymentTerms.error ?? serviceTypes.error ?? conditions.error ?? config.error,
    refetch: () => {
      currencies.refetch()
      paymentTerms.refetch()
      serviceTypes.refetch()
      conditions.refetch()
      config.refetch()
    },
  }
}
