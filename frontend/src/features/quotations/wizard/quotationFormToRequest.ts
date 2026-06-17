import { orderIntegralFirst } from './itemOrdering'
import type { ChildItemInput, ItemInput, WizardFormInput } from './quotation-wizard.schema'
import type {
  QuotationItemRequest,
  QuotationRequest,
  QuotationStandbyCostRequest,
} from '../../../api'

/** Texto opcional: vacío/whitespace → `null` (el backend espera null, no ""). */
function textOrNull(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}

/** Stand-by del form (nullable) → request; omitido (`undefined`) si el ítem no tiene. */
function standbyToRequest(standby: ItemInput['standby']): QuotationStandbyCostRequest | undefined {
  if (!standby) return undefined
  return { pricePerDay: standby.pricePerDay, includesIgv: standby.includesIgv }
}

/**
 * Hijo del Servicio Integral → ítem plano del request. Lleva su `itemNumber` contiguo y
 * referencia al padre vía `parentItemNumber`. NO lleva `unitPrice` (el precio al cliente está
 * en el padre); su costo es el `internalReferencePrice` opcional (0 ≡ sin precio → se omite).
 */
function childToRequest(
  child: ChildItemInput,
  itemNumber: number,
  parentItemNumber: number,
): QuotationItemRequest {
  return {
    itemNumber,
    parentItemNumber,
    serviceTypeId: child.serviceTypeId,
    cargoTypeId: child.cargoTypeId,
    observations: textOrNull(child.observations),
    weightKg: child.weightKg,
    lengthMeters: child.lengthMeters,
    widthMeters: child.widthMeters,
    heightMeters: child.heightMeters,
    quantity: child.quantity,
    internalReferencePrice: child.internalReferencePrice || null,
    standby: standbyToRequest(child.standby),
  }
}

/**
 * Aplana el form del wizard a la `QuotationRequest` del `POST /quotations`: cabecera + lista
 * PLANA de ítems (root e hijos). El Servicio Integral se reordena al frente (debe ser el ítem 1)
 * y se expande en su padre seguido de sus componentes, que lo referencian con `parentItemNumber`.
 *
 * **Todos** los ítems llevan `itemNumber` contiguo (1..N en orden de la lista): el backend exige
 * que el `itemNumber` esté presente en TODOS o en NINGUNO, y como los hijos referencian al padre
 * por número, hay que numerarlos explícitamente (no se puede mezclar con/sin). Normaliza
 * opcionales: texto vacío → null, `internalReferencePrice` 0 → omitido, stand-by ausente →
 * omitido. `unitPrice` se omite en los hijos. `insuredAmount` no se envía (bug SEG/CSE). Los
 * auxiliares del form (`serviceKind`, `cargoTypeName`) no se mapean.
 */
export function quotationFormToRequest(form: WizardFormInput): QuotationRequest {
  // El Integral va primero (debe ser `itemNumber = 1`). Helper compartido con el mapper del
  // Resumen para que la numeración no dependa del orden crudo del form (ver `orderIntegralFirst`).
  const orderedItems = orderIntegralFirst(form.items)

  const items: QuotationItemRequest[] = []
  let itemNumber = 0
  for (const item of orderedItems) {
    if (item.serviceKind === 'INTEGRAL') {
      // Padre del Integral: paquete con el precio al cliente; sin carga/medidas ni stand-by.
      itemNumber += 1
      const parentItemNumber = itemNumber
      items.push({
        itemNumber,
        serviceTypeId: item.serviceTypeId,
        observations: textOrNull(item.observations),
        quantity: item.quantity,
        unitPrice: item.unitPrice,
      })
      for (const child of item.components) {
        itemNumber += 1
        items.push(childToRequest(child, itemNumber, parentItemNumber))
      }
    } else {
      // Ítem root facturable.
      itemNumber += 1
      items.push({
        itemNumber,
        serviceTypeId: item.serviceTypeId,
        cargoTypeId: item.cargoTypeId,
        observations: textOrNull(item.observations),
        weightKg: item.weightKg,
        lengthMeters: item.lengthMeters,
        widthMeters: item.widthMeters,
        heightMeters: item.heightMeters,
        quantity: item.quantity,
        unitPrice: item.unitPrice,
        standby: standbyToRequest(item.standby),
      })
    }
  }

  return {
    quotationType: form.quotationType,
    clientId: form.clientId,
    contactName: form.contactName.trim(),
    contactPhone: textOrNull(form.contactPhone),
    currencyId: form.currencyId,
    paymentTermId: form.paymentTermId,
    tentativeServiceDate: textOrNull(form.tentativeServiceDate),
    validityDays: form.validityDays,
    origin: textOrNull(form.origin),
    destination: textOrNull(form.destination),
    clientNote: textOrNull(form.clientNote),
    internalNote: textOrNull(form.internalNote),
    items,
  }
}
