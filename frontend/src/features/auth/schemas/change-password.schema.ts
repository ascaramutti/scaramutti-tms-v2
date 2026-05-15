import { z } from 'zod'

// Las constraints de password matchean el contrato OpenAPI
// (ChangePasswordRequest: minLength 8, maxLength 100).
export const changePasswordSchema = z
  .object({
    currentPassword: z
      .string()
      .min(8, 'Mínimo 8 caracteres')
      .max(100, 'Máximo 100 caracteres'),
    newPassword: z
      .string()
      .min(8, 'Mínimo 8 caracteres')
      .max(100, 'Máximo 100 caracteres'),
    confirmPassword: z.string(),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: 'Las contraseñas no coinciden',
    path: ['confirmPassword'],
  })
  .refine((data) => data.newPassword !== data.currentPassword, {
    message: 'La nueva contraseña debe ser diferente a la actual',
    path: ['newPassword'],
  })

export type ChangePasswordFormInput = z.infer<typeof changePasswordSchema>
