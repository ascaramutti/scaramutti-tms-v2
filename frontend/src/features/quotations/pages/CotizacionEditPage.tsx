import { useState } from 'react'
import { FileQuestion } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { Spinner } from '../../../shared/ui/Spinner'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { useQuotation } from '../hooks/useQuotation'
import { useUpdateQuotation } from '../hooks/useUpdateQuotation'
import { quotationFormToRequest } from '../wizard/quotationFormToRequest'
import { quotationResponseToForm } from '../wizard/quotationResponseToForm'
import { useWizardCatalogs } from '../wizard/useWizardCatalogs'
import { WizardForm } from '../wizard/WizardForm'
import type { WizardFormInput } from '../wizard/quotation-wizard.schema'
import type { ClientResponse } from '../../../api'

/**
 * Página de EDICIÓN de cotización (`/cotizaciones/:id/editar`). Carga la cotización (GET) y los
 * catálogos, y monta el `WizardForm` compartido en modo edición: precargado con
 * `quotationResponseToForm`, con tipo + cliente inmutables, y guardando vía PUT con `If-Match`
 * (optimistic locking). Reusa los gates de carga/404 del detalle.
 */
export function CotizacionEditPage() {
  const navigate = useNavigate()
  const params = useParams<{ id: string }>()
  const id = Number(params.id)
  const idInvalid = !Number.isInteger(id) || id <= 0

  const quotation = useQuotation(id)
  const catalogs = useWizardCatalogs()
  const updateQuotation = useUpdateQuotation()
  // Cuenta las recargas para forzar el remount del wizard al recargar (vuelve al Step 1),
  // aunque la versión no haya cambiado (si solo dependiera del ETag, sin cambios no remontaría).
  const [reloadCount, setReloadCount] = useState(0)

  function goToList() {
    navigate('/cotizaciones')
  }

  // Id no numérico o 404 → "no encontrada" (estado dedicado, no error genérico).
  if (idInvalid || (quotation.isError && isNotFoundError(quotation.error))) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <EmptyState
          icon={FileQuestion}
          title="Cotización no encontrada"
          description="La cotización que buscas no existe o fue eliminada."
          action={
            <button type="button" onClick={goToList} className={PRIMARY_BUTTON}>
              Volver al listado
            </button>
          }
        />
      </div>
    )
  }

  // Carga de la cotización o de los catálogos.
  if (quotation.isLoading || catalogs.isLoading) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div className="flex justify-center py-16">
          <Spinner size={28} label="Cargando cotización" className="text-blue-600" />
        </div>
      </div>
    )
  }

  // Error al traer la cotización (401/403/500/red): aviso + reintentar.
  if (quotation.isError || !quotation.data) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div role="alert" className="flex flex-col items-center justify-center px-6 py-16 text-center">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(quotation.error, 'No se pudo cargar la cotización.')}
          </p>
          <div className="mt-4 flex gap-2">
            <button type="button" onClick={() => quotation.refetch()} className={SECONDARY_BUTTON}>
              Reintentar
            </button>
            <button type="button" onClick={goToList} className={SECONDARY_BUTTON}>
              Volver
            </button>
          </div>
        </div>
      </div>
    )
  }

  // Error al traer los catálogos del wizard.
  if (!catalogs.data) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div role="alert" className="flex flex-col items-center justify-center px-6 py-16 text-center">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(catalogs.error, 'No se pudo cargar el formulario de cotización.')}
          </p>
          <button type="button" onClick={catalogs.refetch} className={SECONDARY_BUTTON}>
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  const data = quotation.data
  // If-Match = el ETag OPACO del header del GET (NO el `updatedAt` del body: su serialización
  // JSON difiere del ETag que el backend compara → daría 412 espurio). Ver readEtag/QuotationWithEtag.
  const ifMatch = data._etag ?? ''

  // Cliente preseleccionado desde el summary embebido (read-only en edición; solo se usan
  // name + ruc). El snapshot de contacto va en el form, no en el cliente master.
  const initialClient: ClientResponse = {
    id: data.client.id,
    name: data.client.name,
    ruc: data.client.ruc,
    phone: data.contactPhone ?? null,
    contactName: data.contactName ?? null,
    isActive: true,
    createdAt: '',
  }

  function handleUpdate(values: WizardFormInput) {
    updateQuotation.mutate(
      { id, ifMatch, body: quotationFormToRequest(values) },
      { onSuccess: () => navigate(`/cotizaciones/${id}`) },
    )
  }

  // Recargar: limpiar el error, traer la versión fresca del servidor y reiniciar el wizard.
  // El `reloadCount` en el `key` fuerza el remount → re-precarga los datos (descarta cambios
  // locales) y vuelve al Step 1, aun si la versión no cambió.
  function handleRecover() {
    updateQuotation.reset()
    void quotation.refetch()
    setReloadCount((count) => count + 1)
  }

  return (
    <WizardForm
      key={`${data._etag ?? data.updatedAt}-${reloadCount}`}
      catalogs={catalogs.data}
      initialValues={quotationResponseToForm(data)}
      initialClient={initialClient}
      immutableFields={['quotationType', 'clientId']}
      title={`Editar cotización ${data.code}`}
      description="Modifica los datos y guarda los cambios."
      submitLabel="Guardar cambios"
      onSubmit={handleUpdate}
      isSubmitting={updateQuotation.isPending}
      apiError={updateQuotation.isError ? updateQuotation.error : null}
      onStepChange={updateQuotation.reset}
      backTo={`/cotizaciones/${id}`}
      backLabel="Cotización"
      onRecover={handleRecover}
    />
  )
}
