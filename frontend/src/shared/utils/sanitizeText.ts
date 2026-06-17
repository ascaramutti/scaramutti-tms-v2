/**
 * Saneo de texto libre: caracteres de control. Espeja el `@Pattern("^[\P{Cntrl}\t\n\r]*$")`
 * del backend (Java `\p{Cntrl}` = `[\x00-\x1F\x7F]`): se prohíben TODOS los control-chars salvo
 * tab (`\t`), salto de línea (`\n`) y retorno de carro (`\r`), que son texto legítimo.
 *
 * El set se define UNA vez (mismos code points para sanitize y zod) para no divergir:
 * - `CONTROL_CHARS_GLOBAL` (con flag `g`) → `stripControlChars` (L2: limpia al escribir/pegar).
 * - `NO_CONTROL` (anclado) → regla zod (L3: backstop de validación; espeja al backend).
 *
 * Imprimibles como `< >` NO son control-chars → se permiten (RN-04). La defensa de XSS es
 * escape-on-output de JSX, nunca bloquear caracteres ni `dangerouslySetInnerHTML`.
 */
// Los control-chars en el patrón son intencionales (es justo lo que se sanea, espejando el
// `@Pattern` del backend) → la regla no-control-regex no aplica acá.
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS_GLOBAL = /[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g

/** Elimina en silencio los caracteres de control (conserva `\t`, `\n`, `\r`). */
export function stripControlChars(value: string): string {
  return value.replace(CONTROL_CHARS_GLOBAL, '')
}

/** Regla zod: el string NO contiene caracteres de control (salvo `\t`, `\n`, `\r`). */
// eslint-disable-next-line no-control-regex -- control-chars intencionales (espejan el backend)
export const NO_CONTROL = /^[^\x00-\x08\x0B\x0C\x0E-\x1F\x7F]*$/
