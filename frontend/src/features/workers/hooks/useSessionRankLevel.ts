import { useAuth } from '../../../shared/auth/AuthContext'
import { sessionRankLevel } from '../rank'
import { useRoles } from './useRoles'

/** El nivel de la sesión cruzado con el catálogo de roles por su nombre de sistema. */
export function useSessionRankLevel() {
  const { user } = useAuth()
  const roles = useRoles()
  return {
    level: roles.data ? sessionRankLevel(user?.role, roles.data) : undefined,
    roles,
  }
}
