import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { formatDate } from '../../../shared/utils/formatters'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { WithdrawalForm } from '../components/WithdrawalForm'
import { useWarehouseWithdrawal } from '../hooks/useWarehouseWithdrawal'

const WITHDRAWALS_PATH = '/cotizaciones/almacen/retiros'

/**
 * Edición de un retiro. Carga el detalle (GET, con su ETag), monta el
 * `WithdrawalForm` compartido en modo edición (precargado, producto read-only,
 * motivo obligatorio, PUT con If-Match) y reusa los gates de carga/404 del detalle.
 *
 * Un retiro anulado no se edita: el backend responde 409 WH-008, pero la URL
 * `/editar` es alcanzable directo (link guardado, navegación manual), así que se
 * avisa y se rebota al detalle.
 */
export function WithdrawalEditPage() {
  const navigate = useNavigate()
  const { id } = useParams()
  const withdrawalId = Number(id)
  const invalidId = !Number.isInteger(withdrawalId) || withdrawalId <= 0

  const withdrawal = useWarehouseWithdrawal(withdrawalId)
  // Cuenta las recargas para forzar el remount del form al recargar tras un 412,
  // aun si la versión no cambió (si solo dependiera del ETag, no remontaría).
  const [reloadCount, setReloadCount] = useState(0)

  const detailPath = `${WITHDRAWALS_PATH}/${withdrawalId}`
  const withdrawalMissing = isNotFoundError(withdrawal.error)
  const isCancelled = withdrawal.data?.status === 'CANCELLED'

  const warnedCancelled = useRef(false)
  useEffect(() => {
    if (isCancelled && !warnedCancelled.current) {
      warnedCancelled.current = true
      toast.error('No se puede editar un retiro anulado.')
    }
  }, [isCancelled])

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
              className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            >
              Ir a retiros
            </Link>
          }
        />
      </div>
    )
  }

  // Anulado → rebote al detalle (un retiro anulado no se edita).
  if (isCancelled) {
    return <Navigate to={detailPath} replace />
  }

  if (withdrawal.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando retiro" className="text-blue-600" />
      </div>
    )
  }

  if (withdrawal.isError || !withdrawal.data) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={detailPath}>Volver al retiro</BackLink>
        <div role="alert" className="flex flex-col items-center justify-center py-16 text-center">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(withdrawal.error, 'No se pudo cargar el retiro.')}
          </p>
          <button
            type="button"
            onClick={() => withdrawal.refetch()}
            className="mt-4 inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          >
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  const data = withdrawal.data

  return (
    <div className="mx-auto max-w-[720px] space-y-6 px-6 py-8">
      <BackLink to={detailPath}>Volver al retiro</BackLink>

      <PageHeader
        title={`Editar retiro de ${data.product.name}`}
        description={`Retirado el ${formatDate(data.withdrawnAt)} · registró ${data.registeredBy.fullName}`}
        divider
      />

      <WithdrawalForm
        // El remount re-precarga los datos (descarta cambios locales) y reinicia la
        // mutación tras recargar por un conflicto de versión.
        key={`${data._etag ?? data.updatedAt}-${reloadCount}`}
        mode="edit"
        withdrawal={data}
        onUpdated={(updated) => {
          toast.success(`Retiro de ${updated.product.name} actualizado. El stock refleja el cambio.`)
          navigate(detailPath)
        }}
        onCancel={() => navigate(detailPath)}
        onReloadRequested={() => {
          void withdrawal.refetch()
          setReloadCount((count) => count + 1)
        }}
      />
    </div>
  )
}
