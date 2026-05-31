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
 * Schema del Step 1 (Información General) del wizard. Las constraints matchean
 * `QuotationRequest` del contrato: `contactName` 1-200, `contactPhone` 9 dígitos,
 * `origin`/`destination` máx 255 (requeridos si TRANSPORTE), `validityDays` 1-365.
 *
 * El schema completo del wizard se irá extendiendo en los PRs siguientes (items,
 * standby). En este PR solo se valida el Step 1.
 */
export const step1Schema = z
  .object({
    quotationType: z.enum(QUOTATION_TYPE_VALUES),
    clientId: z
      .number({ message: 'Seleccioná un cliente.' })
      .int()
      .positive('Seleccioná un cliente.'),
    contactName: z
      .string()
      .trim()
      .min(1, 'El contacto es obligatorio.')
      .max(200, 'Máximo 200 caracteres.'),
    contactPhone: z
      .string()
      .trim()
      .regex(/^\d{9}$/, 'El teléfono debe tener 9 dígitos.')
      .optional()
      .or(z.literal('')),
    origin: z.string().trim().max(255, 'Máximo 255 caracteres.').optional().or(z.literal('')),
    destination: z.string().trim().max(255, 'Máximo 255 caracteres.').optional().or(z.literal('')),
    currencyId: z.number().int().positive('Seleccioná una moneda.'),
    paymentTermId: z.number().int().positive().nullable(),
    tentativeServiceDate: z
      .string()
      .optional()
      .or(z.literal(''))
      .refine((value) => !value || value >= todayISO(), 'No se permiten fechas pasadas.'),
    validityDays: z
      .number({ message: 'Ingresá la validez en días.' })
      .int('Debe ser un número entero.')
      .min(1, 'Mínimo 1 día.')
      .max(365, 'Máximo 365 días.'),
  })
  // origin/destination requeridos solo si TRANSPORTE (regla del backend).
  .superRefine((data, ctx) => {
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
  })

export type WizardFormInput = z.infer<typeof step1Schema>

/** Valores por defecto del wizard. `validityDays` se sobreescribe con el config
 * del backend al montar (ver `CotizacionWizardPage`). */
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
}

/** Campos a validar por step (para `trigger()` en "Siguiente"). Los steps 2-3
 * se agregan en los PRs siguientes. */
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
}
