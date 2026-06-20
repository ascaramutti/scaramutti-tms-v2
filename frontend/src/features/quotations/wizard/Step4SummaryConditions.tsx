import type { ConditionResponse } from '../../../api'

interface Step4SummaryConditionsProps {
  /** Catálogo de condiciones activas (para resolver los ids elegidos a su texto). */
  conditions: ConditionResponse[]
  /** Ids elegidos en el form (`conditionIds`). */
  selectedIds: number[]
}

/**
 * Bloque read-only del Resumen que lista las condiciones generales elegidas (RN-12). Resuelve
 * `selectedIds` contra el catálogo activo y las muestra ordenadas por `displayOrder` ASC (RN-04).
 * Es "lo que se va a guardar": una condición desactivada ya no está en `conditionIds` (el mapper
 * de edición la excluye), por eso no aparece — coherente con el envío. Si no hay ninguna elegida,
 * no renderiza nada (consistente con el resto del Resumen, que oculta secciones vacías).
 */
export function Step4SummaryConditions({ conditions, selectedIds }: Step4SummaryConditionsProps) {
  const selected = conditions
    .filter((condition) => selectedIds.includes(condition.id))
    .sort((a, b) => a.displayOrder - b.displayOrder)

  if (selected.length === 0) {
    return null
  }

  return (
    <section>
      <h2 className="text-base font-semibold text-slate-900">Condiciones generales</h2>
      <ul className="mt-3 space-y-2 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        {selected.map((condition) => (
          <li key={condition.id} className="flex gap-2 text-sm text-slate-700">
            <span aria-hidden="true" className="text-slate-400">
              •
            </span>
            <span className="whitespace-pre-wrap break-words">{condition.text}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
