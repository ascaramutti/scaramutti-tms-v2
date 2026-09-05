import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Ban, Pencil } from 'lucide-react'
import { BackLink } from '../../../shared/ui/BackLink'
import { Badge } from '../../../shared/ui/Badge'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { formatDate, formatQuantity } from '../../../shared/utils/formatters'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { WithdrawalCancelModal } from '../components/WithdrawalCancelModal'
import { WithdrawalInfoCards } from '../components/WithdrawalInfoCards'
import { useWarehouseWithdrawal } from '../hooks/useWarehouseWithdrawal'
import { Card } from '../../../shared/ui/Card'
import { Alert } from '../../../shared/ui/Alert'

const WITHDRAWALS_PATH = '/cotizaciones/almacen/retiros'

/**
 * Detalle de un retiro: su ficha y la anulación. El alta vive en su propia
 * pantalla y la edición llega como ítem aparte, así que esta pantalla muestra y
 * anula.
 */
export function WithdrawalDetailPage() {
  const { id } = useParams()
  const withdrawalId = Number(id)
  const [isCancelOpen, setCancelOpen] = useState(false)

  const withdrawal = useWarehouseWithdrawal(withdrawalId)
  // Un id no numérico igual entra a la ruta (el patrón no lo distingue): se
  // resuelve acá como "no encontrado" en vez de dispararle una request al backend.
  const invalidId = !Number.isInteger(withdrawalId) || withdrawalId <= 0
  const withdrawalMissing = isNotFoundError(withdrawal.error)

  if (invalidId || withdrawalMissing) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={WITHDRAWALS_PATH}>Volver a retiros</BackLink>
        <EmptyState
          title="No se encontró el retiro"
          description="Puede que el enlace esté mal o que el retiro ya no exista."
          action={
            <Link
              to={WITHDRAWALS_PATH}
              className="inline-flex items-center rounded-lg border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            >
              Ir a retiros
            </Link>
          }
        />
      </div>
    )
  }

  if (withdrawal.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando retiro" className="text-accent" />
      </div>
    )
  }

  if (withdrawal.isError || !withdrawal.data) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={WITHDRAWALS_PATH}>Volver a retiros</BackLink>
        <div role="alert" className="flex flex-col items-center justify-center py-16 text-center">
          <p className="text-sm font-medium text-fg-body">
            {getApiErrorMessage(withdrawal.error, 'No se pudo cargar el retiro.')}
          </p>
          <button
            type="button"
            onClick={() => withdrawal.refetch()}
            className="mt-4 inline-flex items-center rounded-lg border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  const data = withdrawal.data
  const isActive = data.status === 'ACTIVE'

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <BackLink to={WITHDRAWALS_PATH}>Volver a retiros</BackLink>

      <PageHeader
        title={`Retiro de ${data.product.name}`}
        description={`${formatQuantity(data.quantity)} ${data.product.unitCode} · retirado el ${formatDate(data.withdrawnAt)}`}
        divider
        action={
          isActive ? (
            <div className="flex gap-2">
              <Link
                to={`${WITHDRAWALS_PATH}/${data.id}/editar`}
                className="inline-flex items-center gap-2 rounded-lg border border-border-strong bg-surface px-4 py-2.5 text-sm font-medium text-fg-body shadow-sm hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-canvas"
              >
                <Pencil className="h-4 w-4" aria-hidden="true" />
                Editar
              </Link>
              <button
                type="button"
                onClick={() => setCancelOpen(true)}
                className="inline-flex items-center gap-2 rounded-lg border border-danger-border-strong bg-surface px-4 py-2.5 text-sm font-medium text-danger-fg shadow-sm hover:bg-danger-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-danger focus-visible:ring-offset-2 focus-visible:ring-offset-canvas"
              >
                <Ban className="h-4 w-4" aria-hidden="true" />
                Anular
              </button>
            </div>
          ) : undefined
        }
      />

      {!isActive && (
        <Alert as="section" role="alert" className="rounded-xl p-4" aria-labelledby="withdrawal-cancelled-heading">
          <div className="flex items-center gap-2">
            <Badge variant="danger">Anulado</Badge>
            {data.cancelledBy && data.cancelledAt && (
              <span id="withdrawal-cancelled-heading" className="text-sm text-danger-fg">
                Anulado por {data.cancelledBy.fullName} · {formatDate(data.cancelledAt)}
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

      <WithdrawalInfoCards withdrawal={data} />

      {data.observations && (
        <Card as="section" padding="md" aria-labelledby="withdrawal-observations-heading">
          <h2
            id="withdrawal-observations-heading"
            className="text-sm font-semibold text-fg"
          >
            Observaciones
          </h2>
          <p className="mt-2 whitespace-pre-line text-sm text-fg-body">{data.observations}</p>
        </Card>
      )}

      <WithdrawalCancelModal
        isOpen={isCancelOpen}
        onClose={() => setCancelOpen(false)}
        withdrawal={data}
        onReloadRequested={() => {
          withdrawal.refetch()
          setCancelOpen(false)
        }}
      />
    </div>
  )
}
