import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FormProvider, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { BackLink } from '../../../shared/ui/BackLink'
import { Spinner } from '../../../shared/ui/Spinner'
import { Stepper, type StepperStep, type StepStatus } from '../../../shared/ui/Stepper'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { useCurrencies } from '../../catalogs/hooks/useCurrencies'
import { usePaymentTerms } from '../../catalogs/hooks/usePaymentTerms'
import { useQuotationServiceTypes } from '../../catalogs/hooks/useQuotationServiceTypes'
import { useQuotationConfig } from '../hooks/useQuotationConfig'
import { Step1InfoGeneral } from '../wizard/Step1InfoGeneral'
import { Step2Items } from '../wizard/Step2Items'
import { StepStandBy } from '../wizard/StepStandBy'
import { Step4Resumen } from '../wizard/Step4Resumen'
import { standbyPricePaths } from '../wizard/standbyTargets'
import { WizardNav } from '../wizard/WizardNav'
import {
  STEP_FIELDS,
  WIZARD_DEFAULTS,
  wizardSchema,
  type WizardFormInput,
} from '../wizard/quotation-wizard.schema'
import type {
  ClientResponse,
  CurrencyResponse,
  PaymentTermResponse,
  QuotationServiceTypeResponse,
} from '../../../api'

const WIZARD_STEPS: StepperStep[] = [
  { label: 'Información General' },
  { label: 'Ítems' },
  { label: 'Stand-By' },
  { label: 'Resumen' },
]

const SECONDARY_BUTTON =
  'mt-4 inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500'

/**
 * Wrapper del wizard: carga catálogos + config (gate con spinner/error) y recién
 * monta el form con los defaults ya resueltos (PEN, Contado, validez del config),
 * evitando un `useEffect`+`setValue` para los defaults async.
 */
export function CotizacionWizardPage() {
  const currencies = useCurrencies()
  const paymentTerms = usePaymentTerms()
  const serviceTypes = useQuotationServiceTypes()
  const config = useQuotationConfig()

  const isLoading =
    currencies.isLoading || paymentTerms.isLoading || serviceTypes.isLoading || config.isLoading
  const ready = currencies.data && paymentTerms.data && serviceTypes.data && config.data

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div className="flex justify-center py-16">
          <Spinner size={28} label="Cargando formulario" className="text-blue-600" />
        </div>
      </div>
    )
  }

  if (!ready) {
    const error = currencies.error ?? paymentTerms.error ?? serviceTypes.error ?? config.error
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div role="alert" className="flex flex-col items-center justify-center px-6 py-16 text-center">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(error, 'No se pudo cargar el formulario de cotización.')}
          </p>
          <button
            type="button"
            onClick={() => {
              currencies.refetch()
              paymentTerms.refetch()
              serviceTypes.refetch()
              config.refetch()
            }}
            className={SECONDARY_BUTTON}
          >
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  return (
    <WizardForm
      currencies={currencies.data}
      paymentTerms={paymentTerms.data}
      serviceTypes={serviceTypes.data}
      defaultValidityDays={config.data.defaultValidityDays}
      igvPercentage={config.data.igvPercentage}
      maxRootItems={config.data.maxRootItems}
    />
  )
}

interface WizardFormProps {
  currencies: CurrencyResponse[]
  paymentTerms: PaymentTermResponse[]
  serviceTypes: QuotationServiceTypeResponse[]
  defaultValidityDays: number
  igvPercentage: number
  maxRootItems: number
}

