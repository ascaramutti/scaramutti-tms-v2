import { z } from 'zod'
import type { AssignResourcesRequest } from '../../../api'
import { NO_CONTROL } from '../../../shared/utils/sanitizeText'
import { trimToNull } from '../../../shared/utils/trimToNull'

/** Tope de la nota, espejo del `maxLength` del contrato. */
export const ASSIGNMENT_NOTE_MAX_LENGTH = 500

/**
 * Los tres ids salen de comboboxes controlados, que entregan un número o `null`, y
 * nunca la cadena vacía. Por eso se validan como número directo y NO con
 * `z.coerce.number()`: la coerción convierte `null` y `''` en `0`, que pasa el
 * chequeo de entero y muere recién en el de positivo, con el mensaje equivocado.
 *
 * Es la otra cara de la trampa que ya mordió en el alta del servicio, donde el campo
 * numérico vacío llegaba como `0`. Acá no hay ningún campo numérico de texto que
 * registrar, y la forma de que siga siendo así es no coercionar.
 */
const requiredResourceId = (message: string) =>
  z.number({ message }).int(message).positive(message)

const optionalResourceId = z.number().int().positive().nullable()

/**
 * `POST /services/{id}/assignment`: conductor y tracto obligatorios, carreta opcional.
 *
 * `force` NO es campo del formulario. Vive como argumento del envío porque, guardado
 * en el form, quedaría en `true` después del primer forzado y una selección posterior
 * viajaría forzada sin que nadie lo haya pedido.
 */
export const assignResourcesFormSchema = z.object({
  driverId: requiredResourceId('Selecciona el conductor'),
  tractorId: requiredResourceId('Selecciona el tracto'),
  trailerId: optionalResourceId,
  note: z
    .string()
    .trim()
    .max(ASSIGNMENT_NOTE_MAX_LENGTH, `Máximo ${ASSIGNMENT_NOTE_MAX_LENGTH} caracteres`)
    // Regla de la casa para los textos libres. Es más estricta que el servidor, que
    // en este campo solo rechaza el byte NUL (el que además no entra en la columna);
    // los demás controles no llegan a escribirse porque el textarea los limpia al
    // tipear. Los saltos de línea SÍ pasan: el servidor los aplasta al escribir la
    // bitácora, no los rechaza, así que el formulario no tiene por qué prohibirlos.
    .regex(NO_CONTROL, 'No se permiten caracteres de control'),
})

export type AssignResourcesFormInput = z.infer<typeof assignResourcesFormSchema>

/** Estado inicial del formulario, con los tipos finales ya puestos. */
export const DEFAULT_ASSIGN_RESOURCES_VALUES: AssignResourcesFormInput = {
  // El cast es porque los obligatorios no admiten `null` en el tipo de salida, y el
  // formulario arranca sin elegir. Un `undefined` por omisión sería peor: es el valor
  // con el que react-hook-form no atrapa el campo intacto.
  driverId: null as unknown as number,
  tractorId: null as unknown as number,
  trailerId: null,
  note: '',
}

/**
 * Arma el cuerpo del pedido. La nota en blanco viaja como `null` y no como cadena
 * vacía: el contrato dice que una nota en blanco se trata como ausente, y mandar el
 * `null` explícito deja el cuerpo completo.
 */
export function toAssignResourcesRequest(
  values: AssignResourcesFormInput,
  force: boolean,
): AssignResourcesRequest {
  return {
    driverId: values.driverId,
    tractorId: values.tractorId,
    trailerId: values.trailerId,
    note: trimToNull(values.note),
    force,
  }
}
