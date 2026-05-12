import { z } from 'zod'

// Las constraints matchean exactamente las del backend (LoginRequest en el
// contrato OpenAPI). Si el backend cambia, hay que actualizarlas aca tambien.
export const loginSchema = z.object({
  username: z
    .string()
    .min(3, 'Mínimo 3 caracteres')
    .max(50, 'Máximo 50 caracteres')
    .regex(/^[a-zA-Z0-9._-]+$/, 'Solo letras, números, puntos, guiones y guión bajo'),
  password: z
    .string()
    .min(8, 'Mínimo 8 caracteres')
    .max(100, 'Máximo 100 caracteres'),
})

export type LoginFormInput = z.infer<typeof loginSchema>
