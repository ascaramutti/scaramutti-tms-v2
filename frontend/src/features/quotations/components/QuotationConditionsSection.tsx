/** Condición a mostrar (subset estructural: sirve para `ConditionResponse` y `QuotationConditionResponse`). */
interface DisplayCondition {
  id: number
  text: string
  displayOrder: number
}

interface QuotationConditionsSectionProps {
  /** Condiciones a listar (read-only). Se ordenan por `displayOrder` ASC. */
  conditions: ReadonlyArray<DisplayCondition>
}

/**
 * Sección read-only "Condiciones generales": lista el texto de las condiciones aplicadas a la
 * cotización, ordenadas por `displayOrder` ASC (RN-04). Compartida por el Resumen del wizard
 * (Step4SummaryConditions) y el Detalle. Si no hay condiciones, no renderiza nada (consistente
 * con QuotationNotesSection y el resto de secciones que se ocultan vacías).
 */
export function QuotationConditionsSection({ conditions }: QuotationConditionsSectionProps) {
  if (conditions.length === 0) {
    return null
  }
  const ordered = [...conditions].sort((a, b) => a.displayOrder - b.displayOrder)

  return (
    <section>
      <h2 className="text-base font-semibold text-slate-900">Condiciones generales</h2>
      <ul className="mt-3 space-y-2 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        {ordered.map((condition) => (
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
