import { getApiErrorMessage } from '../../shared/utils/getApiErrorMessage'
import { REACTIVATE_BLOCKED_REASON } from './accountActions'

const COMMON_ACCOUNT_ERRORS: Record<string, string> = {
  'COM-003': 'Tu rol no tiene permisos para esta acción.',
  'USR-008': 'No puedes gestionar la cuenta de alguien de tu nivel o superior.',
}

const ACCOUNT_STATUS_ERRORS: Record<string, string> = {
  ...COMMON_ACCOUNT_ERRORS,
  'USR-005': REACTIVATE_BLOCKED_REASON.workerInactive,
  'USR-006': REACTIVATE_BLOCKED_REASON.roleCannotLogin,
  'USR-007': 'No puedes desactivar tu propia cuenta.',
  'USR-009': 'Otra operación estaba en curso y no se pudo cambiar el estado. Intenta de nuevo.',
}

const RESET_PASSWORD_ERRORS: Record<string, string> = {
  ...COMMON_ACCOUNT_ERRORS,
  'USR-007': 'Tu propia contraseña se cambia en Cambiar contraseña.',
  'USR-009': 'Otra operación estaba en curso y no se pudo restablecer la contraseña. Intenta de nuevo.',
}

export function accountStatusErrorMessage(error: unknown): string {
  return accountErrorMessage(error, ACCOUNT_STATUS_ERRORS, 'No se pudo cambiar el estado de la cuenta. Intenta de nuevo.')
}

export function resetPasswordErrorMessage(error: unknown): string {
  return accountErrorMessage(error, RESET_PASSWORD_ERRORS, 'No se pudo restablecer la contraseña. Intenta de nuevo.')
}

function accountErrorMessage(error: unknown, messages: Record<string, string>, fallback: string): string {
  const code = problemCodeOf(error)
  return (code && messages[code]) || getApiErrorMessage(error, fallback)
}

function problemCodeOf(error: unknown): string | undefined {
  return (error as { response?: { data?: { code?: string } } })?.response?.data?.code
}
