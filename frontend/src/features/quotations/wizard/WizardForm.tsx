import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FormProvider, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { BackLink } from '../../../shared/ui/BackLink'
import { Stepper, type StepperStep, type StepStatus } from '../../../shared/ui/Stepper'
import {
  getApiErrorMessage,
  isPreconditionFailedError,
} from '../../../shared/utils/getApiErrorMessage'
import { Step1InfoGeneral } from './Step1InfoGeneral'
import { Step2Items } from './Step2Items'
import { StepStandBy } from './StepStandBy'
import { StepConditions } from './StepConditions'
import { Step4Resumen } from './Step4Resumen'
import { standbyPricePaths } from './standbyTargets'
import { WizardNav } from './WizardNav'
import {
  STEP_FIELDS,
  WIZARD_DEFAULTS,
  wizardSchema,
  type ImmutableField,
  type WizardFormInput,
} from './quotation-wizard.schema'
import type { WizardCatalogs } from './useWizardCatalogs'
import type { ClientResponse, QuotationConditionResponse } from '../../../api'

// Índices de paso (evitan números mágicos al renderizar/validar — el orden importa).
const STEP_STANDBY = 2
const STEP_CONDITIONS = 3
const STEP_SUMMARY = 4

const WIZARD_STEPS: StepperStep[] = [
  { label: 'Información General' },
  { label: 'Ítems' },
  { label: 'Stand-By' },
  { label: 'Condiciones' },
  { label: 'Resumen' },
]

const SECONDARY_BUTTON =
  'inline-flex items-center rounded-lg border border-red-300 bg-white px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50 focus:outline-none focus:ring-2 focus:ring-red-500'

export interface WizardFormProps {
  catalogs: WizardCatalogs
  /** Valores iniciales del form. Creación: omitido (usa WIZARD_DEFAULTS + defaults de catálogo);
   *  edición: precargado con `quotationResponseToForm`. */
  initialValues?: WizardFormInput
  /** Cliente preseleccionado (edición). Creación: `null`. */
  initialClient?: ClientResponse | null
  /** Campos bloqueados/read-only. Edición: `['quotationType', 'clientId']`. Creación: `[]`. */
  immutableFields?: ReadonlyArray<ImmutableField>
  /** Condiciones linkeadas a la cotización (edición). El paso "Condiciones" las usa para mostrar
   *  las que quedaron inactivas como "ya no vigentes". Creación: omitido. */
  linkedConditions?: QuotationConditionResponse[]
  title: string
  description: string
  submitLabel: string
  /** Mutación inyectada por la página: POST en creación, PUT (con If-Match) en edición. */
  onSubmit: (values: WizardFormInput) => void
  isSubmitting: boolean
  /** Error de la mutación; se muestra en el banner con `getApiErrorMessage`. */
  apiError: unknown
  /** Destino del BackLink y de "Cancelar". */
  backTo: string
  backLabel: string
  /** Acción de recuperación ante un 412 (recargar la cotización). Solo edición. */
  onRecover?: () => void
  /** Se invoca al navegar entre pasos: limpia el error de API persistente (mutation.reset),
   * para que un banner de guardado fallido no quede pegado mientras se corrige en otro paso. */
  onStepChange?: () => void
}

/**
 * Formulario del wizard de cotización (4 pasos: General / Ítems / Stand-By / Resumen).
 * Parametrizado para servir tanto a la creación (`CotizacionWizardPage`) como a la edición
 * (`CotizacionEditPage`): los valores iniciales, la mutación, el título y los campos
 * inmutables se inyectan por props. La navegación libre entre pasos y el gate de submit no
 * cambian respecto del flujo de creación.
 */
