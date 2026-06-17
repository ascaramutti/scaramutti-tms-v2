import type {
  ChildItemInput,
  ChildServiceKind,
  ItemInput,
  ItemServiceKind,
  StandbyInput,
  WizardFormInput,
} from './quotation-wizard.schema'
import type {
  QuotationItemResponse,
  QuotationResponse,
  QuotationStandbyCostResponse,
} from '../../../api'

/** Texto opcional del response → form: `null`/`undefined` → `''` (el form usa string vacío, no null). */
function textOrEmpty(value: string | null | undefined): string {
  return value ?? ''
}

/**
 * Stand-by del response → form. Descarta el `id` (el form solo usa `pricePerDay` + `includesIgv`;
 * el backend reasigna ids en el replace de ítems del PUT). Ausente → `null` (no `undefined`).
 */
function standbyResponseToForm(standby: QuotationStandbyCostResponse | undefined): StandbyInput | null {
  if (!standby) return null
  return { pricePerDay: standby.pricePerDay, includesIgv: standby.includesIgv }
}

/**
 * Hijo del Servicio Integral (response) → componente del form. Inverso de `childToRequest`:
 * el hijo no lleva `unitPrice` (usa `internalReferencePrice`). El `serviceKind` se deriva del
 * `kind` embebido en el response (no hace falta cruzar con el catálogo).
 */
function childResponseToForm(child: QuotationItemResponse): ChildItemInput {
  return {
    serviceTypeId: child.serviceType.id,
    serviceKind: child.serviceType.kind as ChildServiceKind,
    cargoTypeId: child.cargoType?.id ?? null,
    cargoTypeName: child.cargoType?.name ?? '',
    weightKg: child.weightKg ?? null,
    lengthMeters: child.lengthMeters ?? null,
    widthMeters: child.widthMeters ?? null,
    heightMeters: child.heightMeters ?? null,
    quantity: child.quantity,
    // Un 0 del backend se preserva acá, pero quotationFormToRequest lo colapsa a null al guardar
    // (0 ≡ "sin precio referencial"): asimetría intencional, consistente con la semántica del create.
    internalReferencePrice: child.internalReferencePrice ?? null,
    observations: textOrEmpty(child.observations),
    standby: standbyResponseToForm(child.standby),
  }
}

/**
 * Ítem root (response) → ítem del form. Inverso de la rama root de `quotationFormToRequest`:
 * un Integral mapea sus `children[]` a `components[]` (y no lleva carga/medidas/stand-by propios);
 * un root facturable mapea su carga/medidas/precio/stand-by directamente.
 */
function itemResponseToForm(item: QuotationItemResponse): ItemInput {
  const serviceKind = item.serviceType.kind as ItemServiceKind

  if (serviceKind === 'INTEGRAL') {
    return {
      serviceTypeId: item.serviceType.id,
      serviceKind: 'INTEGRAL',
      cargoTypeId: null,
      cargoTypeName: '',
      weightKg: null,
      lengthMeters: null,
      widthMeters: null,
      heightMeters: null,
      quantity: item.quantity,
      unitPrice: item.unitPrice ?? 0, // precio al cliente (del padre)
      observations: textOrEmpty(item.observations),
      standby: null, // el padre Integral nunca lleva stand-by (sí sus hijos)
      components: (item.children ?? []).map(childResponseToForm),
    }
  }

  return {
    serviceTypeId: item.serviceType.id,
    serviceKind,
    cargoTypeId: item.cargoType?.id ?? null,
    cargoTypeName: item.cargoType?.name ?? '',
    weightKg: item.weightKg ?? null,
    lengthMeters: item.lengthMeters ?? null,
    widthMeters: item.widthMeters ?? null,
    heightMeters: item.heightMeters ?? null,
    quantity: item.quantity,
    unitPrice: item.unitPrice ?? 0, // los root siempre tienen unitPrice
    observations: textOrEmpty(item.observations),
    standby: standbyResponseToForm(item.standby),
    components: [], // siempre presente (el schema lo exige); vacío para no-Integral
  }
}

/**
 * Mapea una `QuotationResponse` (GET /quotations/{id}) al shape del wizard (`WizardFormInput`)
 * para PRECARGAR el formulario de edición. Es el inverso fiel de `quotationFormToRequest`:
 *
 * - summaries embebidos → IDs (`client.id`, `currency.id`, `paymentTerm?.id`, `serviceType.id`, `cargoType?.id`),
 * - ítems jerárquicos (`children[]`) → `components[]` del form,
 * - `serviceKind` auxiliar derivado de `serviceType.kind` (el response lo trae embebido, no hace
 *   falta el catálogo → el mapper es independiente y testeable en aislamiento),
 * - texto `null` → `''` (el form usa strings vacíos).
 *
 * El response ya devuelve el Integral primero (`itemNumber=1`); `quotationFormToRequest` lo
 * reordena defensivamente, así el round-trip GET→form→PUT preserva itemNumber/parentItemNumber.
 * `insuredAmount` se ignora (bug SEG/CSE: campo bloqueado en el front), igual que el request.
 */
export function quotationResponseToForm(quotation: QuotationResponse): WizardFormInput {
  return {
    quotationType: quotation.quotationType,
    clientId: quotation.client.id,
    contactName: textOrEmpty(quotation.contactName),
    contactPhone: textOrEmpty(quotation.contactPhone),
    currencyId: quotation.currency.id,
    paymentTermId: quotation.paymentTerm?.id ?? null,
    tentativeServiceDate: textOrEmpty(quotation.tentativeServiceDate),
    validityDays: quotation.validityDays,
    origin: textOrEmpty(quotation.origin),
    destination: textOrEmpty(quotation.destination),
    clientNote: textOrEmpty(quotation.clientNote),
    internalNote: textOrEmpty(quotation.internalNote),
    items: quotation.items.map(itemResponseToForm),
  }
}
