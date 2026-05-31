import { z } from 'zod'

/**
 * Validaciones del form de creación de cliente al vuelo. Matchean `ClientRequest`
 * del contrato: `name` 1-200, `ruc` 11 dígitos, `phone` 9 dígitos (opcional),
 * `contactName` máx 100 (opcional). Si el backend cambia, actualizar acá.
 */
export const createClientSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, 'La razón social es obligatoria.')
    .max(200, 'Máximo 200 caracteres.'),
  ruc: z
    .string()
    .trim()
    .regex(/^\d{11}$/, 'El RUC debe tener 11 dígitos.'),
  phone: z
    .string()
    .trim()
    .regex(/^\d{9}$/, 'El teléfono debe tener 9 dígitos.')
    .optional()
    .or(z.literal('')),
  contactName: z.string().trim().max(100, 'Máximo 100 caracteres.').optional().or(z.literal('')),
})

export type CreateClientInput = z.infer<typeof createClientSchema>
