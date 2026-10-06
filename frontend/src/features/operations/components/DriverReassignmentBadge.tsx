import { TriangleAlert } from 'lucide-react'
import { Badge } from '../../../shared/ui/Badge'

const DRIVER_REASSIGNMENT_LABEL = 'Reasignar conductor'
const DRIVER_REASSIGNMENT_REASON = 'Dado de baja, ficha apagada o cambió de cargo'

/**
 * La alerta del conductor que ya no se puede asignar, junto a su nombre en el detalle del viaje.
 * Se lee sin el color: ícono y texto, y debajo el porqué a la vista, una sola vez. La pastilla nunca
 * se parte: en dos líneas pierde la forma en la columna angosta.
 */
export function DriverReassignmentBadge() {
  return (
    <>
      <span className="mt-1 block whitespace-nowrap">
        <Badge variant="warning">
          <TriangleAlert className="h-3.5 w-3.5" aria-hidden="true" />
          {DRIVER_REASSIGNMENT_LABEL}
        </Badge>
      </span>
      <p className="mt-0.5 text-xs text-warning-fg">{DRIVER_REASSIGNMENT_REASON}</p>
    </>
  )
}
