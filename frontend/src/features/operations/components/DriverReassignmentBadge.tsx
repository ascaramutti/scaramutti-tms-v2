import { TriangleAlert } from 'lucide-react'
import { Badge } from '../../../shared/ui/Badge'

export const DRIVER_REASSIGNMENT_LABEL = 'Reasignar conductor'
export const DRIVER_REASSIGNMENT_REASON =
  'El conductor asignado ya no está habilitado: dado de baja, ficha apagada o cambió de cargo'

/**
 * La alerta del viaje cuyo conductor ya no se puede asignar. Se lee sin el color: ícono y texto,
 * y el porqué va dentro del mismo texto, solo para el lector de pantalla (el visible irá en el
 * detalle). Nunca se parte: una pastilla en dos líneas pierde la forma en la columna angosta.
 */
export function DriverReassignmentBadge() {
  return (
    <span className="mt-1 block whitespace-nowrap">
      <Badge variant="warning">
        <TriangleAlert className="h-3.5 w-3.5" aria-hidden="true" />
        {DRIVER_REASSIGNMENT_LABEL}
        <span className="sr-only">. {DRIVER_REASSIGNMENT_REASON}</span>
      </Badge>
    </span>
  )
}
