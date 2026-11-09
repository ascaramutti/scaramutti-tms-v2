import { getApiErrorMessage } from '../../shared/utils/getApiErrorMessage'
import { REACTIVATE_BLOCKED_REASON } from './accountActions'

const ACCOUNT_STATUS_ERRORS: Record<string, string> = {
  'COM-003': 'Tu rol no tiene permisos para esta acción.',
  'USR-005': REACTIVATE_BLOCKED_REASON.workerInactive,
  'USR-006': REACTIVATE_BLOCKED_REASON.roleCannotLogin,
  'USR-007': 'No puedes desactivar tu propia cuenta.',
  'USR-008': 'No puedes gestionar la cuenta de alguien de tu nivel o superior.',
  'USR-009': 'Otra operación estaba en curso y no se pudo cambiar el estado. Intenta de nuevo.',
}

export function accountStatusErrorMessage(error: unknown): string {
  const code = problemCodeOf(error)
  return (
    (code && ACCOUNT_STATUS_ERRORS[code]) ||
    getApiErrorMessage(error, 'No se pudo cambiar el estado de la cuenta. Intenta de nuevo.')
  )
}

function problemCodeOf(error: unknown): string | undefined {
  return (error as { response?: { data?: { code?: string } } })?.response?.data?.code
}
