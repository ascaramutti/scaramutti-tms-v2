import { getApiErrorMessage, isPreconditionFailedError } from '../../../../shared/utils/getApiErrorMessage'

interface ServiceStatusErrorAlertProps {
  error: unknown
  /** Qué decir cuando el servidor no mandó cuerpo (red caída, 5xx pelado). */
  fallback: string
  /** Vuelve a pedir el detalle del viaje. Solo se ofrece ante un 412. */
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
    <div className="rounded-lg border border-red-200 bg-red-50 px-3.5 py-3">
      <p role="alert" className="text-sm text-red-700">
        {getApiErrorMessage(error, fallback)}
      </p>
      {isPreconditionFailedError(error) && (
        <button
          type="button"
          onClick={onRefresh}
          className="mt-2 inline-flex items-center rounded-lg border border-red-300 bg-white px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
        >
          Actualizar datos
        </button>
      )}
    </div>
  )
}
