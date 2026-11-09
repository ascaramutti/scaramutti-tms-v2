import type { SessionUser, UserAccountDetailResponse } from '../../api'
import { canManageWorker } from '../workers/rank'

export type ReactivateBlockedBy = 'workerInactive' | 'roleCannotLogin'

export interface AccountActions {
  isOwn: boolean
  isOutOfRank: boolean
  canDeactivate: boolean
  canReactivate: boolean
  reactivateBlockedBy: ReactivateBlockedBy | null
}

export const REACTIVATE_BLOCKED_REASON: Record<ReactivateBlockedBy, string> = {
  workerInactive: 'Su trabajador está dado de baja. Primero reactiva al trabajador en Trabajadores.',
  roleCannotLogin: 'El cargo de su trabajador no inicia sesión. Primero cámbiale el cargo en Trabajadores.',
}

export function accountActions(
  account: UserAccountDetailResponse,
  session: SessionUser | null,
  sessionLevel: number | undefined,
): AccountActions {
  const isOwn = session?.id === account.id
  const inRank = sessionLevel !== undefined && canManageWorker(account.role.level, sessionLevel)
  const canChange = inRank && !isOwn
  const reactivateBlockedBy =
    !canChange || account.isActive
      ? null
      : !account.worker.isActive
        ? 'workerInactive'
        : !account.role.canLogin
          ? 'roleCannotLogin'
          : null
  return {
    isOwn,
    isOutOfRank: sessionLevel !== undefined && !inRank && !isOwn,
    canDeactivate: canChange && account.isActive,
    canReactivate: canChange && !account.isActive && reactivateBlockedBy === null,
    reactivateBlockedBy,
  }
}
