import { z } from 'zod'
import { QUOTATION_TYPE_VALUES } from '../schemas/quotation-filters.schema'

/** Fecha de hoy en formato `YYYY-MM-DD` (horario local), para validar fechas no pasadas. */
function todayISO(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/**
 * Kinds de servicio que el Step 2 permite hoy como ítem ROOT. El Servicio Integral
 * (jerárquico, con componentes hijos) se implementa en un PR posterior, por eso se
 * excluye del select y del schema por ahora.
 */
export const ITEM_SERVICE_KINDS = ['SERVICIO', 'ALQUILER', 'COMPLEMENTARIO'] as const
export type ItemServiceKind = (typeof ITEM_SERVICE_KINDS)[number]

/** Campos del Step 1 (Información General). Se extraen para componer `step1Schema`
 * y `wizardSchema` sin duplicar. */
const step1Fields = {
  quotationType: z.enum(QUOTATION_TYPE_VALUES),
  clientId: z.number({ message: 'Selecciona un cliente.' }).int().positive('Selecciona un cliente.'),
  contactName: z.string().trim().min(1, 'El contacto es obligatorio.').max(200, 'Máximo 200 caracteres.'),
  contactPhone: z
    .string()
    .trim()
    .regex(/^\d{9}$/, 'El teléfono debe tener 9 dígitos.')
    .optional()
    .or(z.literal('')),
  origin: z.string().trim().max(255, 'Máximo 255 caracteres.').optional().or(z.literal('')),
  destination: z.string().trim().max(255, 'Máximo 255 caracteres.').optional().or(z.literal('')),
  currencyId: z.number().int().positive('Selecciona una moneda.'),
  paymentTermId: z.number().int().positive().nullable(),
  tentativeServiceDate: z
    .string()
    .optional()
    .or(z.literal(''))
    .refine((value) => !value || value >= todayISO(), 'No se permiten fechas pasadas.'),
  validityDays: z
    .number({ message: 'Ingresa la validez en días.' })
    .int('Debe ser un número entero.')
    .min(1, 'Mínimo 1 día.')
    .max(365, 'Máximo 365 días.'),
}

/** origin/destination requeridos solo si TRANSPORTE (regla del backend). */
function refineRoute(
  data: { quotationType: string; origin?: string; destination?: string },
  ctx: z.RefinementCtx,
) {
  if (data.quotationType === 'TRANSPORTE') {
    if (!data.origin?.trim()) {
      ctx.addIssue({ code: 'custom', path: ['origin'], message: 'El origen es obligatorio para transporte.' })
    }
    if (!data.destination?.trim()) {
      ctx.addIssue({
        code: 'custom',
        path: ['destination'],
        message: 'El destino es obligatorio para transporte.',
      })
    }
  }
}

export const step1Schema = z.object(step1Fields).superRefine(refineRoute)

/**
 * Schema de un ítem del Step 2 (ítem root; sin Servicio Integral jerárquico aún).
 * `serviceKind` es auxiliar (deriva del tipo elegido): valida/condiciona la UI según
 * el tipo, pero NO se envía al backend (que solo recibe `serviceTypeId`).
 */
export const itemSchema = z
  .object({
    serviceTypeId: z
      .number({ message: 'Selecciona un tipo de servicio.' })
      .int()
      .positive('Selecciona un tipo de servicio.'),
    serviceKind: z.enum(ITEM_SERVICE_KINDS),
    cargoTypeId: z.number().int().positive().nullable(),
    /** Auxiliar: nombre del tipo de carga elegido, para el chip del combobox (no se envía). */
    cargoTypeName: z.string().optional(),
    // Backend: peso @Digits(integer=8) → hasta 99.999.999,99 kg; dimensiones @Digits(integer=6).
    weightKg: z.number().min(0).max(99999999.99).nullable(),
    lengthMeters: z.number().min(0).max(999999.99).nullable(),
    widthMeters: z.number().min(0).max(999999.99).nullable(),
    heightMeters: z.number().min(0).max(999999.99).nullable(),
    quantity: z.number({ message: 'Ingresa la cantidad.' }).int('Debe ser un número entero.').min(1, 'Mínimo 1.'),
    unitPrice: z
      .number({ message: 'Ingresa el precio unitario.' })
      .positive('El precio debe ser mayor a 0.')
      .max(9999999999.99, 'Precio demasiado grande.'),
    observations: z.string().trim().max(2000, 'Máximo 2000 caracteres.').optional().or(z.literal('')),
  })
  .superRefine((item, ctx) => {
    // SERVICIO (transporte): tipo de carga y peso (>0) obligatorios.
    if (item.serviceKind === 'SERVICIO') {
      if (!item.cargoTypeId) {
        ctx.addIssue({
          code: 'custom',
          path: ['cargoTypeId'],
          message: 'El tipo de carga es obligatorio para servicios de transporte.',
        })
      }
      if (item.weightKg == null || item.weightKg <= 0) {
        ctx.addIssue({ code: 'custom', path: ['weightKg'], message: 'El peso debe ser mayor a 0.' })
      }
    }
  })

export type ItemInput = z.infer<typeof itemSchema>

/**
 * Schema del wizard completo (Step 1 + Step 2). El `zodResolver` usa este; cada step
 * valida solo sus campos vía `STEP_FIELDS` + `form.trigger`.
 */
export const wizardSchema = z
  .object({
    ...step1Fields,
    items: z.array(itemSchema).min(1, 'Agrega al menos un ítem a la cotización.'),
  })
  .superRefine(refineRoute)

export type WizardFormInput = z.infer<typeof wizardSchema>

/** Valores por defecto del wizard. `validityDays` se sobreescribe con el config del
 * backend al montar (ver `CotizacionWizardPage`). */
export const WIZARD_DEFAULTS: WizardFormInput = {
  quotationType: 'TRANSPORTE',
  clientId: 0,
  contactName: '',
  contactPhone: '',
  origin: '',
  destination: '',
  currencyId: 0,
  paymentTermId: null,
  tentativeServiceDate: '',
  validityDays: 15,
  items: [],
}

/** Defaults de un ítem nuevo. El `serviceKind` lo ajusta el Step 2 según el tipo de
 * cotización al agregar (ALQUILER → 'ALQUILER', TRANSPORTE → 'SERVICIO'). */
export const ITEM_DEFAULTS: ItemInput = {
  serviceTypeId: 0,
  serviceKind: 'SERVICIO',
  cargoTypeId: null,
  cargoTypeName: '',
  weightKg: null,
  lengthMeters: null,
  widthMeters: null,
  heightMeters: null,
  quantity: 1,
  unitPrice: 0,
  observations: '',
}

/** Campos a validar por step (para `trigger()` en "Siguiente" / al cambiar de paso). */
export const STEP_FIELDS: Record<number, readonly (keyof WizardFormInput)[]> = {
  0: [
    'quotationType',
    'clientId',
    'contactName',
    'contactPhone',
    'origin',
    'destination',
    'currencyId',
    'paymentTermId',
    'tentativeServiceDate',
    'validityDays',
  ],
  1: ['items'],
}
