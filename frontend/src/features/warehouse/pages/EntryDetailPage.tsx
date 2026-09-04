import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Ban, Pencil } from 'lucide-react'
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
import { Card } from '../../../shared/ui/Card'
import { Alert } from '../../../shared/ui/Alert'

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
            /* Esta cadena NO usa `buttonClasses`, y no es un olvido. Es la gemela del
                secundario compartido: idéntica clase por clase salvo que el anillo de foco
                sale con `focus-visible:` en vez de `focus:`, o sea aparece al llegar con el
                tabulador pero no al hacer clic con el mouse. Son doce en siete archivos, y
                unificarlas cambia CUÁNDO se ve el anillo, que es un cambio de aspecto y no
                una mudanza.

                Aviso para el próximo barrido por valor: por conjunto de clases estas doce se
                parecen mucho a la secundaria, y esa es justo la trampa que el PR del botón
                documentó del otro lado en `WizardForm`. La decisión y su fila están en
                `docs/2-diseno/frontend-tema/DECISIONES.md`. */
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
        <BackLink to={ENTRIES_PATH}>Volver a entradas</BackLink>
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
            <div className="flex gap-2">
              <Link
                to={`${ENTRIES_PATH}/${data.id}/editar`}
                className="inline-flex items-center gap-2 rounded-lg border border-border-strong bg-surface px-4 py-2.5 text-sm font-medium text-fg-body shadow-sm hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-canvas"
              >
                <Pencil className="h-4 w-4" aria-hidden="true" />
                Editar
              </Link>
              <button
                type="button"
                onClick={() => setCancelOpen(true)}
                className="inline-flex items-center gap-2 rounded-lg border border-danger-border bg-surface px-4 py-2.5 text-sm font-medium text-danger-fg shadow-sm hover:bg-danger-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-danger focus-visible:ring-offset-2 focus-visible:ring-offset-canvas"
              >
                <Ban className="h-4 w-4" aria-hidden="true" />
                Anular
              </button>
            </div>
          ) : undefined
        }
      />

      {!isActive && (
        <Alert as="section" role="alert" className="rounded-xl p-4" aria-labelledby="entry-cancelled-heading">
          <div className="flex items-center gap-2">
            <Badge variant="danger">Anulada</Badge>
            {data.cancelledBy && data.cancelledAt && (
              <span id="entry-cancelled-heading" className="text-sm text-danger-fg">
                Anulada por {data.cancelledBy.fullName} · {formatDate(data.cancelledAt)}
              </span>
            )}
          </div>
          {data.cancelReason && (
            <p className="mt-2 text-sm text-danger-fg">
              <span className="font-medium">Motivo:</span> {data.cancelReason}
            </p>
          )}
        </Alert>
      )}

      <EntryInfoCards invoice={data} />

      {data.observations && (
        <Card as="section" padding="md" aria-labelledby="entry-observations-heading">
          <h2 id="entry-observations-heading" className="text-sm font-semibold text-fg">
            Observaciones
          </h2>
          <p className="mt-2 whitespace-pre-line text-sm text-fg-body">{data.observations}</p>
        </Card>
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
