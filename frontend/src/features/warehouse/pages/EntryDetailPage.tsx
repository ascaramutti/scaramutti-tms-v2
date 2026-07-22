import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Ban } from 'lucide-react'
import { BackLink } from '../../../shared/ui/BackLink'
import { Badge } from '../../../shared/ui/Badge'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { formatDate } from '../../../shared/utils/formatters'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { EntryCancelModal } from '../components/EntryCancelModal'
import { EntryDetailItemsTable } from '../components/EntryDetailItemsTable'
import { EntryInfoCards } from '../components/EntryInfoCards'
import { useWarehousePurchaseInvoice } from '../hooks/useWarehousePurchaseInvoice'

const ENTRIES_PATH = '/cotizaciones/almacen/entradas'

/**
 * Detalle de una entrada (factura de compra): su ficha, los ítems con el total y
 * la anulación. El alta y la edición no viven acá (el alta es en su propia
 * pantalla; la edición llega como ítem aparte), así que esta pantalla muestra y
 * anula.
 */
export function EntryDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const invoiceId = Number(id)
  const [isCancelOpen, setCancelOpen] = useState(false)

  const invoice = useWarehousePurchaseInvoice(invoiceId)
  // Un id no numérico igual entra a la ruta (el patrón no lo distingue): se
  // resuelve acá como "no encontrado" en vez de dispararle una request al backend.
  const invalidId = !Number.isInteger(invoiceId) || invoiceId <= 0
  const invoiceMissing = isNotFoundError(invoice.error)

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
              className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            >
              Ir a entradas
            </Link>
          }
        />
      </div>
    )
  }

  if (invoice.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando entrada" className="text-blue-600" />
      </div>
    )
  }

  if (invoice.isError || !invoice.data) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={ENTRIES_PATH}>Volver a entradas</BackLink>
        <div role="alert" className="flex flex-col items-center justify-center py-16 text-center">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(invoice.error, 'No se pudo cargar la entrada.')}
          </p>
          <button
            type="button"
            onClick={() => invoice.refetch()}
            className="mt-4 inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          >
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  const data = invoice.data
  const isActive = data.status === 'ACTIVE'

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <BackLink to={ENTRIES_PATH}>Volver a entradas</BackLink>

      <PageHeader
        title={`Factura ${data.invoiceNumber}`}
        description={
          data.supplier.ruc
            ? `${data.supplier.name} · RUC ${data.supplier.ruc}`
            : data.supplier.name
        }
        divider
        action={
          isActive ? (
            <button
              type="button"
              onClick={() => setCancelOpen(true)}
              className="inline-flex items-center gap-2 rounded-lg border border-red-200 bg-white px-4 py-2.5 text-sm font-medium text-red-700 shadow-sm hover:bg-red-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500 focus-visible:ring-offset-2"
            >
              <Ban className="h-4 w-4" aria-hidden="true" />
              Anular
            </button>
          ) : undefined
        }
      />

      {!isActive && (
        <section
          role="alert"
          aria-labelledby="entry-cancelled-heading"
          className="rounded-xl border border-red-200 bg-red-50 p-4"
        >
          <div className="flex items-center gap-2">
            <Badge variant="danger">Anulada</Badge>
            {data.cancelledBy && data.cancelledAt && (
              <span id="entry-cancelled-heading" className="text-sm text-red-800">
                Anulada por {data.cancelledBy.fullName} · {formatDate(data.cancelledAt)}
              </span>
            )}
          </div>
          {data.cancelReason && (
            <p className="mt-2 text-sm text-red-800">
              <span className="font-medium">Motivo:</span> {data.cancelReason}
            </p>
          )}
        </section>
      )}

      <EntryInfoCards invoice={data} />

      {data.observations && (
        <section
          aria-labelledby="entry-observations-heading"
          className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
        >
          <h2 id="entry-observations-heading" className="text-sm font-semibold text-slate-900">
            Observaciones
          </h2>
          <p className="mt-2 whitespace-pre-line text-sm text-slate-700">{data.observations}</p>
        </section>
      )}

      <EntryDetailItemsTable
        items={data.items}
        total={data.total}
        currencyCode={data.currency.code}
        onProductClick={(item) =>
          navigate(`/cotizaciones/almacen/productos/${item.product.id}`)
        }
      />

      <EntryCancelModal
        isOpen={isCancelOpen}
        onClose={() => setCancelOpen(false)}
        invoice={data}
        onReloadRequested={() => {
          invoice.refetch()
          setCancelOpen(false)
        }}
      />
    </div>
  )
}
