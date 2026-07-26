import { z } from 'zod'

/**
 * Tope de la cantidad a retirar: 4 dígitos (< 10000). No es del contrato (que
 * solo acota `exclusiveMinimum: 0`) sino una cota razonable del frontend, espejo
 * del tope de las entradas. El límite real de un retiro es el stock disponible,
 * que valida el backend bajo lock (409 WH-001): el front no puede garantizarlo.
 */
export const WITHDRAWAL_MAX_QUANTITY = 9999
/**
 * Tope del frontend, no del contrato (que tipa `observations` sin `maxLength`).
 * Más estricto a propósito: un texto desmedido no aporta y el backend lo cortaría.
 */
export const WITHDRAWAL_OBSERVATIONS_MAX_LENGTH = 500

/**
 * Form de registro de un retiro. Espeja `WarehouseWithdrawalRequest`, que es un
 * único producto + cantidad (no una tabla de ítems como la entrada): un retiro
 * descuenta stock de un solo producto.
 *
 * La unidad de flota NO se valida acá: es opcional y su disyunción (a lo sumo una
 * de tracto/carreta/escolta, RN-WH2) queda garantizada estructuralmente en el
 * mapper (a lo sumo un `*Id` se setea según el `kind`), no puede violar WH-005
 * desde la UI. Vive en el estado del form y se lee al enviar.
 *
 * El stock disponible tampoco es una regla del schema: es un dato async y externo
 * que puede cambiar entre la lectura y el envío. El form avisa si la cantidad lo
 * supera, pero la autoridad es el 409 WH-001 del backend (validado en transacción).
 */
export const withdrawalFormSchema = z.object({
  productId: z
    .number({ message: 'Selecciona el producto' })
    .int()
    .positive('Selecciona el producto'),
  quantity: z
    .number({ message: 'Indica la cantidad' })
    .positive('La cantidad debe ser mayor a 0')
    .max(WITHDRAWAL_MAX_QUANTITY, `Máximo ${WITHDRAWAL_MAX_QUANTITY}`),
  receivedByWorkerId: z
    .number({ message: 'Indica quién recibe' })
    .int()
    .positive('Indica quién recibe'),
  observations: z
    .string()
    .trim()
    .max(
      WITHDRAWAL_OBSERVATIONS_MAX_LENGTH,
      `Máximo ${WITHDRAWAL_OBSERVATIONS_MAX_LENGTH} caracteres`,
    )
    .optional(),
})

export type WithdrawalFormInput = z.infer<typeof withdrawalFormSchema>

/**
 * Espejo de `WarehouseWithdrawalUpdateRequest.reason` en el contrato
 * (`minLength: 10, maxLength: 500`, requerido). La edición exige una justificación
 * que va a `almacen.audit_logs` con el diff por campo (RN-WH4). Constantes
 * propias, no las de la anulación: son dos reglas de dominio distintas aunque hoy
 * coincidan los números.
 */
export const WITHDRAWAL_EDIT_REASON_MIN_LENGTH = 10
export const WITHDRAWAL_EDIT_REASON_MAX_LENGTH = 500

/**
 * Form de EDICIÓN de un retiro. Extiende el de alta con el motivo obligatorio. El
 * `productId` sigue en el schema (prefilled, read-only en la UI) pero NO viaja al
 * PUT: el producto es INMUTABLE (RN-WH4, producto equivocado = anular y registrar
 * otro) y el contrato no lo acepta. Validar un campo que no se envía nunca bloquea.
 */
export const withdrawalEditFormSchema = withdrawalFormSchema.extend({
  reason: z
    .string()
    .trim()
    .min(
      WITHDRAWAL_EDIT_REASON_MIN_LENGTH,
      `El motivo debe tener al menos ${WITHDRAWAL_EDIT_REASON_MIN_LENGTH} caracteres`,
    )
    .max(
      WITHDRAWAL_EDIT_REASON_MAX_LENGTH,
      `Máximo ${WITHDRAWAL_EDIT_REASON_MAX_LENGTH} caracteres`,
    ),
})

export type WithdrawalEditFormInput = z.infer<typeof withdrawalEditFormSchema>

/**
 * Valores iniciales del form. El producto y el trabajador en 0 disparan sus
 * "selecciona…" del schema; la cantidad arranca sin valor (`NaN` es lo que produce
 * un input numérico vacío con `valueAsNumber`), no en 0, para no aparentar un
 * retiro ya cargado.
 */
export const DEFAULT_WITHDRAWAL_VALUES: WithdrawalFormInput = {
  productId: 0,
  quantity: Number.NaN,
  receivedByWorkerId: 0,
  observations: '',
}
