import { z } from 'zod'

const MIN_PASSWORD_LENGTH = 8
const MAX_PASSWORD_BYTES = 72

const TOO_SHORT_MESSAGE = 'Mínimo 8 caracteres'
const TOO_LONG_MESSAGE = 'Es demasiado larga: máximo 72 bytes; las tildes y los emojis cuentan doble o más'
const UNUSABLE_CHARACTER_MESSAGE = 'Tiene un carácter que no se puede usar'

const utf8 = new TextEncoder()

// Igual que el servidor: recorrida por puntos de código, lo que cae en el rango de los surrogates
// está suelto. Con un nulo o un surrogate suelto, BCrypt puede dar por iguales claves distintas.
function hasNulOrLoneSurrogate(password: string): boolean {
  return [...password].some((character) => {
    const codePoint = character.codePointAt(0) ?? 0
    return codePoint === 0 || (codePoint >= 0xd800 && codePoint <= 0xdfff)
  })
}

// La contraseña se cuenta como en el servidor. El máximo, en bytes UTF-8: es lo que procesa BCrypt.
// El mínimo, en unidades UTF-16 (casi todo emoji vale 2), no en puntos de código como el .min de
// zod: si no, una clave que el servidor acepta no se podría escribir acá. El carácter va antes que
// el largo: TextEncoder cuenta 3 bytes por surrogate suelto y Java 1, y el largo avisaría de más.
export function passwordField() {
  return z
    .string()
    .refine((password) => password.length >= MIN_PASSWORD_LENGTH, TOO_SHORT_MESSAGE)
    .refine((password) => !hasNulOrLoneSurrogate(password), UNUSABLE_CHARACTER_MESSAGE)
    .refine((password) => utf8.encode(password).length <= MAX_PASSWORD_BYTES, TOO_LONG_MESSAGE)
}
