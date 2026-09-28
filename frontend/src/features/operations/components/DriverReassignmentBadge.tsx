import { TriangleAlert } from 'lucide-react'
import { Badge } from '../../../shared/ui/Badge'

export const DRIVER_REASSIGNMENT_LABEL = 'Reasignar conductor'
/**
 * En minúscula porque casi siempre sigue a la etiqueta ("Reasignar conductor: dado de baja…");
 * donde va sola, a la vista, la vista pone la mayúscula inicial. El texto vive una sola vez.
 */
export const DRIVER_REASSIGNMENT_REASON = 'dado de baja, ficha apagada o cambió de cargo'

const VISIBLE_REASON =
  DRIVER_REASSIGNMENT_REASON.charAt(0).toUpperCase() + DRIVER_REASSIGNMENT_REASON.slice(1)

interface DriverReassignmentBadgeProps {
  /** `true` cuando el porqué va a la vista, debajo de la pastilla. */
  reasonVisible?: boolean
}

/**
 * La alerta del viaje cuyo conductor ya no se puede asignar. Se lee sin el color: ícono y texto.
 * El porqué va dentro del texto, solo para el lector, donde no se ve; donde se ve, sin esa copia
 * oculta, que el lector anunciaría dos veces. La pastilla nunca se parte: en dos líneas pierde la
 * forma en la columna angosta.
 */
export function DriverReassignmentBadge({ reasonVisible = false }: DriverReassignmentBadgeProps) {
  return (
    <>
      <span className="mt-1 block whitespace-nowrap">
        <Badge variant="warning">
          <TriangleAlert className="h-3.5 w-3.5" aria-hidden="true" />
          {DRIVER_REASSIGNMENT_LABEL}
          {!reasonVisible && <span className="sr-only">: {DRIVER_REASSIGNMENT_REASON}</span>}
        </Badge>
      </span>
      {reasonVisible && <p className="mt-0.5 text-xs text-warning-fg">{VISIBLE_REASON}</p>}
    </>
  )
}
