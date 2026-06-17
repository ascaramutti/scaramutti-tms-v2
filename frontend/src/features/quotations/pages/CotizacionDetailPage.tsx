import { FileQuestion } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { Spinner } from '../../../shared/ui/Spinner'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { useQuotation } from '../hooks/useQuotation'
import { QuotationAuditFooter } from '../components/QuotationAuditFooter'
import { QuotationDetailHeader } from '../components/QuotationDetailHeader'
import { QuotationItemsSection } from '../components/QuotationItemsSection'
import { QuotationDetailActions } from '../components/QuotationDetailActions'
import { QuotationNotesSection } from '../components/QuotationNotesSection'
import { QuotationStandbyTable } from '../components/QuotationStandbyTable'
import { QuotationSummaryCard } from '../components/QuotationSummaryCard'
import { QuotationTotalGeneral } from '../components/QuotationTotalGeneral'

export function CotizacionDetailPage() {
  const navigate = useNavigate()
  const params = useParams<{ id: string }>()
  const id = Number(params.id)
  const idInvalid = !Number.isInteger(id) || id <= 0

  const { data, isLoading, isError, error, refetch } = useQuotation(id)

  function goToList() {
    navigate('/cotizaciones')
  }

  // Id no numérico o 404 → "no encontrada" (estado dedicado, no un error genérico).
  if (idInvalid || (isError && isNotFoundError(error))) {
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

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div className="flex justify-center py-16">
          <Spinner size={28} label="Cargando cotización" className="text-blue-600" />
        </div>
      </div>
    )
  }

  // Cualquier otro error (401/403/500/red): aviso + reintentar.
  if (isError || !data) {
    return (
      <div className="mx-auto max-w-[1024px] px-6 py-8">
        <div role="alert" className="flex flex-col items-center justify-center px-6 py-16 text-center">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(error, 'No se pudo cargar la cotización.')}
          </p>
          <div className="mt-4 flex gap-2">
            <button type="button" onClick={() => refetch()} className={SECONDARY_BUTTON}>
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

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <QuotationDetailHeader
        code={data.code}
        quotationType={data.quotationType}
        status={data.status}
        isExpired={data.isExpired}
        createdAt={data.createdAt}
        expiresAt={data.expiresAt}
      />
      <QuotationDetailActions quotationId={data.id} quotationCode={data.code} />
      <QuotationSummaryCard quotation={data} />
      <QuotationItemsSection
        items={data.items}
        currencyCode={data.currency.code}
        subtotal={data.totalSubtotal}
        igv={data.totalIgv}
      />
      <QuotationStandbyTable items={data.items} currencyCode={data.currency.code} />
      <QuotationNotesSection quotation={data} />
      <div className="flex flex-wrap items-end justify-between gap-4">
        <QuotationAuditFooter
          createdBy={data.createdBy}
          updatedBy={data.updatedBy}
          createdAt={data.createdAt}
          updatedAt={data.updatedAt}
        />
        <QuotationTotalGeneral total={data.totalAmount} currencyCode={data.currency.code} />
      </div>
    </div>
  )
}