function WizardForm({
  currencies,
  paymentTerms,
  serviceTypes,
  defaultValidityDays,
  igvPercentage,
  maxRootItems,
}: WizardFormProps) {
  const navigate = useNavigate()
  // El array nunca está vacío (USD + PEN siempre seedeados); el `?? 0` es defensivo.
  const defaultCurrencyId = currencies.find((c) => c.code === 'PEN')?.id ?? currencies[0]?.id ?? 0
  const defaultPaymentTermId =
    paymentTerms.find((term) => term.name.toLowerCase() === 'contado')?.id ?? null

  const form = useForm<WizardFormInput>({
    resolver: zodResolver(wizardSchema),
    mode: 'onTouched',
    defaultValues: {
      ...WIZARD_DEFAULTS,
      currencyId: defaultCurrencyId,
      paymentTermId: defaultPaymentTermId,
      validityDays: defaultValidityDays,
    },
  })

  const [currentStep, setCurrentStep] = useState(0)
  // Estado por paso evaluado (check/alerta). La navegación NO bloquea: el usuario va
  // y vuelve libremente; solo se señala lo que falta (gate real = submit del PR3).
  const [stepStatus, setStepStatus] = useState<Record<number, StepStatus>>({})
  // El cliente seleccionado vive acá (no en ClientField) para persistir entre steps.
  const [selectedClient, setSelectedClient] = useState<ClientResponse | null>(null)
  const lastStep = WIZARD_STEPS.length - 1

  /** Valida un paso y registra su estado (check/alerta). Pasos sin campos (placeholders) no se marcan. */
  async function markStep(step: number): Promise<void> {
    // Step 3 (Stand-By, índice 2): opcional, sin campos propios en STEP_FIELDS. Se evalúa mirando
    // SOLO los precios de los stand-by cargados (sin stand-by = válido → check).
    if (step === 2) {
      const pricePaths = standbyPricePaths(form.getValues('items'))
      const valid = pricePaths.length === 0 || (await form.trigger(pricePaths))
      setStepStatus((prev) => ({ ...prev, [step]: valid ? 'completed' : 'error' }))
      return
    }
    const fields = STEP_FIELDS[step]
    if (!fields) return
    const valid = await form.trigger(fields as (keyof WizardFormInput)[])
    setStepStatus((prev) => ({ ...prev, [step]: valid ? 'completed' : 'error' }))
  }

  /** Navega marcando primero el estado del paso que se deja (sin bloquear el avance). */
  function goToStep(target: number) {
    const clamped = Math.max(0, Math.min(target, lastStep))
    if (clamped === currentStep) return
    void markStep(currentStep)
    setCurrentStep(clamped)
  }

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <div>
        <BackLink to="/cotizaciones">Cotizaciones</BackLink>
        <div className="mt-3">
          <PageHeader title="Nueva cotización" description="Completa los datos para generar la cotización." />
        </div>
      </div>
      <Stepper
        steps={WIZARD_STEPS}
        currentStep={currentStep}
        stepStatus={stepStatus}
        onStepClick={goToStep}
      />
      <FormProvider {...form}>
        {/* No es un <form> nativo: el wizard navega con botones (no submit), y un
            <form> acá anidaría el <form> de los modales de crear cliente/tipo de carga. */}
        <div className="space-y-6">
          <div className="min-h-[300px]">
            {currentStep === 0 && (
              <Step1InfoGeneral
                currencies={currencies}
                paymentTerms={paymentTerms}
                selectedClient={selectedClient}
                onClientChange={setSelectedClient}
              />
            )}
            {currentStep === 1 && (
              <Step2Items
                serviceTypes={serviceTypes}
                currencies={currencies}
                igvPercentage={igvPercentage}
                maxRootItems={maxRootItems}
              />
            )}
            {currentStep === 2 && <StepStandBy serviceTypes={serviceTypes} />}
            {currentStep === 3 && (
              <Step4Resumen
                selectedClient={selectedClient}
                currencies={currencies}
                paymentTerms={paymentTerms}
                serviceTypes={serviceTypes}
                igvPercentage={igvPercentage}
              />
            )}
          </div>
          <WizardNav
            isFirst={currentStep === 0}
            isLast={currentStep === lastStep}
            onBack={() => goToStep(currentStep - 1)}
            onNext={() => goToStep(currentStep + 1)}
            onCancel={() => navigate('/cotizaciones')}
          />
        </div>
      </FormProvider>
    </div>
  )
}
