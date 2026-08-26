import { z } from 'zod'
import type { AddResourcesRequest } from '../../../api'
import { NO_CONTROL } from '../../../shared/utils/sanitizeText'

/** Mínimo del motivo, medido DESPUÉS de recortar los bordes. */
export const REINFORCEMENT_REASON_MIN_LENGTH = 10

/** Tope del motivo, espejo del `maxLength` del contrato. */
export const REINFORCEMENT_REASON_MAX_LENGTH = 500

const optionalResourceId = z.number().int().positive().nullable()

/**
 * `POST /services/{id}/resources`: refuerzos de un viaje que ya arrancó.
 *
 * Los tres recursos son opcionales por separado pero al menos uno tiene que venir. Es
 * una condición ENTRE campos, así que no se puede declarar en el tipo de cada uno y
 * la valida un refinamiento, igual que hace el servidor.
 *
 * El motivo es obligatorio, a diferencia de la nota de la asignación, y su mínimo se
 * mide sobre el texto YA recortado: diez espacios no son un motivo. El `.trim()` de
 * zod corre antes que el `.min()`, que es exactamente lo que el patrón del contrato
 * codifica.
 */
export const addResourcesFormSchema = z
  .object({
    driverId: optionalResourceId,
    tractorId: optionalResourceId,
    trailerId: optionalResourceId,
    reason: z
      .string()
      .trim()
      .min(
        REINFORCEMENT_REASON_MIN_LENGTH,
        `El motivo debe tener al menos ${REINFORCEMENT_REASON_MIN_LENGTH} caracteres`,
      )
      .max(
        REINFORCEMENT_REASON_MAX_LENGTH,
        `Máximo ${REINFORCEMENT_REASON_MAX_LENGTH} caracteres`,
      )
      .regex(NO_CONTROL, 'No se permiten caracteres de control'),
  })
  .superRefine((values, ctx) => {
    if (values.driverId === null && values.tractorId === null && values.trailerId === null) {
      ctx.addIssue({
        code: 'custom',
        // El mensaje es del GRUPO y no del conductor. El `path` es solo el vehículo
        // para que react-hook-form lo exponga con una clave legible: se muestra una
        // vez, arriba de los tres campos, y no repetido bajo cada uno.
        path: ['driverId'],
        message: 'Elige al menos un conductor, tracto o carreta',
      })
    }
  })

export type AddResourcesFormInput = z.infer<typeof addResourcesFormSchema>

/** Estado inicial del formulario, con los tipos finales ya puestos. */
export const DEFAULT_ADD_RESOURCES_VALUES: AddResourcesFormInput = {
  driverId: null,
  tractorId: null,
  trailerId: null,
  reason: '',
}

/** Arma el cuerpo del pedido. El motivo viaja recortado, que es como se mide. */
export function toAddResourcesRequest(
  values: AddResourcesFormInput,
  force: boolean,
): AddResourcesRequest {
  return {
    driverId: values.driverId,
    tractorId: values.tractorId,
    trailerId: values.trailerId,
    reason: values.reason.trim(),
    force,
  }
}
