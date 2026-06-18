import type { MouseEvent } from 'react'
import { Ban, Check, Loader2, Send } from 'lucide-react'
import { useAuth } from '../../../shared/auth/AuthContext'
import { DANGER_BUTTON, PRIMARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { useChangeQuotationStatus } from '../hooks/useChangeQuotationStatus'
import type { QuotationStatus } from '../../../api'
import {
  canChangeQuotationStatus,
  QUOTATION_STATUS_PRESENTATION,
  type QuotationStatusAction,
  type StatusActionVariant,
} from './quotationStatusPresentation'

interface QuotationStatusActionsProps {
  quotationId: number
  status: QuotationStatus
  /** ETag opaco del GET, para el `If-Match` de la transición. */
  etag?: string | null
  /** Abre el modal de rechazo (el rechazo exige motivo). */
  onRejectClick?: () => void
  /** Reporta un error de la mutación de Enviar/Aceptar al padre (toast/banner + refetch). */
  onStatusError?: (error: unknown) => void
}

const VARIANT_BUTTON: Record<StatusActionVariant, string> = {
  primary: PRIMARY_BUTTON,
  success:
    'inline-flex items-center rounded-lg bg-teal-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-teal-700 focus:outline-none focus:ring-2 focus:ring-teal-500 focus:ring-offset-2',
  danger: DANGER_BUTTON,
}

const VARIANT_ICON: Record<StatusActionVariant, typeof Send> = {
  primary: Send,
  success: Check,
  danger: Ban,
}

const DISABLED = 'disabled:cursor-not-allowed disabled:opacity-60'

/** Las acciones de transición disponibles desde un estado, o `null` si no hay nada que
 * mostrar (estado terminal o el rol no puede transicionar). Aísla el gate de rol/terminal
 * para no instanciar la mutación en una rama de early-return. */
function useVisibleStatusActions(status: QuotationStatus): readonly QuotationStatusAction[] | null {
  const { user } = useAuth()
  const actions = QUOTATION_STATUS_PRESENTATION[status].actions
  if (actions.length === 0 || !canChangeQuotationStatus(user?.role)) {
    return null
  }
  return actions
}

interface StatusActionButtonProps {
  action: QuotationStatusAction
  /** `true` mientras esta acción está mutando: muestra spinner. */
  isPending?: boolean
  /** `true` si el botón está deshabilitado (hay una transición en vuelo). */
  disabled?: boolean
  onClick: (event: MouseEvent<HTMLButtonElement>, action: QuotationStatusAction) => void
}

/** Un botón de acción de transición: icono/color por variante, spinner mientras muta. */
function StatusActionButton({ action, isPending, disabled, onClick }: StatusActionButtonProps) {
  const Icon = VARIANT_ICON[action.variant]
  return (
    <button
      type="button"
      onClick={(event) => onClick(event, action)}
      disabled={disabled}
      className={`${VARIANT_BUTTON[action.variant]} ${DISABLED}`}
    >
      {isPending ? (
        <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
      ) : (
        <Icon className="mr-2 h-4 w-4" aria-hidden="true" />
      )}
      {action.label}
    </button>
  )
}

/**
 * Botones contextuales de transición de estado del **detalle**, leídos de la fuente de
 * verdad única (`QUOTATION_STATUS_PRESENTATION[status].actions`). Si no hay acciones
 * (estados terminales) o el rol no puede cambiar el estado → no renderiza nada.
 *
 * Enviar/Aceptar disparan la mutación directo (un click, sin confirmación); Rechazar delega
 * en `onRejectClick` (el modal exige motivo). Instancia `useChangeQuotationStatus` para mutar
 * y reflejar el spinner/disabled en vuelo.
 */
export function QuotationStatusActions({
  quotationId,
  status,
  etag,
  onRejectClick,
  onStatusError,
}: QuotationStatusActionsProps) {
  const changeStatus = useChangeQuotationStatus()
  const actions = useVisibleStatusActions(status)
  if (actions === null) {
    return null
  }

  function handleClick(event: MouseEvent<HTMLButtonElement>, action: QuotationStatusAction) {
    event.stopPropagation()
    if (action.requiresReason) {
      onRejectClick?.()
      return
    }
    changeStatus.mutate(
      { id: quotationId, ifMatch: etag ?? '', status: action.target },
      { onError: (error) => onStatusError?.(error) },
    )
  }

  // En vuelo: qué target está mutando, para el spinner + disabled.
  const isBusy = changeStatus.isPending
  const pendingTarget = isBusy ? changeStatus.variables?.status : undefined

  return (
    <div className="flex flex-wrap items-center gap-2">
      {actions.map((action) => (
        <StatusActionButton
          key={action.target}
          action={action}
          isPending={pendingTarget === action.target}
          disabled={isBusy}
          onClick={handleClick}
        />
      ))}
    </div>
  )
}
