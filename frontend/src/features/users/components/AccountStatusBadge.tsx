import { Badge } from '../../../shared/ui/Badge'

export function AccountStatusBadge({ isActive }: { isActive: boolean }) {
  return isActive ? <Badge variant="success">Activa</Badge> : <Badge>Inactiva</Badge>
}
