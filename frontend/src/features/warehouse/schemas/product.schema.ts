import { z } from 'zod'

/** Espejo de `name` en el contrato (`minLength: 3, maxLength: 200`). */
export const PRODUCT_NAME_MIN_LENGTH = 3
export const PRODUCT_NAME_MAX_LENGTH = 200
/** Espejo de `brand` y `partNumber` (`maxLength: 100`). */
export const PRODUCT_SHORT_TEXT_MAX_LENGTH = 100

/**
 * Una característica del producto. En el contrato `attributes` es un objeto libre
 * clave-valor; el form lo edita como filas para poder agregarlas y quitarlas, y lo
 * convierte a objeto al enviar.
 *
 * Los topes de largo son del frontend, no del contrato (que no acota las claves):
 * el front es más estricto a propósito, para que la tabla del detalle siga siendo
 * legible.
 */
const productAttributeSchema = z.object({
  key: z
    .string()
    .trim()
    .min(1, 'Indica el nombre de la característica')
    .max(100, 'Máximo 100 caracteres'),
  value: z.string().trim().min(1, 'Indica el valor').max(200, 'Máximo 200 caracteres'),
})

/**
 * Form de edición de producto. Espeja `WarehouseProductUpdateRequest`, que NO
 * incluye la unidad de medida: se fija al crear y es inmutable (el modal la
 * muestra como dato, no como campo).
 */
export const productFormSchema = z.object({
  name: z
    .string()
    .trim()
    .min(PRODUCT_NAME_MIN_LENGTH, `El nombre debe tener al menos ${PRODUCT_NAME_MIN_LENGTH} caracteres`)
    .max(PRODUCT_NAME_MAX_LENGTH, `Máximo ${PRODUCT_NAME_MAX_LENGTH} caracteres`),
  categoryId: z
    .number({ message: 'Selecciona una categoría' })
    .int()
    .positive('Selecciona una categoría'),
  brand: z
    .string()
    .trim()
    .max(PRODUCT_SHORT_TEXT_MAX_LENGTH, `Máximo ${PRODUCT_SHORT_TEXT_MAX_LENGTH} caracteres`)
    .optional(),
  partNumber: z
    .string()
    .trim()
    .max(PRODUCT_SHORT_TEXT_MAX_LENGTH, `Máximo ${PRODUCT_SHORT_TEXT_MAX_LENGTH} caracteres`)
    .optional(),
  minStock: z
    .number({ message: 'Indica el stock mínimo' })
    .min(0, 'No puede ser negativo'),
  attributes: z
    .array(productAttributeSchema)
    .superRefine((attributes, ctx) => {
      // Un objeto JSON no admite claves repetidas: la segunda pisaría a la primera
      // en silencio al convertir las filas a `attributes`.
      const seen = new Set<string>()
      attributes.forEach((attribute, index) => {
        const key = attribute.key.trim().toLowerCase()
        if (!key) return
        if (seen.has(key)) {
          ctx.addIssue({
            code: 'custom',
            path: [index, 'key'],
            message: 'Ya usaste esa característica',
          })
        }
        seen.add(key)
      })
    }),
  observations: z.string().trim().optional(),
})

export type ProductFormInput = z.infer<typeof productFormSchema>
