import { z } from 'zod'
import { NO_CONTROL } from '../../../shared/utils/sanitizeText'

/**
 * Validación del motivo de rechazo de una cotización (`status=REJECTED`). Texto libre
 * obligatorio. `.regex(NO_CONTROL)` espeja el `@Pattern` del backend (defensa por capas:
 * la L2 `stripControlChars` ya limpia al tipear/pegar; esto es el backstop de validación).
 * `< >` y demás imprimibles se permiten (la defensa de XSS es escape-on-output de JSX).
 *
 * El `.trim()` colapsa un input de solo-whitespace a `''`, que falla el `.min(1)` →
 * "obligatorio" (no pasa un motivo en blanco al backend).
 */
export const rejectSchema = z.object({
  rejectionReason: z
    .string()
    .trim()
    .min(1, 'El motivo del rechazo es obligatorio.')
    .max(500, 'Máximo 500 caracteres.')
    .regex(NO_CONTROL, 'No se permiten caracteres de control.'),
})

export type RejectFormValues = z.infer<typeof rejectSchema>
