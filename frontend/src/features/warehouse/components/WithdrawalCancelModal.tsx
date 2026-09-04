import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { Modal } from '../../../shared/ui/Modal'
import { Spinner } from '../../../shared/ui/Spinner'
import { Textarea } from '../../../shared/ui/Textarea'
import { formatQuantity } from '../../../shared/utils/formatters'
import {
  getApiErrorMessage,
  isPreconditionFailedError,
} from '../../../shared/utils/getApiErrorMessage'
import { useCancelWarehouseWithdrawal } from '../hooks/useCancelWarehouseWithdrawal'
import type { WarehouseWithdrawalWithEtag } from '../hooks/useWarehouseWithdrawal'
import {
  warehouseCancelSchema,
  type WarehouseCancelInput,
} from '../schemas/warehouse-cancel.schema'
import { Alert } from '../../../shared/ui/Alert'

interface WithdrawalCancelModalProps {
  isOpen: boolean
  onClose: () => void
  withdrawal: WarehouseWithdrawalWithEtag
  /** El detalle recarga para tomar la versión vigente tras un conflicto (412). */
  onReloadRequested: () => void
}

/**
 * Anulación de un retiro. Se monta solo cuando está abierto para que el motivo
 * arranque siempre en blanco (react-hook-form congela los defaults al montar).
 *
 * Anular DEVUELVE al almacén el stock que el retiro había descontado (al revés
 * que en una entrada, donde anular descuenta lo que la factura había sumado), así
 * que la operación es siempre segura para el stock; lo que se pide es el motivo
 * obligatorio (RN-WH3, ≥10 caracteres) porque queda en la auditoría. El `If-Match`
 * viaja con el ETag OPACO del header del GET, nunca con el `updatedAt` del body
 * (difieren por microsegundos y darían un 412 espurio).
 */
export function WithdrawalCancelModal(props: WithdrawalCancelModalProps) {
  if (!props.isOpen) return null
  return <WithdrawalCancelForm {...props} />
}

function WithdrawalCancelForm({
  onClose,
  withdrawal,
  onReloadRequested,
}: WithdrawalCancelModalProps) {
  const cancelWithdrawal = useCancelWarehouseWithdrawal()
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<WarehouseCancelInput>({
    resolver: zodResolver(warehouseCancelSchema),
    mode: 'onTouched',
    defaultValues: { reason: '' },
  })

  // Sin el ETag del header no se puede armar el If-Match y el cancel lo exige. Pasa
  // si el gateway no expone el header (falta `cors.exposed-headers=ETag`): mejor
  // avisarlo antes de que el usuario escriba el motivo.
  const missingEtag = !withdrawal._etag
  const versionConflict = isPreconditionFailedError(cancelWithdrawal.error)
  // Cualquier otro error del backend (409 WH-008 ya anulado, 500, …) se muestra con
  // su `detail`. El 412 tiene su propio aviso con recarga.
  const backendError =
    cancelWithdrawal.isError && !versionConflict
      ? getApiErrorMessage(cancelWithdrawal.error, 'No se pudo anular el retiro. Intenta de nuevo.')
      : null

  function onSubmit(input: WarehouseCancelInput) {
    if (!withdrawal._etag) return
    cancelWithdrawal.mutate(
      { id: withdrawal.id, ifMatch: withdrawal._etag, body: { reason: input.reason.trim() } },
      {
        onSuccess: () => {
          toast.success(`Retiro de ${withdrawal.product.name} anulado.`)
          onClose()
        },
      },
    )
  }

  return (
    <Modal isOpen onClose={onClose} title="Anular retiro">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <Alert as="p" variant="warning" role="alert" className="rounded-lg px-4 py-2.5 text-sm text-warning-fg">
          Anular este retiro devuelve al stock {formatQuantity(withdrawal.quantity)}{' '}
          {withdrawal.product.unitCode} de {withdrawal.product.name}. Esta acción no se puede
          deshacer.
        </Alert>

        {versionConflict && (
          <Alert variant="warning" role="alert" className="flex items-center justify-between gap-3 rounded-lg px-4 py-2.5 text-sm text-warning-fg">
            <span>
              {getApiErrorMessage(
                cancelWithdrawal.error,
                'Otro usuario cambió este retiro mientras lo revisabas.',
              )}{' '}
              Recarga para ver el estado vigente.
            </span>
            <button
              type="button"
              onClick={onReloadRequested}
              className="shrink-0 font-medium text-warning-fg underline underline-offset-2 hover:no-underline"
            >
              Descartar y recargar
            </button>
          </Alert>
        )}

        {missingEtag && (
          <Alert as="p" bordered={false} role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            No se puede anular: falta la versión del retiro. Recarga la página e intenta de nuevo.
          </Alert>
        )}

        {backendError && (
          <Alert as="p" bordered={false} role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            {backendError}
          </Alert>
        )}

        <Textarea
          id="withdrawal-cancel-reason"
          label="Motivo de anulación"
          rows={3}
          helperText="Queda registrado en la auditoría. Mínimo 10 caracteres."
          error={errors.reason?.message}
          register={register('reason')}
        />

        <div className="flex justify-end gap-3 border-t border-border pt-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-border-strong bg-surface px-4 py-2.5 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            Cancelar
          </button>
          <button
            type="submit"
            disabled={isSubmitting || cancelWithdrawal.isPending || missingEtag}
            className="inline-flex items-center gap-2 rounded-lg bg-danger px-4 py-2.5 text-sm font-medium text-on-solid shadow-sm hover:bg-danger-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-danger focus-visible:ring-offset-2 focus-visible:ring-offset-surface disabled:cursor-not-allowed disabled:opacity-60"
          >
            {cancelWithdrawal.isPending && <Spinner size={16} label="Anulando" />}
            {cancelWithdrawal.isPending ? 'Anulando…' : 'Anular retiro'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
