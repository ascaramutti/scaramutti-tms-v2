import { Badge } from '../../../shared/ui/Badge'

/** Estado del trabajador en el padrón, con texto: el color no va solo. */
export function WorkerStatusBadge({ isActive }: { isActive: boolean }) {
  return isActive ? <Badge variant="success">Activo</Badge> : <Badge>Inactivo</Badge>
}
