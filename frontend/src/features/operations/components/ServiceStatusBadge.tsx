import type { ServiceStatus } from '../../../api'
import { Badge } from '../../../shared/ui/Badge'
import { SERVICE_STATUS_PRESENTATION } from '../status/serviceStatusPresentation'

interface ServiceStatusBadgeProps {
  status: ServiceStatus
}

/** Estado del servicio como pill. El color acompaña al texto, nunca lo reemplaza. */
export function ServiceStatusBadge({ status }: ServiceStatusBadgeProps) {
  const { label, badgeVariant } = SERVICE_STATUS_PRESENTATION[status]
  return <Badge variant={badgeVariant}>{label}</Badge>
}
