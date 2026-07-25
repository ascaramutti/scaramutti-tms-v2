import { z } from 'zod'

/**
 * Espejo de `WarehouseCancelRequest.reason` en el contrato (`minLength: 10,
 * maxLength: 500`, requerido). El motivo va a `cancel_reason` y al `audit_logs`
 * del backend (RN-WH3), así que exigir una explicación mínima no es capricho de
 * la UI: es la regla del dominio.
 */
export const CANCEL_REASON_MIN_LENGTH = 10
export const CANCEL_REASON_MAX_LENGTH = 500

export const warehouseCancelSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(CANCEL_REASON_MIN_LENGTH, `El motivo debe tener al menos ${CANCEL_REASON_MIN_LENGTH} caracteres`)
    .max(CANCEL_REASON_MAX_LENGTH, `Máximo ${CANCEL_REASON_MAX_LENGTH} caracteres`),
})

export type WarehouseCancelInput = z.infer<typeof warehouseCancelSchema>
