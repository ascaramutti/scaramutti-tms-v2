import type { RoleResponse } from '../../api'

/**
 * El nivel del rol de la sesión en el organigrama. El admin no tiene techo; un rol que
 * no está en el catálogo vale cero, así que no puede asignar nada: se deniega por
 * defecto y el backend es la autoridad.
 */
export function sessionRankLevel(role: string | undefined, roles: readonly RoleResponse[] | undefined): number {
  if (role === 'admin') return Infinity
  return roles?.find((candidate) => candidate.name === role)?.level ?? 0
}

/** Los cargos que la sesión puede asignar: los de nivel estrictamente menor al suyo. */
export function assignableRoles(roles: readonly RoleResponse[], level: number): RoleResponse[] {
  return roles.filter((role) => role.level < level)
}

/** La sesión gestiona a un trabajador si su cargo es de nivel estrictamente menor; el admin, siempre. */
export function canManageWorker(workerRoleLevel: number, sessionLevel: number): boolean {
  return workerRoleLevel < sessionLevel
}
