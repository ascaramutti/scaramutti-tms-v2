import { z } from 'zod'

/** Espejo de `name` en el contrato de proveedores (`minLength: 3, maxLength: 200`). */
export const SUPPLIER_NAME_MIN_LENGTH = 3
export const SUPPLIER_NAME_MAX_LENGTH = 200
/** Espejo de `contactName` (`maxLength: 100`). */
export const SUPPLIER_CONTACT_MAX_LENGTH = 100

/**
 * Alta de proveedor al vuelo desde el form de la entrada. Espeja
 * `WarehouseSupplierRequest`: acá el RUC es OPCIONAL (a diferencia del cliente
 * de cotizaciones, que lo exige), porque hay proveedores chicos sin RUC a mano
 * y bloquear el alta trabaría la carga de la factura.
 */
export const supplierFormSchema = z.object({
  name: z
    .string()
    .trim()
    .min(SUPPLIER_NAME_MIN_LENGTH, `El nombre debe tener al menos ${SUPPLIER_NAME_MIN_LENGTH} caracteres`)
    .max(SUPPLIER_NAME_MAX_LENGTH, `Máximo ${SUPPLIER_NAME_MAX_LENGTH} caracteres`),
  ruc: z
    .string()
    .trim()
    .regex(/^\d{11}$/, 'El RUC debe tener 11 dígitos')
    .optional()
    .or(z.literal('')),
  phone: z
    .string()
    .trim()
    .regex(/^\d{9}$/, 'El teléfono debe tener 9 dígitos')
    .optional()
    .or(z.literal('')),
  contactName: z
    .string()
    .trim()
    .max(SUPPLIER_CONTACT_MAX_LENGTH, `Máximo ${SUPPLIER_CONTACT_MAX_LENGTH} caracteres`)
    .optional(),
})

export type SupplierFormInput = z.infer<typeof supplierFormSchema>
