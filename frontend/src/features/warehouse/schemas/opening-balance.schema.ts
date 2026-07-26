import { z } from 'zod'

/**
 * Tope de la cantidad del corte inicial: 4 dígitos (< 10000). No es del contrato
 * (que solo acota `minimum: 0`) sino una cota del frontend, alineada con la de las
 * entradas y los retiros para que la misma cantidad no sea válida en una pantalla
 * e inválida en otra.
 */
export const OPENING_BALANCE_MAX_QUANTITY = 9999
/**
 * Tope del frontend, no del contrato (que tipa `observations` sin `maxLength`).
 * Espejo del de entradas y retiros.
 */
export const OPENING_BALANCE_OBSERVATIONS_MAX_LENGTH = 500

/**
 * Form del corte inicial: con cuánto arranca un producto. Espeja
 * `WarehouseOpeningBalanceRequest`.
 *
 * La apertura es el primer movimiento del kardex y es INMUTABLE: no hay PUT ni
 * DELETE en el contrato, así que este archivo no define (ni debe definir) un
 * schema de edición.
 *
 * Las reglas que el frontend NO puede validar y quedan en manos del backend: que
 * el producto no tenga ya su apertura (409 WH-009) y que no tenga movimientos
 * previos (409 WH-011). Ambas dependen del estado del kardex al momento de enviar.
 */
export const openingBalanceFormSchema = z.object({
  productId: z
    .number({ message: 'Selecciona el producto' })
    .int()
    .positive('Selecciona el producto'),
  // `min(0)` y NO `.positive()`: el contrato admite el 0 a propósito. Registrar un
  // producto en 0 deja constancia de que se contó y no había existencias, que es
  // información distinta de no haberlo cargado nunca.
  quantity: z
    .number({ message: 'Indica la cantidad' })
    .min(0, 'La cantidad no puede ser negativa')
    .max(OPENING_BALANCE_MAX_QUANTITY, `Máximo ${OPENING_BALANCE_MAX_QUANTITY}`),
  observations: z
    .string()
    .trim()
    .max(
      OPENING_BALANCE_OBSERVATIONS_MAX_LENGTH,
      `Máximo ${OPENING_BALANCE_OBSERVATIONS_MAX_LENGTH} caracteres`,
    )
    .optional(),
})

export type OpeningBalanceFormInput = z.infer<typeof openingBalanceFormSchema>

/**
 * Valores iniciales del form. El producto en 0 dispara su "selecciona…"; la
 * cantidad arranca sin valor (`NaN` es lo que produce un input numérico vacío con
 * `valueAsNumber`) y NO en 0, que acá es un valor legítimo y debe ser una decisión
 * explícita del operador, no un default que se cuele.
 */
export const DEFAULT_OPENING_BALANCE_VALUES: OpeningBalanceFormInput = {
  productId: 0,
  quantity: Number.NaN,
  observations: '',
}
