import { AlertTriangle } from 'lucide-react'
import { SERVICE_STATUS_PRESENTATION } from '../../status/serviceStatusPresentation'
import { SERVICE_RESOURCE_LABELS } from '../../status/resourcePresentation'
import type { ServiceOperationError } from '../../utils/serviceResourceConflict'
import { Alert } from '../../../../shared/ui/Alert'

interface ResourceConflictAlertProps {
  error: ServiceOperationError
  /** Rótulo del botón que reintenta forzando. Solo se usa si el conflicto es forzable. */
  forceLabel: string
  /**
   * Qué queda después de forzar, cuando forzar deja algo que el usuario debería saber.
   *
   * Se anuncia por el `aria-describedby` del botón, o sea al enfocarlo: la región viva es
   * el párrafo del encabezado (ver abajo), y este texto es hermano suyo, no hijo, así que
   * no se lee solo al aparecer.
   *
   * Va DENTRO del recuadro igual, por dos razones que se ven al sacarlo: aparece antes que
   * el botón en el orden de lectura, o sea antes de la acción que explica, y comparte el
   * fondo del aviso en vez de quedar en el registro tipográfico de la letra chica.
   *
   * Solo lo pasa quien lo necesita: al asignar recursos no aplica, porque ahí el usuario
   * todavía puede elegir otro.
   */
  forceConsequence?: string
  onForce: () => void
  isPending: boolean
}

/**
 * Encabezado del aviso cuando hay una tabla de conflictos debajo.
 *
 * Es GENÉRICO a propósito, y va contra la regla de la casa de mostrar el `detail` del
 * backend. El motivo: ese `detail` RESUME la tabla (nombra al primer recurso y cuenta
 * cuántos quedan), y el resumen se desalinea con lo que se lista abajo. El servidor
 * cuenta recursos DISTINTOS y la tabla lleva una fila por conflicto, así que un mismo
 * conductor tomado por dos viajes da "no hay más en conflicto" arriba y dos filas
 * abajo, con la segunda sin explicación.
 *
 * Un encabezado que no resume no se puede desalinear: elimina la clase entera de
 * defecto y no este caso. "Uno o más" evita además la lógica de singular y plural,
 * que es otra fuente de lo mismo.
 */
const CONFLICT_HEADING = 'Uno o más recursos ya están asignados a otro viaje.'

/*
 * Id fijo y no generado: el párrafo al que apunta solo se renderiza cuando llega
 * `forceConsequence`, y un solo llamador la pasa (`grep -rn "forceConsequence="`). Si un
 * segundo la pasara, este id habría que generarlo.
 */
const FORCE_CONSEQUENCE_ID = 'force-consequence'

/**
 * El aviso de un conflicto de recursos: qué recurso choca y en qué viaje, y el botón
 * de forzar cuando corresponde.
 *
 * Con tabla, el encabezado es genérico y la TABLA es la única fuente de verdad (ver
 * arriba). Sin tabla, se muestra el `detail` del backend tal cual: ahí no hay nada
 * que resumir, y ese texto es todo lo que el usuario tiene. Por acá pasa el conflicto
 * duro, donde el servidor nombra el recurso que ya participa de este mismo viaje, y
 * también el resto de los errores con detalle: el estado que no admite la acción, el
 * viaje cerrado, el bloqueo transitorio y el recurso dado de baja.
 *
 * El botón de forzar aparece SOLO cuando el error es forzable. El conflicto duro llega
 * sin esa bandera, y ofrecerle forzar al usuario sería ofrecerle un camino que el
 * servidor rechaza igual.
 */
export function ResourceConflictAlert({
  error,
  forceLabel,
  forceConsequence,
  onForce,
  isPending,
}: ResourceConflictAlertProps) {
  return (
    <Alert variant="warning" role={undefined} className="flex items-start gap-3 rounded-lg px-4 py-3">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden="true" />
      <div className="flex-1">
        {/* El `alert` va en el PÁRRAFO y no en el recuadro entero: una región viva se
            anuncia de corrido y sin estructura, así que con la tabla adentro el
            lector recita las cuatro cabeceras y las N filas seguidas, que es
            justamente el detalle que se pierde. La tabla queda al lado, navegable con
            los comandos de tabla, y el botón fuera de la región viva. */}
        <p
          role="alert"
          className={`text-sm text-amber-900${forceConsequence ? ' font-medium' : ''}`}
        >
          {error.conflicts.length > 0 ? CONFLICT_HEADING : error.detail}
        </p>

        {error.conflicts.length > 0 && (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-left text-xs">
              <caption className="sr-only">Recursos en conflicto</caption>
              <thead>
                <tr className="text-amber-800">
                  <th scope="col" className="pb-1 pr-4 font-medium">
                    Recurso
                  </th>
                  <th scope="col" className="pb-1 pr-4 font-medium">
                    Nombre o placa
                  </th>
                  <th scope="col" className="pb-1 pr-4 font-medium">
                    Viaje
                  </th>
                  <th scope="col" className="pb-1 font-medium">
                    Estado
                  </th>
                </tr>
              </thead>
              <tbody className="text-amber-900">
                {error.conflicts.map((conflict) => (
                  <tr key={`${conflict.resource}-${conflict.serviceCode}`}>
                    <td className="py-0.5 pr-4">{SERVICE_RESOURCE_LABELS[conflict.resource]}</td>
                    <td className="py-0.5 pr-4">{conflict.resourceName}</td>
                    <td className="py-0.5 pr-4">{conflict.serviceCode}</td>
                    <td className="py-0.5">
                      {SERVICE_STATUS_PRESENTATION[conflict.serviceStatus].label}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {error.forcible && (
          <>
            {forceConsequence && (
              <p id={FORCE_CONSEQUENCE_ID} className="mt-3 text-sm text-amber-900">
                {forceConsequence}
              </p>
            )}
            <button
              type="button"
              onClick={onForce}
              disabled={isPending}
              aria-describedby={forceConsequence ? FORCE_CONSEQUENCE_ID : undefined}
              className="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-1.5 text-xs font-medium text-amber-900 hover:bg-amber-100 disabled:opacity-50"
            >
              {forceLabel}
            </button>
          </>
        )}
      </div>
    </Alert>
  )
}
