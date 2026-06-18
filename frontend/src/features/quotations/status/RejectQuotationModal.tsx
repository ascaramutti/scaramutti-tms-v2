import { useEffect } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { isAxiosError } from 'axios'
import { Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import { Modal } from '../../../shared/ui/Modal'
import { Textarea } from '../../../shared/ui/Textarea'
import { DANGER_BUTTON, SECONDARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { isPreconditionFailedError } from '../../../shared/utils/getApiErrorMessage'
import { stripControlChars } from '../../../shared/utils/sanitizeText'
import { useChangeQuotationStatus } from '../hooks/useChangeQuotationStatus'
import { rejectSchema, type RejectFormValues } from './reject.schema'
import type { Problem } from '../../../api'

const REASON_FALLBACK_ERROR = 'No se pudo registrar el rechazo.'

/** `true` si el error es un 400 (validación). El 400 del motivo se muestra inline en el
 * campo; el resto de los estados de error se delegan al padre. */
function isBadRequestError(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 400
}

/** `true` si el 400 trae `errors[]` por campo (lo asigna inline `handleApiFormError`). Un
 * 400 SIN `errors[]` (ej. COM-001 a secas) no lo cubre ese helper → se muestra inline acá. */
function hasFieldErrors(error: unknown): boolean {
  if (!isAxiosError(error)) {
    return false
  }
  const problem = error.response?.data as Problem | undefined
  return (problem?.errors?.length ?? 0) > 0
}

const REASON_MAX_LENGTH = 500
const DISABLED = 'disabled:cursor-not-allowed disabled:opacity-60'

interface RejectQuotationModalProps {
  isOpen: boolean
  quotationId: number
  /** ETag opaco del GET, para el `If-Match` de la transición. */
  etag: string | null
  onClose: () => void
  /**
   * Reporta al padre los errores que NO son de validación de formulario (409 transición
   * inválida, 412 ETag stale, 403/404): el padre centraliza toast/banner + refetch. El 400
   * (motivo inválido/ausente) lo maneja el modal inline. El padre también decide cerrar el
   * modal ante 412 (el banner queda en el detalle).
   */
  onStatusError?: (error: unknown) => void
}

/**
 * Modal de rechazo de cotización (SENT → REJECTED). El motivo es OBLIGATORIO e INTERNO
 * (nunca va al PDF ni cara al cliente — se avisa en el helper + badge "🔒 interno", mismo
 * patrón que `QuotationNotesFields`). Validación zod (`rejectSchema`) + sanitize L2.
 *
 * Errores: 400 (motivo) → inline en el textarea — con `errors[]` vía
 * `handleApiFormError(allowedFields:['rejectionReason'])`, y sin `errors[]` con el
 * `Problem.detail` del backend (nunca toast genérico, el modal está abierto);
 * 409/412/403/404 → se delegan al padre (`onStatusError`). Éxito → toast + cierra.
 * El Modal aporta la a11y (role=dialog, aria-modal, foco inicial, focus-trap, Escape).
 */
export function RejectQuotationModal({
  isOpen,
  quotationId,
  etag,
  onClose,
  onStatusError,
}: RejectQuotationModalProps) {
  const changeStatus = useChangeQuotationStatus()
  const {
    register,
    handleSubmit,
    control,
    reset,
    setError,
    formState: { errors },
  } = useForm<RejectFormValues>({
    resolver: zodResolver(rejectSchema),
    defaultValues: { rejectionReason: '' },
  })

  // Al abrir/cerrar, limpiar el form (no arrastrar el motivo de un intento previo).
  useEffect(() => {
    if (isOpen) {
      reset({ rejectionReason: '' })
    }
  }, [isOpen, reset])

  // `useWatch` (con control) en vez de `watch()` para el contador: es la API que el React
  // Compiler memoiza sin advertencias (a diferencia de `useForm().watch`).
  const rejectionReason = useWatch({ control, name: 'rejectionReason' }) ?? ''

  function onSubmit(values: RejectFormValues) {
    changeStatus.mutate(
      {
        id: quotationId,
        ifMatch: etag ?? '',
        status: 'REJECTED',
        rejectionReason: values.rejectionReason,
      },
      {
        onSuccess: () => {
          toast.success('Cotización rechazada.')
          onClose()
        },
        onError: (error) => {
          // 400 (COM-001): motivo ausente/ inválido → inline en el campo (defensivo: el
          // front ya lo previene con zod). El 412 (ETag stale), 409 (transición inválida) y
          // 403/404 se delegan al padre, que centraliza banner/toast + refetch (y cierra el
          // modal en el 412).
          if (isBadRequestError(error) && !isPreconditionFailedError(error)) {
            if (hasFieldErrors(error)) {
              // 400 con `errors[]` → cada error a su campo (acá solo `rejectionReason`).
              handleApiFormError(error, {
                setError,
                fallbackMessage: REASON_FALLBACK_ERROR,
                allowedFields: ['rejectionReason'],
              })
            } else {
              // 400 SIN `errors[]` (ej. COM-001 a secas): el error es del motivo y el modal
              // está abierto → mostrarlo inline en el textarea (con el `detail` del backend),
              // no en un toast genérico.
              const problem = isAxiosError(error)
                ? (error.response?.data as Problem | undefined)
                : undefined
              setError('rejectionReason', {
                type: 'backend',
                message: problem?.detail ?? REASON_FALLBACK_ERROR,
              })
            }
            return
          }
          onStatusError?.(error)
        },
      },
    )
  }

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Rechazo de la cotización" size="md">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        <Textarea
          id="reject-reason"
          label="Motivo del rechazo"
          labelSlot={
            <span className="inline-flex items-center gap-1 rounded-full border border-blue-200 bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
              <span aria-hidden="true">🔒</span>
              interno
            </span>
          }
          rows={4}
          maxLength={REASON_MAX_LENGTH}
          showCounter
          value={rejectionReason}
          placeholder="Ej.: el cliente eligió otro proveedor; precio fuera de presupuesto…"
          helperText="Uso interno. No aparece en el PDF ni se envía al cliente."
          error={errors.rejectionReason?.message}
          register={register('rejectionReason')}
          sanitize={stripControlChars}
        />

        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={changeStatus.isPending}
            className={`${SECONDARY_BUTTON} ${DISABLED}`}
          >
            Cancelar
          </button>
          <button type="submit" disabled={changeStatus.isPending} className={`${DANGER_BUTTON} ${DISABLED}`}>
            {changeStatus.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
            Registrar rechazo
          </button>
        </div>
      </form>
    </Modal>
  )
}
