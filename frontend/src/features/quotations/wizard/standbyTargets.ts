import type { WizardFormInput } from './quotation-wizard.schema'
import type { QuotationServiceTypeResponse } from '../../../api'

export interface StandbyTarget {
  /** Path RHF al ítem (root o componente del Integral) que admite stand-by. */
  path: `items.${number}` | `items.${number}.components.${number}`
  /** Etiqueta visible (ej. "Ítem 2 — Escolta armada"). */
  label: string
  /** Si el ítem ya tiene un stand-by cargado. */
  hasStandby: boolean
}

/**
 * Ítems que admiten stand-by: los root que NO son Servicio Integral + los componentes
 * (hijos) de un Integral. El ítem padre Integral se EXCLUYE: el backend lo rechaza
 * (`KINDS_WITHOUT_STANDBY`), el stand-by va en sus componentes individualmente.
 * Solo se listan ítems/componentes con tipo de servicio ya elegido.
 */
export function standbyTargets(
  items: WizardFormInput['items'],
  serviceTypes: QuotationServiceTypeResponse[],
): StandbyTarget[] {
  const nameOf = (serviceTypeId: number) =>
    serviceTypes.find((type) => type.id === serviceTypeId)?.name ?? 'Sin tipo'

  const targets: StandbyTarget[] = []
  items.forEach((item, i) => {
    if (item.serviceKind === 'INTEGRAL') {
      item.components.forEach((component, j) => {
        if (!component.serviceTypeId) return
        targets.push({
          path: `items.${i}.components.${j}` as const,
          label: `Ítem ${i + 1} · Componente ${j + 1} — ${nameOf(component.serviceTypeId)}`,
          hasStandby: !!component.standby,
        })
      })
    } else if (item.serviceTypeId) {
      targets.push({
        path: `items.${i}` as const,
        label: `Ítem ${i + 1} — ${nameOf(item.serviceTypeId)}`,
        hasStandby: !!item.standby,
      })
    }
  })
  return targets
}

type StandbyPricePath =
  | `items.${number}.standby.pricePerDay`
  | `items.${number}.components.${number}.standby.pricePerDay`

/**
 * Paths RHF a los precios de los stand-by cargados (root o componentes). Sirve para validar
 * SOLO el stand-by (Step 3) sin re-validar todo el array de ítems (que es del Step 2).
 */
export function standbyPricePaths(items: WizardFormInput['items']): StandbyPricePath[] {
  const paths: StandbyPricePath[] = []
  items.forEach((item, i) => {
    if (item.standby) paths.push(`items.${i}.standby.pricePerDay`)
    item.components.forEach((component, j) => {
      if (component.standby) paths.push(`items.${i}.components.${j}.standby.pricePerDay`)
    })
  })
  return paths
}