export function WizardForm({
  catalogs,
  initialValues,
  initialClient = null,
  immutableFields = [],
  linkedConditions = [],
  title,
  description,
  submitLabel,
  onSubmit,
  isSubmitting,
  apiError,
  backTo,
  backLabel,
  onRecover,
  onStepChange,
}: WizardFormProps) {
  const navigate = useNavigate()
  const { currencies, paymentTerms, serviceTypes, conditions, igvPercentage, maxRootItems, defaultValidityDays } =
    catalogs

  // Defaults de creación (solo se usan si no hay initialValues). El array nunca está vacío
  // (USD + PEN siempre seedeados); los `?? 0/null` son defensivos.
  const defaultCurrencyId = currencies.find((c) => c.code === 'PEN')?.id ?? currencies[0]?.id ?? 0
  const defaultPaymentTermId =
    paymentTerms.find((term) => term.name.toLowerCase() === 'contado')?.id ?? null

  const form = useForm<WizardFormInput>({
    resolver: zodResolver(wizardSchema),
    mode: 'onTouched',
    defaultValues: initialValues ?? {
      ...WIZARD_DEFAULTS,
      currencyId: defaultCurrencyId,
      paymentTermId: defaultPaymentTermId,
      validityDays: defaultValidityDays,
      // RN-07: en creación, todas las condiciones activas vienen pre-marcadas.
      conditionIds: conditions.map((condition) => condition.id),
    },
  })

  const [currentStep, setCurrentStep] = useState(0)
  // Estado por paso evaluado (check/alerta). La navegación NO bloquea: el usuario va y vuelve
  // libremente; solo se señala lo que falta (gate real = submit final).
  const [stepStatus, setStepStatus] = useState<Record<number, StepStatus>>({})
  // El cliente seleccionado vive acá (no en ClientField) para persistir entre steps.
  const [selectedClient, setSelectedClient] = useState<ClientResponse | null>(initialClient)
  // Dos señales de error: datos faltantes del form (validación local) y rechazo del backend.
  const [validationError, setValidationError] = useState<string | null>(null)
  const apiErrorMessage = apiError
    ? getApiErrorMessage(apiError, 'No se pudo guardar la cotización. Intenta de nuevo.')
    : null
  const bannerMessage = validationError ?? apiErrorMessage
  // Un 412 (otro usuario editó primero) ofrece recargar la cotización para re-precargar el form.
  const showRecover = Boolean(onRecover) && isPreconditionFailedError(apiError)
  const lastStep = WIZARD_STEPS.length - 1

  /** Valida un paso, registra su estado (check/alerta) y devuelve si es válido. Los pasos sin
   * campos propios se consideran válidos. */
  async function markStep(step: number): Promise<boolean> {
    // Stand-By (opcional, sin campos propios en STEP_FIELDS). Se evalúa mirando SOLO los precios
    // de los stand-by cargados (sin stand-by = válido → check). El paso "Condiciones" también es
    // opcional y sin campos propios → cae en la rama genérica de abajo (siempre válido).
    if (step === STEP_STANDBY) {
      const pricePaths = standbyPricePaths(form.getValues('items'))
      const valid = pricePaths.length === 0 || (await form.trigger(pricePaths))
      setStepStatus((prev) => ({ ...prev, [step]: valid ? 'completed' : 'error' }))
      return valid
    }
    const fields = STEP_FIELDS[step]
    if (!fields) return true
    const valid = await form.trigger(fields as (keyof WizardFormInput)[])
    setStepStatus((prev) => ({ ...prev, [step]: valid ? 'completed' : 'error' }))
    return valid
  }

  /** Navega marcando primero el estado del paso que se deja (sin bloquear el avance). */
  function goToStep(target: number) {
    const clamped = Math.max(0, Math.min(target, lastStep))
    if (clamped === currentStep) return
    setValidationError(null)
    onStepChange?.() // limpia un error de guardado previo (mutation.reset) al navegar
    void markStep(currentStep)
    setCurrentStep(clamped)
  }

  /** Guarda: mapea el form y delega en la mutación inyectada. El error del backend lo expone
   * la página (prop `apiError`), no un estado paralelo. */
  function onValid(values: WizardFormInput) {
    setValidationError(null)
    onSubmit(values)
  }

  /** Form inválido al guardar: marca cada paso y lleva al primero con errores. */
  async function onInvalid() {
    setValidationError('Faltan datos obligatorios. Revisa los pasos marcados con alerta.')
    const stepValid = await Promise.all([markStep(0), markStep(1), markStep(2)])
    const firstInvalidStep = stepValid.findIndex((isValid) => !isValid)
    setCurrentStep(firstInvalidStep === -1 ? 0 : firstInvalidStep)
  }

  const handleSave = form.handleSubmit(onValid, onInvalid)

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <div>
        <BackLink to={backTo}>{backLabel}</BackLink>
        <div className="mt-3">
          <PageHeader title={title} description={description} />
        </div>
      </div>
      <Stepper
        steps={WIZARD_STEPS}
        currentStep={currentStep}
        stepStatus={stepStatus}
        onStepClick={goToStep}
      />
      <FormProvider {...form}>
        {/* No es un <form> nativo: el wizard navega con botones (no submit), y un <form> acá
            anidaría el <form> de los modales de crear cliente/tipo de carga. */}
        <div className="space-y-6">
          <div className="min-h-[300px]">
            {currentStep === 0 && (
              <Step1InfoGeneral
                currencies={currencies}
                paymentTerms={paymentTerms}
                selectedClient={selectedClient}
                onClientChange={setSelectedClient}
                immutableFields={immutableFields}
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
            {currentStep === STEP_STANDBY && <StepStandBy serviceTypes={serviceTypes} />}
            {currentStep === STEP_CONDITIONS && (
              <StepConditions conditions={conditions} linkedConditions={linkedConditions} />
            )}
            {currentStep === STEP_SUMMARY && (
              <Step4Resumen
                selectedClient={selectedClient}
                currencies={currencies}
                paymentTerms={paymentTerms}
                serviceTypes={serviceTypes}
                igvPercentage={igvPercentage}
              />
            )}
          </div>
          {bannerMessage && (
            <div
              role="alert"
              className="space-y-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
            >
              <p>{bannerMessage}</p>
              {showRecover && (
                <button type="button" onClick={onRecover} className={SECONDARY_BUTTON}>
                  Recargar cotización
                </button>
              )}
            </div>
          )}
          <WizardNav
            isFirst={currentStep === 0}
            isLast={currentStep === lastStep}
            onBack={() => goToStep(currentStep - 1)}
            onNext={() => goToStep(currentStep + 1)}
            onCancel={() => navigate(backTo)}
            onSubmit={handleSave}
            isSubmitting={isSubmitting}
            submitLabel={submitLabel}
          />
        </div>
      </FormProvider>
    </div>
  )
}
