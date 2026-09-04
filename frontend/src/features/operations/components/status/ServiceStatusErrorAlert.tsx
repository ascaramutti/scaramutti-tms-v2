import { getApiErrorMessage, isPreconditionFailedError } from '../../../../shared/utils/getApiErrorMessage'
import { Alert } from '../../../../shared/ui/Alert'

interface ServiceStatusErrorAlertProps {
  error: unknown
  /** Qué decir cuando el servidor no mandó cuerpo (red caída, 5xx pelado). */
  fallback: string
  /** Vuelve a pedir el detalle del viaje y cierra el diálogo. Solo se ofrece ante un
   * 412, y el texto del botón nombra las dos cosas: lo que se pierde al apretarlo es lo
   * que el usuario acababa de escribir. */
  onRefresh: () => void
}

/**
 * El error de una transición, con el texto que mandó el servidor.
 *
 * El mensaje sale del `detail` del backend y no de una tabla de códigos propia: el
 * servidor es el que sabe por qué rechazó (desde qué estado, qué dato falta), y
 * reescribirlo acá lo dejaría desincronizado en la primera edición del lado del servidor.
 *
 * El botón de recargar aparece SOLO ante un 412, que es la única familia donde volver a
 * pedir el viaje es literalmente la solución: la pantalla quedó vieja y el pedido llevaba
 * una versión que ya no corre. Ante un conflicto de estado no se ofrece, porque recargar
 * no cambia que desde ese estado no se pueda llegar al que se pidió.
 */
export function ServiceStatusErrorAlert({
  error,
  fallback,
  onRefresh,
}: ServiceStatusErrorAlertProps) {
  return (
    <Alert role="alert" className="rounded-lg px-3.5 py-3">
      <p className="text-sm text-danger-fg">
        {getApiErrorMessage(error, fallback)}
      </p>
      {isPreconditionFailedError(error) && (
        <button
          type="button"
          onClick={onRefresh}
          className="mt-2 inline-flex items-center rounded-lg border border-danger-border-strong bg-surface px-3 py-1.5 text-sm font-medium text-danger-fg hover:bg-danger-soft-strong focus:outline-none focus-visible:ring-2 focus-visible:ring-danger"
        >
          Descartar y recargar
        </button>
      )}
    </Alert>
  )
}
