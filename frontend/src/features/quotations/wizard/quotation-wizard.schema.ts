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
 * Kinds de servicio que el Step 2 permite como ítem ROOT (incluye INTEGRAL: el Servicio
 * Integral es un ítem padre con componentes hijos — ver `childItemSchema`).
 */
export const ITEM_SERVICE_KINDS = ['SERVICIO', 'ALQUILER', 'COMPLEMENTARIO', 'INTEGRAL'] as const
export type ItemServiceKind = (typeof ITEM_SERVICE_KINDS)[number]

/**
 * Kinds permitidos para un COMPONENTE (hijo) del Servicio Integral: transporte (SERVICIO)
 * y complementarios. No se permite anidar ALQUILER ni otro INTEGRAL.
 */
export const CHILD_SERVICE_KINDS = ['SERVICIO', 'COMPLEMENTARIO'] as const
export type ChildServiceKind = (typeof CHILD_SERVICE_KINDS)[number]

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
 * Schema de un COMPONENTE (hijo) del Servicio Integral. Igual que un ítem de transporte
 * o complementario, pero su precio es de REFERENCIA interna (`internalReferencePrice`,
 * opcional): no se cobra al cliente ni suma al total de la cotización. El backend lo
 * recibe como ítem con `parentItemNumber` y `unitPrice = 0` (el aplanado se hace al
 * enviar, en un PR posterior).
 */
export const childItemSchema = z
  .object({
    serviceTypeId: z
      .number({ message: 'Selecciona un tipo de servicio.' })
      .int()
      .positive('Selecciona un tipo de servicio.'),
    serviceKind: z.enum(CHILD_SERVICE_KINDS),
    cargoTypeId: z.number().int().positive().nullable(),
    cargoTypeName: z.string().optional(),
    weightKg: z.number().min(0).max(99999999.99).nullable(),
    lengthMeters: z.number().min(0).max(999999.99).nullable(),
    widthMeters: z.number().min(0).max(999999.99).nullable(),
    heightMeters: z.number().min(0).max(999999.99).nullable(),
    quantity: z.number({ message: 'Ingresa la cantidad.' }).int('Debe ser un número entero.').min(1, 'Mínimo 1.'),
    // Referencia interna opcional (desglose del paquete); no se cobra al cliente.
    internalReferencePrice: z.number().min(0).max(9999999999.99, 'Precio demasiado grande.').nullable(),
    observations: z.string().trim().max(2000, 'Máximo 2000 caracteres.').optional().or(z.literal('')),
  })
  .superRefine((child, ctx) => {
    // Componente de transporte (SERVICIO): tipo de carga y peso (>0) obligatorios.
    if (child.serviceKind === 'SERVICIO') {
      if (!child.cargoTypeId) {
        ctx.addIssue({
          code: 'custom',
          path: ['cargoTypeId'],
          message: 'El tipo de carga es obligatorio para servicios de transporte.',
        })
      }
      if (child.weightKg == null || child.weightKg <= 0) {
        ctx.addIssue({ code: 'custom', path: ['weightKg'], message: 'El peso debe ser mayor a 0.' })
      }
    }
  })

export type ChildItemInput = z.infer<typeof childItemSchema>

/**
 * Schema de un ítem del Step 2. `serviceKind` es auxiliar (deriva del tipo elegido):
 * valida/condiciona la UI según el tipo, pero NO se envía al backend (que solo recibe
 * `serviceTypeId`). Si el ítem es INTEGRAL lleva `components` (sus hijos jerárquicos).
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
    // Componentes hijos: solo se usan cuando el ítem es INTEGRAL (ver superRefine).
    // Siempre presente (los defaults lo inicializan en []), para no divergir input/output.
    components: z.array(childItemSchema),
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
    // INTEGRAL: paquete jerárquico. Mínimo 2 componentes, con al menos uno de transporte
    // (SERVICIO) y uno complementario. El `unitPrice` del padre es el precio al cliente.
    if (item.serviceKind === 'INTEGRAL') {
      const components = item.components ?? []
      if (components.length < 2) {
        ctx.addIssue({
          code: 'custom',
          path: ['components'],
          message: 'El Servicio Integral requiere mínimo 2 componentes.',
        })
      } else {
        if (!components.some((component) => component.serviceKind === 'SERVICIO')) {
          ctx.addIssue({
            code: 'custom',
            path: ['components'],
            message: 'Agrega al menos un componente de transporte.',
          })
        }
        if (!components.some((component) => component.serviceKind === 'COMPLEMENTARIO')) {
          ctx.addIssue({
            code: 'custom',
            path: ['components'],
            message: 'Agrega al menos un componente complementario.',
          })
        }
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
  components: [],
}

/** Defaults de un componente nuevo del Integral. Arranca COMPLEMENTARIO (no pide carga)
 * hasta que se elige el tipo; ahí el `serviceKind` se ajusta al kind real. */
export const CHILD_DEFAULTS: ChildItemInput = {
  serviceTypeId: 0,
  serviceKind: 'COMPLEMENTARIO',
  cargoTypeId: null,
  cargoTypeName: '',
  weightKg: null,
  lengthMeters: null,
  widthMeters: null,
  heightMeters: null,
  quantity: 1,
  internalReferencePrice: null,
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
