import type { ServiceEventResponse } from '../../../../api'
import { Badge } from '../../../../shared/ui/Badge'
import { formatDateTime } from '../../../../shared/utils/formatters'
import { SERVICE_EVENT_PRESENTATION } from '../../status/serviceEventPresentation'

interface ServiceTimelineProps {
  events: ServiceEventResponse[]
}

/**
 * Bitácora del viaje: una entrada por acción, en el orden cronológico en que el
 * servidor las manda (ascendente). No se reordena acá: el orden es parte de lo
 * que el contrato entrega, y rehacerlo en la pantalla sería otra fuente de verdad.
 *
 * Es una lista y no una tabla porque el texto de una entrada puede ocupar varias
 * líneas y no hay nada que comparar entre columnas.
 *
 * La bitácora la lee TODO el que pueda leer el detalle, incluido el despacho, que
 * no ve importes. Por eso el servidor ya escribe las entradas sin los valores de
 * precio y moneda; acá no hace falta filtrar nada, y filtrar por texto sería
 * adivinar.
 */
export function ServiceTimeline({ events }: ServiceTimelineProps) {
  if (events.length === 0) {
    return (
      <p className="text-sm text-slate-500">
        Este viaje todavía no tiene movimientos registrados.
      </p>
    )
  }

  return (
    <ol className="space-y-4">
      {events.map((event) => {
        const { label, badgeVariant } = SERVICE_EVENT_PRESENTATION[event.eventType]
        return (
          <li key={event.id} className="border-l-2 border-slate-200 pl-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={badgeVariant}>{label}</Badge>
              <span className="text-xs text-slate-500">
                {event.createdBy.fullName} · {formatDateTime(event.createdAt)}
              </span>
            </div>
            {/* `whitespace-pre-line` y no un párrafo común, y no es solo por el
                dato heredado: v2 escribe una LÍNEA POR DATO y las une con saltos
                reales al asignar recursos, al sumarlos, al darlos de baja, al
                mover el estado y al editar. Solo el alta escribe una sola línea.
                De las 825 entradas migradas, además, 9 concatenan varios
                movimientos igual. Sin esto, todas se leerían como un bloque
                corrido.

                El `trim` es por las otras 37 heredadas que traen saltos: los suyos
                están al principio o al final, y respetados al pie de la letra
                dibujan una línea en blanco antes o después del texto. Se recortan
                los de los BORDES y se conservan los de adentro, que son los que
                separan. */}
            <p className="mt-1 whitespace-pre-line break-words text-sm text-slate-900">
              {event.note.trim()}
            </p>
          </li>
        )
      })}
    </ol>
  )
}
