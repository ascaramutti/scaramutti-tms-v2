import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FormProvider, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { BackLink } from '../../../shared/ui/BackLink'
import { Spinner } from '../../../shared/ui/Spinner'
import { Stepper, type StepperStep } from '../../../shared/ui/Stepper'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { useCurrencies } from '../../catalogs/hooks/useCurrencies'
import { usePaymentTerms } from '../../catalogs/hooks/usePaymentTerms'
import { useQuotationConfig } from '../hooks/useQuotationConfig'
import { Step1InfoGeneral } from '../wizard/Step1InfoGeneral'
import { StepPlaceholder } from '../wizard/StepPlaceholder'
import { WizardNav } from '../wizard/WizardNav'
import {
  STEP_FIELDS,
  WIZARD_DEFAULTS,
  step1Schema,
  type WizardFormInput,
} from '../wizard/quotation-wizard.schema'
import type { ClientResponse, CurrencyResponse, PaymentTermResponse } from '../../../api'

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
  const config = useQuotationConfig()

  const isLoading = currencies.isLoading || paymentTerms.isLoading || config.isLoading
  const ready = currencies.data && paymentTerms.data && config.data

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
    const error = currencies.error ?? paymentTerms.error ?? config.error
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
      defaultValidityDays={config.data.defaultValidityDays}
    />
  )
}

interface WizardFormProps {
  currencies: CurrencyResponse[]
  paymentTerms: PaymentTermResponse[]
  defaultValidityDays: number
}

function WizardForm({ currencies, paymentTerms, defaultValidityDays }: WizardFormProps) {
  const navigate = useNavigate()
  // El array nunca está vacío (USD + PEN siempre seedeados); el `?? 0` es defensivo.
  const defaultCurrencyId = currencies.find((c) => c.code === 'PEN')?.id ?? currencies[0]?.id ?? 0
  const defaultPaymentTermId =
    paymentTerms.find((term) => term.name.toLowerCase() === 'contado')?.id ?? null

  const form = useForm<WizardFormInput>({
    resolver: zodResolver(step1Schema),
    mode: 'onTouched',
    defaultValues: {
      ...WIZARD_DEFAULTS,
      currencyId: defaultCurrencyId,
      paymentTermId: defaultPaymentTermId,
      validityDays: defaultValidityDays,
    },
  })

  const [currentStep, setCurrentStep] = useState(0)
  const [maxVisitedStep, setMaxVisitedStep] = useState(0)
  const [stepErrors, setStepErrors] = useState<number[]>([])
  // El cliente seleccionado vive acá (no en ClientField) para persistir entre steps.
  const [selectedClient, setSelectedClient] = useState<ClientResponse | null>(null)
  const lastStep = WIZARD_STEPS.length - 1

  async function validateStep(step: number): Promise<boolean> {
    const fields = STEP_FIELDS[step]
    if (!fields) return true
    const valid = await form.trigger(fields as (keyof WizardFormInput)[])
    setStepErrors((prev) => {
      if (valid) return prev.filter((s) => s !== step)
      return prev.includes(step) ? prev : [...prev, step]
    })
    return valid
  }

  async function handleNext() {
    const valid = await validateStep(currentStep)
    if (!valid) return
    const next = Math.min(currentStep + 1, lastStep)
    setMaxVisitedStep((prev) => Math.max(prev, next))
    setCurrentStep(next)
  }

  function handleBack() {
    setCurrentStep((prev) => Math.max(prev - 1, 0))
  }

  async function handleStepClick(index: number) {
    if (index === currentStep || index > maxVisitedStep) return
    await validateStep(currentStep)
    setCurrentStep(index)
  }

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <div>
        <BackLink to="/cotizaciones">Cotizaciones</BackLink>
        <div className="mt-3">
          <PageHeader title="Nueva cotización" description="Completá los datos para generar la cotización." />
        </div>
      </div>
      <Stepper
        steps={WIZARD_STEPS}
        currentStep={currentStep}
        maxVisitedStep={maxVisitedStep}
        stepErrors={stepErrors}
        onStepClick={handleStepClick}
      />
      <FormProvider {...form}>
        {/* No es un <form> nativo: el wizard navega con botones (no submit), y un
            <form> acá anidaría el <form> del modal de crear cliente (HTML inválido). */}
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
            {currentStep === 1 && <StepPlaceholder title="Ítems del servicio" />}
            {currentStep === 2 && <StepPlaceholder title="Costos de Stand-By" />}
            {currentStep === 3 && <StepPlaceholder title="Resumen y confirmación" />}
          </div>
          <WizardNav
            isFirst={currentStep === 0}
            isLast={currentStep === lastStep}
            onBack={handleBack}
            onNext={handleNext}
            onCancel={() => navigate('/cotizaciones')}
          />
        </div>
      </FormProvider>
    </div>
  )
}
