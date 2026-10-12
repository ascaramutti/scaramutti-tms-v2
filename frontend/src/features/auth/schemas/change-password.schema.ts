import { z } from 'zod'

const ONLY_SPACES_MESSAGE = 'No puede tener solo espacios'

// El blanco del servidor (isBlank de Java) no es el de JavaScript: no cuenta estos cuatro, que
// JavaScript sí, y cuenta los separadores de control, que JavaScript no. Con el de JavaScript, quien
// ya tiene una clave de espacios duros no podría cambiarla.
const NOT_BLANK_ON_THE_SERVER = '\u00A0\u2007\u202F\uFEFF'

function isServerWhitespace(character: string): boolean {
  return (
    (/\s/.test(character) && !NOT_BLANK_ON_THE_SERVER.includes(character)) ||
    (character >= '\u001C' && character <= '\u001F')
  )
}

// El servidor rechaza una clave en blanco: se avisa antes de enviarla, con un texto propio.
function notOnlySpaces(password: string): boolean {
  return ![...password].every(isServerWhitespace)
}

// Las constraints de password matchean el contrato OpenAPI (ChangePasswordRequest: minLength 8,
// maxLength 100), más la clave en blanco que el servidor rechaza y el contrato no declara.
export const changePasswordSchema = z
  .object({
    currentPassword: z
      .string()
      .min(8, 'Mínimo 8 caracteres')
      .max(100, 'Máximo 100 caracteres')
      .refine(notOnlySpaces, ONLY_SPACES_MESSAGE),
    newPassword: z
      .string()
      .min(8, 'Mínimo 8 caracteres')
      .max(100, 'Máximo 100 caracteres')
      .refine(notOnlySpaces, ONLY_SPACES_MESSAGE),
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
