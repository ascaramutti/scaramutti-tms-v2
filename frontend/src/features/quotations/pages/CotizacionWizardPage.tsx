import { useNavigate } from 'react-router-dom'
import { Spinner } from '../../../shared/ui/Spinner'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { useCreateQuotation } from '../hooks/useCreateQuotation'
import { quotationFormToRequest } from '../wizard/quotationFormToRequest'
import { useWizardCatalogs } from '../wizard/useWizardCatalogs'
import { WizardForm } from '../wizard/WizardForm'
import type { WizardFormInput } from '../wizard/quotation-wizard.schema'
import { Button } from '../../../shared/ui/Button'

/**
 * Página de CREACIÓN de cotización. Wrapper delgado: gatea con spinner/error la carga de
 * catálogos (`useWizardCatalogs`) y monta el `WizardForm` compartido en modo creación
 * (defaults vacíos, mutación POST, todos los campos editables).
 */
export function CotizacionWizardPage() {
  const navigate = useNavigate()
  const catalogs = useWizardCatalogs()
  const createQuotation = useCreateQuotation()

  if (catalogs.isLoading) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div className="flex justify-center py-16">
          <Spinner size={28} label="Cargando formulario" className="text-accent" />
        </div>
      </div>
    )
  }

  if (!catalogs.data) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div role="alert" className="flex flex-col items-center justify-center px-6 py-16 text-center">
          <p className="text-sm font-medium text-fg-body">
            {getApiErrorMessage(catalogs.error, 'No se pudo cargar el formulario de cotización.')}
          </p>
          <Button variant="secondary" onClick={catalogs.refetch} className="mt-4">
            Reintentar
          </Button>
        </div>
      </div>
    )
  }

  function handleCreate(values: WizardFormInput) {
    createQuotation.mutate(quotationFormToRequest(values), {
      onSuccess: (created) => navigate(`/cotizaciones/${created.id}`),
    })
  }

  return (
    <WizardForm
      catalogs={catalogs.data}
      title="Nueva cotización"
      description="Completa los datos para generar la cotización."
      submitLabel="Guardar cotización"
      onSubmit={handleCreate}
      isSubmitting={createQuotation.isPending}
      apiError={createQuotation.isError ? createQuotation.error : null}
      onStepChange={createQuotation.reset}
      backTo="/cotizaciones"
      backLabel="Cotizaciones"
    />
  )
}
