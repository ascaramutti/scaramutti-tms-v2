import { itemSubtotal } from './itemCalc'
import type { ChildItemInput, WizardFormInput } from './quotation-wizard.schema'
import type { QuotationItemResponse, QuotationServiceTypeResponse } from '../../../api'

/** Tipo de servicio de respaldo si el id del form no está en el catálogo (defensivo). */
const FALLBACK_SERVICE_TYPE = { id: 0, code: '', name: 'Sin tipo', kind: '' }

/**
 * Mapea los ítems del FORM (anidados en `components[]`) al shape de la API
 * (`QuotationItemResponse` con `children[]`), para reusar los componentes read-only del
 * Detalle (`QuotationItemsSection`, `QuotationStandbyTable`) en el Resumen del wizard.
 *
 * Asigna `itemNumber` consecutivo (root + hijos, como el backend al aplanar) y calcula el
 * `subtotal` neto. Es solo para el RENDER del Resumen: el aplanado real al `POST /quotations`
 * (con `parentItemNumber`) se implementa en un PR posterior.
 */
export function quotationFormItemsToResponse(
  items: WizardFormInput['items'],
  serviceTypes: QuotationServiceTypeResponse[],
  igvPercentage: number,
): QuotationItemResponse[] {
  let counter = 0
  const serviceTypeOf = (id: number) => serviceTypes.find((type) => type.id === id) ?? FALLBACK_SERVICE_TYPE

  function mapChild(child: ChildItemInput): QuotationItemResponse {
    const itemNumber = ++counter
    return {
      id: itemNumber,
      itemNumber,
      parentItemId: null,
      serviceType: serviceTypeOf(child.serviceTypeId),
      cargoType: child.cargoTypeId ? { id: child.cargoTypeId, name: child.cargoTypeName ?? '' } : undefined,
      observations: child.observations || null,
      weightKg: child.weightKg,
      lengthMeters: child.lengthMeters,
      widthMeters: child.widthMeters,
      heightMeters: child.heightMeters,
      quantity: child.quantity,
      unitPrice: 0, // los hijos del Integral no se cobran (su costo está en el padre)
      // 0 ≡ sin precio referencial (un precio ref de 0 no existe): se normaliza a null.
      internalReferencePrice: child.internalReferencePrice || null,
      igvPercentage,
      subtotal: 0,
      standby: child.standby
        ? { id: 0, pricePerDay: child.standby.pricePerDay, includesIgv: child.standby.includesIgv }
        : undefined,
    }
  }

  return items.map((item) => {
    const itemNumber = ++counter
    const children = item.serviceKind === 'INTEGRAL' ? item.components.map(mapChild) : []
    return {
      id: itemNumber,
      itemNumber,
      parentItemId: null,
      serviceType: serviceTypeOf(item.serviceTypeId),
      cargoType: item.cargoTypeId ? { id: item.cargoTypeId, name: item.cargoTypeName ?? '' } : undefined,
      observations: item.observations || null,
      weightKg: item.weightKg,
      lengthMeters: item.lengthMeters,
      widthMeters: item.widthMeters,
      heightMeters: item.heightMeters,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      internalReferencePrice: null,
      igvPercentage,
      // Mismo helper canónico que `itemsSubtotal` (Step 2): `num()` degrada un campo vacío
      // (ej. cantidad borrada en el wizard no-bloqueante) a 0 en vez de propagar NaN al total.
      subtotal: itemSubtotal(item),
      standby: item.standby
        ? { id: 0, pricePerDay: item.standby.pricePerDay, includesIgv: item.standby.includesIgv }
        : undefined,
      children,
    }
  })
}

/**
 * Totales de la cotización a partir de los ítems YA mapeados: subtotal (suma de los netos de
 * los ítems root), IGV y total. Un solo origen del cálculo para el Resumen y, a futuro, el
 * submit — así no divergen. Los hijos del Integral no suman (su costo está en el padre y no
 * entran al array root).
 */
export function quotationTotals(
  items: QuotationItemResponse[],
  igvPercentage: number,
): { subtotal: number; igv: number; total: number } {
  const subtotal = items.reduce((accumulator, item) => accumulator + item.subtotal, 0)
  const igv = subtotal * (igvPercentage / 100)
  return { subtotal, igv, total: subtotal + igv }
}
