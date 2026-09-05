import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { EntryForm } from '../components/EntryForm'
import { useWarehousePurchaseInvoice } from '../hooks/useWarehousePurchaseInvoice'

const ENTRIES_PATH = '/cotizaciones/almacen/entradas'

/**
 * Edición de una entrada. Carga el detalle (GET, con su ETag), monta el
 * `EntryForm` compartido en modo edición (precargado, proveedor read-only, motivo
 * obligatorio, PUT con If-Match) y reusa los gates de carga/404 del detalle.
 *
 * Una factura anulada no se edita: el backend responde 409 WH-008, pero la URL
 * `/editar` es alcanzable directo (link guardado, navegación manual), así que se
 * avisa y se rebota al detalle.
 */
export function EntryEditPage() {
  const navigate = useNavigate()
  const { id } = useParams()
  const invoiceId = Number(id)
  const invalidId = !Number.isInteger(invoiceId) || invoiceId <= 0

  const invoice = useWarehousePurchaseInvoice(invoiceId)
  // Cuenta las recargas para forzar el remount del form al recargar tras un 412,
  // aun si la versión no cambió (si solo dependiera del ETag, no remontaría).
  const [reloadCount, setReloadCount] = useState(0)

  const detailPath = `${ENTRIES_PATH}/${invoiceId}`
  const invoiceMissing = isNotFoundError(invoice.error)
  const isCancelled = invoice.data?.status === 'CANCELLED'

  const warnedCancelled = useRef(false)
  useEffect(() => {
    if (isCancelled && !warnedCancelled.current) {
      warnedCancelled.current = true
      toast.error('No se puede editar una factura anulada.')
    }
  }, [isCancelled])

  if (invalidId || invoiceMissing) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={ENTRIES_PATH}>Volver a entradas</BackLink>
        <EmptyState
          title="No se encontró la entrada"
          description="Puede que se haya anulado o que el enlace esté mal."
          action={
            <Link
              to={ENTRIES_PATH}
              className="inline-flex items-center rounded-lg border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            >
              Ir a entradas
            </Link>
          }
        />
      </div>
    )
  }

  // Anulada → rebote al detalle (una anulada no se edita).
  if (isCancelled) {
    return <Navigate to={detailPath} replace />
  }

  if (invoice.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando entrada" className="text-accent" />
      </div>
    )
  }

  if (invoice.isError || !invoice.data) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={detailPath}>Volver a la entrada</BackLink>
        <div role="alert" className="flex flex-col items-center justify-center py-16 text-center">
          <p className="text-sm font-medium text-fg-body">
            {getApiErrorMessage(invoice.error, 'No se pudo cargar la entrada.')}
          </p>
          <button
            type="button"
            onClick={() => invoice.refetch()}
            className="mt-4 inline-flex items-center rounded-lg border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  const data = invoice.data

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <BackLink to={detailPath}>Volver a la entrada</BackLink>

      <PageHeader
        title={`Editar factura ${data.invoiceNumber}`}
        description={
          data.supplier.ruc
            ? `${data.supplier.name} · RUC ${data.supplier.ruc}`
            : data.supplier.name
        }
        divider
      />

      <EntryForm
        // El remount re-precarga los datos (descarta cambios locales) y reinicia la
        // mutación tras recargar por un conflicto de versión.
        key={`${data._etag ?? data.updatedAt}-${reloadCount}`}
        mode="edit"
        invoice={data}
        onUpdated={(updated) => {
          toast.success(`Factura ${updated.invoiceNumber} actualizada. El stock refleja los cambios.`)
          navigate(detailPath)
        }}
        onCancel={() => navigate(detailPath)}
        onReloadRequested={() => {
          void invoice.refetch()
          setReloadCount((count) => count + 1)
        }}
      />
    </div>
  )
}
