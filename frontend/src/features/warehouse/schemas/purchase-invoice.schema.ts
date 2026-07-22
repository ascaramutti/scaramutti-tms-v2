import { z } from 'zod'
import { todayIsoDate } from '../../../shared/utils/formatters'

/** Espejo de `invoiceNumber` y `guideNumber` en el contrato (`maxLength: 50`). */
export const INVOICE_NUMBER_MAX_LENGTH = 50
export const GUIDE_NUMBER_MAX_LENGTH = 50
/** Espejo de `items.maxItems` en el contrato. */
export const INVOICE_MAX_ITEMS = 200
/**
 * Tope de la cantidad por ítem: 4 dígitos (< 10000). No es del contrato sino una
 * cota razonable de una compra por factura; evita cargas erróneas de más dígitos.
 */
export const INVOICE_ITEM_MAX_QUANTITY = 9999
/**
 * Tope del frontend, no del contrato (que tipa `observations` sin `maxLength`).
 * El front es más estricto a propósito: un texto desmedido no aporta y el backend
 * lo cortaría en la columna. Si hiciera falta más, se sube acá.
 */
export const INVOICE_OBSERVATIONS_MAX_LENGTH = 500

/**
 * Un ítem de la factura. `unitPrice` admite 0 (bonificación: el contrato lo
 * permite explícitamente), pero `quantity` no: el contrato la acota con
 * `exclusiveMinimum: 0`.
 */
const invoiceItemSchema = z.object({
  productId: z
    .number({ message: 'Selecciona un producto' })
    .int()
    .positive('Selecciona un producto'),
  quantity: z
    .number({ message: 'Indica la cantidad' })
    .positive('La cantidad debe ser mayor a 0')
    .max(INVOICE_ITEM_MAX_QUANTITY, `Máximo ${INVOICE_ITEM_MAX_QUANTITY}`),
  unitPrice: z
    .number({ message: 'Indica el precio unitario' })
    .min(0, 'No puede ser negativo'),
})

/**
 * Form de registro de una entrada (factura de compra). Espeja
 * `WarehousePurchaseInvoiceRequest`.
 *
 * La fecha no puede ser futura: una factura de compra no se emite a futuro. Es
 * una regla SOLO del frontend por ahora (el backend acepta cualquier fecha);
 * endurecerlo del lado del servidor quedó como pendiente.
 *
 * Un mismo producto repetido en dos ítems NO se rechaza acá: el backend lo
 * acepta (las dos líneas suman al stock) y hay compras que lo justifican, como
 * un despacho parcial a distinto precio. La tabla de ítems lo avisa sin bloquear.
 */
export const purchaseInvoiceFormSchema = z.object({
  supplierId: z
    .number({ message: 'Selecciona el proveedor' })
    .int()
    .positive('Selecciona el proveedor'),
  invoiceNumber: z
    .string()
    .trim()
    .min(1, 'Indica el número de factura')
    .max(INVOICE_NUMBER_MAX_LENGTH, `Máximo ${INVOICE_NUMBER_MAX_LENGTH} caracteres`),
  invoiceDate: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'Indica la fecha de la factura')
    .refine((value) => value <= todayIsoDate(), 'La fecha no puede ser futura'),
  guideNumber: z
    .string()
    .trim()
    .max(GUIDE_NUMBER_MAX_LENGTH, `Máximo ${GUIDE_NUMBER_MAX_LENGTH} caracteres`)
    .optional(),
  currencyId: z
    .number({ message: 'Selecciona la moneda' })
    .int()
    .positive('Selecciona la moneda'),
  observations: z
    .string()
    .trim()
    .max(
      INVOICE_OBSERVATIONS_MAX_LENGTH,
      `Máximo ${INVOICE_OBSERVATIONS_MAX_LENGTH} caracteres`,
    )
    .optional(),
  items: z
    .array(invoiceItemSchema)
    .min(1, 'Agrega al menos un ítem')
    .max(INVOICE_MAX_ITEMS, `Máximo ${INVOICE_MAX_ITEMS} ítems por factura`),
})

export type PurchaseInvoiceFormInput = z.infer<typeof purchaseInvoiceFormSchema>
export type PurchaseInvoiceItemInput = z.infer<typeof invoiceItemSchema>

/**
 * Espejo de `WarehousePurchaseInvoiceUpdateRequest.reason` en el contrato
 * (`minLength: 10, maxLength: 500`, requerido). La edición exige una
 * justificación que va a `almacen.audit_logs` con el diff por campo (RN-WH4).
 * Constantes propias, no las de la anulación: son dos reglas de dominio distintas
 * aunque hoy coincidan los números.
 */
export const EDIT_REASON_MIN_LENGTH = 10
export const EDIT_REASON_MAX_LENGTH = 500

/**
 * Form de EDICIÓN de una entrada. Extiende el de alta con el motivo obligatorio.
 * El `supplierId` sigue en el schema (prefilled) pero NO viaja al PUT: el
 * proveedor es inmutable y el contrato no lo acepta, igual que la unidad de medida
 * en la edición de un producto. Validar un campo que no se envía nunca bloquea.
 */
export const purchaseInvoiceEditFormSchema = purchaseInvoiceFormSchema.extend({
  reason: z
    .string()
    .trim()
    .min(EDIT_REASON_MIN_LENGTH, `El motivo debe tener al menos ${EDIT_REASON_MIN_LENGTH} caracteres`)
    .max(EDIT_REASON_MAX_LENGTH, `Máximo ${EDIT_REASON_MAX_LENGTH} caracteres`),
})

export type PurchaseInvoiceEditFormInput = z.infer<typeof purchaseInvoiceEditFormSchema>

/**
 * Fila del form ANTES de validar: los números pueden ser `NaN` (lo que produce un
 * input numérico vacío con `valueAsNumber`). Es un tipo aparte del validado a
 * propósito: `PurchaseInvoiceItemInput` afirma que el ítem pasó el schema.
 */
export type PurchaseInvoiceItemDraft = {
  productId: number
  quantity: number
  unitPrice: number
}

/**
 * Fila vacía. El producto en 0 dispara el "selecciona un producto" del schema, y
 * la cantidad y el precio arrancan sin valor (`NaN` es lo que produce un input
 * numérico vacío con `valueAsNumber`): un 0 de arranque haría pasar como
 * bonificación una fila a la que nadie le puso precio.
 */
export const EMPTY_INVOICE_ITEM: PurchaseInvoiceItemDraft = {
  productId: 0,
  quantity: Number.NaN,
  unitPrice: Number.NaN,
}
