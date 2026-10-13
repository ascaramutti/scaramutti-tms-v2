import { z } from 'zod'
import { passwordField } from './password'

// El usuario sigue a LoginRequest en el contrato OpenAPI y la contraseña cuenta como el servidor
// (ver password.ts). Falta la clave hecha solo de espacios: el servidor la rechaza y acá no se avisa.
export const loginSchema = z.object({
  username: z
    .string()
    .min(3, 'Mínimo 3 caracteres')
    .max(50, 'Máximo 50 caracteres')
    .regex(/^[a-zA-Z0-9._-]+$/, 'Solo letras, números, puntos, guiones y guión bajo'),
  password: passwordField(),
})

export type LoginFormInput = z.infer<typeof loginSchema>
