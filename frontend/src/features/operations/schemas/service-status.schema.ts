import { z } from 'zod'
import type { ChangeStatusRequest } from '../../../api'
import { NO_CONTROL, stripControlChars } from '../../../shared/utils/sanitizeText'
import { isFutureInLima, limaInputToIsoInstant } from '../utils/limaDate'
import type { ServiceStatusTransition } from '../status/serviceStatusTransitions'

/** Tope del texto libre, igual al que declara el contrato para `note`. */
export const STATUS_NOTE_MAX_LENGTH = 500

/**
 * Forma que entrega un `<input type="datetime-local">`: `YYYY-MM-DDTHH:mm`, con los
 * segundos opcionales porque hay navegadores que los agregan.
 */
const WALL_CLOCK_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/

/**
 * Iniciar y finalizar: la fecha y hora real del viaje, más una nota opcional.
 *
 * La fecha es obligatoria acá aunque el contrato la admita ausente (donde significa "el
 * instante en que llega el pedido"). Es una decisión de esta pantalla: el campo viene
 * precargado y es editable, porque el viaje suele registrarse un rato después de que
 * pasó, así que mandarlo vacío guardaría una hora que nadie eligió.
 */
export const serviceProgressFormSchema = z.object({
  dateTime: z
    .string()
    .regex(WALL_CLOCK_PATTERN, 'Indica la fecha y la hora')
    /*
     * El futuro se rechaza acá, en la entrada. Es una guarda contra el tipeo errado (el
     * mes cambiado, el año del calendario), no una garantía sobre el dato: el servidor
     * admite una fecha futura, y lo hace a propósito. Lo único que acota es la ventana
     * de negocio de 1900 a 2999 (`ServiceRequestParsing`, en `operations/util`), y del
     * par inicio/fin cuida solo el orden (`ChangeServiceStatusService`). Se deja abierto
     * para poder corregir una fecha a mano cuando haga falta, como en las filas que
     * vinieron del sistema anterior. Si algún día conviene cerrarlo, se cierra ahí.
     *
     * Se mide contra el reloj de Lima y no contra el del navegador: quien registra el
     * viaje desde otro país sigue anotando la hora a la que salió el camión en Perú.
     */
    .refine((wallClock) => !isFutureInLima(wallClock), 'La fecha no puede estar en el futuro'),
  note: z
    .string()
    .trim()
    .max(STATUS_NOTE_MAX_LENGTH, `Máximo ${STATUS_NOTE_MAX_LENGTH} caracteres`)
    .regex(NO_CONTROL, 'El texto tiene caracteres no permitidos'),
})

export type ServiceProgressFormValues = z.infer<typeof serviceProgressFormSchema>

/**
 * El cuerpo del pedido a partir de lo que el formulario recogió.
 *
 * La nota en blanco viaja como `null` y no como cadena vacía: el texto se guarda en la
 * bitácora del viaje, y una entrada vacía es peor que ninguna.
 *
 * `force` no se manda nunca. Es la única bandera que autoriza al servidor a pisar la
 * reja de conflictos de recursos, solo aplica al reabrir, y mandarla en cualquier otra
 * transición es un rechazo. Se deja escrito para que nadie la agregue por simetría con
 * el formulario de asignación.
 */
export function toServiceProgressRequest(
  values: ServiceProgressFormValues,
  target: ServiceStatusTransition,
): ChangeStatusRequest {
  const note = stripControlChars(values.note).trim()
  return {
    target,
    dateTime: limaInputToIsoInstant(values.dateTime),
    note: note === '' ? null : note,
  }
}
