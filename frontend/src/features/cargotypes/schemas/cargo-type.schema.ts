import { z } from 'zod'

/** Tope de las columnas del catálogo: NUMERIC(10,2), o sea 8 enteros más 2 decimales. */
const CARGO_TYPE_MAX = 99999999.99

/**
 * Los campos numéricos viajan por el formulario como TEXTO y se convierten acá.
 *
 * No es una vuelta de más, y la causa no es la que parece. Los campos se registraban
 * como número con un `setValueAs` que convertía `''` a `null`, pero react-hook-form
 * le pasa a esa función TAMBIÉN el valor por omisión, tal cual, sin que el usuario
 * toque nada. El valor por omisión de las dimensiones era `null`, la guarda `value
 * === ''` no lo atrapaba, y `Number(null)` es `0`. La firma de la función decía
 * recibir un string y era mentira.
 *
 * Medido instrumentando ese `setValueAs`: se lo llama UNA vez, con `null` y de tipo
 * objeto. Y sin `setValueAs`, el mismo campo intacto manda `null`. Por eso el defecto
 * era POR CAMPO: el tocado pasaba por la función con un string de verdad y salía
 * `null`; el intacto pasaba con el valor por omisión y salía `0`.
 *
 * Con los campos como texto no hay conversión que reciba un valor de otro tipo: lo
 * vacío queda vacío y el schema decide qué significa. Elimina esa clase de error en
 * este formulario.
 */
const numericText = z
  .string()
  .trim()
  .refine((value) => value === '' || DECIMAL_TEXT.test(value), {
    message: 'Escribe un número con hasta 2 decimales.',
  })

/**
 * La forma en que se acepta escribir un valor que la columna `NUMERIC(10,2)` guarda.
 * Es un patrón y no una suma de comprobaciones, y es más estrecho que la columna a
 * propósito: `1e2` vale 100 y se rechaza igual, porque el formulario define cómo se
 * escribe, no solo qué se puede guardar.
 *
 * El schema anterior no controlaba la escala: `z.number()` dejaba salir cualquier
 * cantidad de decimales, y también la notación científica, que un campo numérico
 * admite. Esos valores no llegaban a la base: el servidor los rechaza con 400 por su
 * propia regla de dígitos. Lo que cambia es dónde aparece el error, en el campo y
 * antes de enviar, en vez de volver del servidor sin decir cuál corregir.
 */
const DECIMAL_TEXT = /^\d+(\.\d{1,2})?$/

/**
 * Peso estándar: obligatorio y mayor que cero.
 *
 * Antes admitía el cero, y como además el campo arrancaba en 0 se podía crear un tipo
 * de carga sin escribir nada y quedaba pesando cero. Una carga que pesa cero no
 * existe: lo que ese cero decía en realidad era "no lo cargué".
 */
const standardWeightSchema = numericText
  .refine((value) => value !== '', { message: 'Ingresa el peso estándar (kg).' })
  .refine((value) => value === '' || Number(value) > 0, {
    message: 'El peso estándar debe ser mayor a 0.',
  })
  .refine((value) => value === '' || Number(value) <= CARGO_TYPE_MAX, {
    message: 'Valor demasiado grande.',
  })
  .transform(Number)

/**
 * Dimensión estándar: opcional, y si se carga, mayor que cero.
 *
 * Vacía significa "no la sé" y viaja como `null`. El cero se rechaza: una medida en
 * cero no existe, y era otra forma de escribir lo mismo que el campo vacío ya dice.
 * Es la misma regla que aplican las medidas de un servicio, y ahora también el
 * servidor.
 */
const standardDimensionSchema = numericText
  .refine((value) => value === '' || Number(value) > 0, {
    message: 'La medida debe ser mayor a 0.',
  })
  .refine((value) => value === '' || Number(value) <= CARGO_TYPE_MAX, {
    message: 'Valor demasiado grande.',
  })
  .transform((value) => (value === '' ? null : Number(value)))

/**
 * Validaciones de creación de tipo de carga al vuelo. Espejan `CargoTypeRequest`:
 * `name` 1-100 y `standardWeight` requeridos. El backend guarda `name` en MAYÚSCULAS
 * y devuelve 409 (`CGT-001`) si ya existe.
 *
 * El modal es COMPARTIDO: lo usan el asistente de cotizaciones y el alta de servicios,
 * y estas reglas valen para los dos por igual.
 */
export const createCargoTypeSchema = z.object({
  name: z.string().trim().min(1, 'El nombre es obligatorio.').max(100, 'Máximo 100 caracteres.'),
  description: z.string().trim().optional().or(z.literal('')),
  standardWeight: standardWeightSchema,
  standardLength: standardDimensionSchema,
  standardWidth: standardDimensionSchema,
  standardHeight: standardDimensionSchema,
})

/** Lo que el formulario guarda mientras se escribe (los números, como texto). */
export type CreateCargoTypeInput = z.input<typeof createCargoTypeSchema>

/** Lo ya validado y convertido, que es lo que se manda. */
export type CreateCargoTypeValues = z.output<typeof createCargoTypeSchema>
