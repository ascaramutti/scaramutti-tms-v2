import { z } from 'zod'

/**
 * Validaciones de creación de tipo de carga al vuelo (desde el wizard). Matchean
 * `CargoTypeRequest`: `name` 1-100, `standardWeight` >= 0 (ambos requeridos). El
 * backend guarda `name` en MAYÚSCULAS y devuelve 409 (CGT-001) si ya existe.
 */
export const createCargoTypeSchema = z.object({
  name: z.string().trim().min(1, 'El nombre es obligatorio.').max(100, 'Máximo 100 caracteres.'),
  description: z.string().trim().optional().or(z.literal('')),
  standardWeight: z
    .number({ message: 'Ingresa el peso estándar (kg).' })
    .min(0, 'No puede ser negativo.')
    .max(99999999.99, 'Valor demasiado grande.'),
  standardLength: z.number().min(0, 'No puede ser negativo.').max(99999999.99, 'Valor demasiado grande.').nullable(),
  standardWidth: z.number().min(0, 'No puede ser negativo.').max(99999999.99, 'Valor demasiado grande.').nullable(),
  standardHeight: z.number().min(0, 'No puede ser negativo.').max(99999999.99, 'Valor demasiado grande.').nullable(),
})

export type CreateCargoTypeInput = z.infer<typeof createCargoTypeSchema>
