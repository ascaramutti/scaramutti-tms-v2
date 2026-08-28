import { z } from 'zod'
import type { ChangeStatusRequest } from '../../../api'
import { NO_CONTROL, stripControlChars } from '../../../shared/utils/sanitizeText'
import { isFutureInLima, limaInputToIsoInstant } from '../utils/limaDate'
import type {
  ServiceExitTransition,
  ServiceProgressTransition,
} from '../status/serviceStatusTransitions'

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
  // El `.trim()` de acá es redundante: el builder recorta igual antes de mandar, y se
  // midió que borrarlo no mueve ninguna aserción. Se deja por simetría con el motivo de
  // la cancelación, donde el mismo `.trim()` SÍ decide (ahí corre antes del mínimo, así
  // que un texto de puros espacios no alcanza el largo). No unificarlos: significan
  // cosas distintas.
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
 * reja de conflictos de recursos y solo aplica al reabrir: mandarla en `true` en
 * cualquier otra transición es un rechazo. Ausente, null y false son lo mismo (no
 * forzar), justamente para que un formulario que serializa el objeto entero siga
 * andando. Se deja escrito para que nadie la agregue por simetría con el formulario de
 * asignación.
 */
export function toServiceProgressRequest(
  values: ServiceProgressFormValues,
  target: ServiceProgressTransition,
): ChangeStatusRequest {
  const note = stripControlChars(values.note).trim()
  return {
    target,
    dateTime: limaInputToIsoInstant(values.dateTime),
    note: note === '' ? null : note,
  }
}

/**
 * Mínimo del motivo, igual al que exige el contrato para las transiciones que sacan el
 * viaje del circuito. Es propio y no se comparte con la justificación de la edición:
 * hoy los dos valen lo mismo por coincidencia, pero son reglas de dos contratos
 * distintos y una constante compartida las acoplaría sin que nadie lo note.
 */
export const SERVICE_EXIT_REASON_MIN_LENGTH = 10

/**
 * Las tres salidas del circuito (cancelar, eliminar y reabrir) piden lo mismo: el motivo,
 * obligatorio.
 *
 * Ninguna lleva fecha, y no es que sea opcional: mandarla es un rechazo del servidor. No
 * fechan el viaje sino la decisión, y esa marca la pone el servidor.
 */
export const serviceExitFormSchema = z.object({
  note: z
    .string()
    .trim()
    .min(
      SERVICE_EXIT_REASON_MIN_LENGTH,
      `El motivo debe tener al menos ${SERVICE_EXIT_REASON_MIN_LENGTH} caracteres`,
    )
    .max(STATUS_NOTE_MAX_LENGTH, `Máximo ${STATUS_NOTE_MAX_LENGTH} caracteres`)
    .regex(NO_CONTROL, 'El texto tiene caracteres no permitidos'),
})

export type ServiceExitFormValues = z.infer<typeof serviceExitFormSchema>

/**
 * El cuerpo de una salida del circuito.
 *
 * La clave `dateTime` se OMITE en vez de mandarse en null. Las dos formas funcionan (el
 * servidor mira el valor y no la presencia de la clave, para que un formulario que
 * serialice el objeto entero siga andando), así que la elección es por cuerpo mínimo:
 * lo que no se manda no puede interpretarse mal más adelante.
 *
 * `force` se omite cuando es falsa: ausente y `false` son lo mismo para el servidor, y el
 * cuerpo mínimo no deja lugar a que alguien lea la clave como que acá se puede forzar
 * siempre. Quién puede pedirla no lo decide esta función, que acepta el destino y la
 * bandera por separado: lo decide el llamador, donde el botón de forzar solo existe al
 * reabrir. Mandarla en `true` en cualquier otra transición es un rechazo del servidor.
 */
export function toServiceExitRequest(
  values: ServiceExitFormValues,
  target: ServiceExitTransition,
  force = false,
): ChangeStatusRequest {
  const body: ChangeStatusRequest = {
    target,
    note: stripControlChars(values.note).trim(),
  }
  return force ? { ...body, force: true } : body
}
