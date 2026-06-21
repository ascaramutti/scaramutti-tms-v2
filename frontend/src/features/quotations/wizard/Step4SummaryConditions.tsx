import { QuotationConditionsSection } from '../components/QuotationConditionsSection'
import type { ConditionResponse } from '../../../api'

interface Step4SummaryConditionsProps {
  /** Catálogo de condiciones activas (para resolver los ids elegidos a su texto). */
  conditions: ConditionResponse[]
  /** Ids elegidos en el form (`conditionIds`). */
  selectedIds: number[]
}

/**
 * Bloque read-only del Resumen del wizard con las condiciones elegidas (RN-12). Resuelve
 * `selectedIds` contra el catálogo activo y delega el render en {@link QuotationConditionsSection}
 * (mismo look que el Detalle). Es "lo que se va a guardar": una condición desactivada ya no está
 * en `conditionIds` (el mapper de edición la excluye), por eso no aparece — coherente con el envío.
 */
export function Step4SummaryConditions({ conditions, selectedIds }: Step4SummaryConditionsProps) {
  const selected = conditions.filter((condition) => selectedIds.includes(condition.id))
  return <QuotationConditionsSection conditions={selected} />
}
