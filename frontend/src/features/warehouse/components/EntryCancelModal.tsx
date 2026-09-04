import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { Modal } from '../../../shared/ui/Modal'
import { Spinner } from '../../../shared/ui/Spinner'
import { Textarea } from '../../../shared/ui/Textarea'
import {
  getApiErrorMessage,
  isPreconditionFailedError,
} from '../../../shared/utils/getApiErrorMessage'
import { useCancelWarehousePurchaseInvoice } from '../hooks/useCancelWarehousePurchaseInvoice'
import type { WarehousePurchaseInvoiceWithEtag } from '../hooks/useWarehousePurchaseInvoice'
import {
  warehouseCancelSchema,
  type WarehouseCancelInput,
} from '../schemas/warehouse-cancel.schema'
import { Alert } from '../../../shared/ui/Alert'

interface EntryCancelModalProps {
  isOpen: boolean
  onClose: () => void
  invoice: WarehousePurchaseInvoiceWithEtag
  /** El detalle recarga para tomar la versión vigente tras un conflicto (412). */
  onReloadRequested: () => void
}

/**
 * Anulación de una entrada. Se monta solo cuando está abierto para que el motivo
 * arranque siempre en blanco (react-hook-form congela los defaults al montar).
 *
 * Anular descuenta del stock lo que la factura había sumado, así que se pide una
 * confirmación explícita y un motivo obligatorio (RN-WH3, ≥10 caracteres). El
 * `If-Match` viaja con el ETag OPACO del header del GET, nunca con el `updatedAt`
 * del body (difieren por microsegundos y darían un 412 espurio).
 */
export function EntryCancelModal(props: EntryCancelModalProps) {
  if (!props.isOpen) return null
  return <EntryCancelForm {...props} />
}

function EntryCancelForm({ onClose, invoice, onReloadRequested }: EntryCancelModalProps) {
  const cancelInvoice = useCancelWarehousePurchaseInvoice()
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
  const missingEtag = !invoice._etag
  const versionConflict = isPreconditionFailedError(cancelInvoice.error)
  // Cualquier otro error del backend (409 WH-006 stock negativo / WH-008 ya anulada,
  // 500, …) se muestra con su `detail`. El 412 tiene su propio aviso con recarga.
  const backendError =
    cancelInvoice.isError && !versionConflict
      ? getApiErrorMessage(cancelInvoice.error, 'No se pudo anular la factura. Intenta de nuevo.')
      : null

  function onSubmit(input: WarehouseCancelInput) {
    if (!invoice._etag) return
    cancelInvoice.mutate(
      { id: invoice.id, ifMatch: invoice._etag, body: { reason: input.reason.trim() } },
      {
        onSuccess: () => {
          toast.success(`Factura ${invoice.invoiceNumber} anulada.`)
          onClose()
        },
      },
    )
  }

  return (
    <Modal isOpen onClose={onClose} title="Anular entrada">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <Alert as="p" variant="warning" role="alert" className="rounded-lg px-4 py-2.5 text-sm text-warning-fg">
          Anular esta factura descuenta del stock los ítems que sumó. Esta acción no se puede
          deshacer.
        </Alert>

        {versionConflict && (
          <Alert variant="warning" role="alert" className="flex items-center justify-between gap-3 rounded-lg px-4 py-2.5 text-sm text-warning-fg">
            <span>
              {getApiErrorMessage(
                cancelInvoice.error,
                'Otro usuario cambió esta factura mientras la revisabas.',
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
            No se puede anular: falta la versión de la entrada. Recarga la página e intenta de nuevo.
          </Alert>
        )}

        {backendError && (
          <Alert as="p" bordered={false} role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            {backendError}
          </Alert>
        )}

        <Textarea
          id="entry-cancel-reason"
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
            disabled={isSubmitting || cancelInvoice.isPending || missingEtag}
            className="inline-flex items-center gap-2 rounded-lg bg-danger px-4 py-2.5 text-sm font-medium text-on-solid shadow-sm hover:bg-danger-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-danger focus-visible:ring-offset-2 focus-visible:ring-offset-surface disabled:cursor-not-allowed disabled:opacity-60"
          >
            {cancelInvoice.isPending && <Spinner size={16} label="Anulando" />}
            {cancelInvoice.isPending ? 'Anulando…' : 'Anular factura'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
