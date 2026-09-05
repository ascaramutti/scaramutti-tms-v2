import { Card } from '../../../shared/ui/Card'

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
      <h2 className="text-base font-semibold text-fg">Condiciones generales</h2>
      <Card as="ul" className="mt-3 space-y-2">
        {ordered.map((condition) => (
          <li key={condition.id} className="flex gap-2 text-sm text-fg-body">
            <span aria-hidden="true" className="text-fg-subtle">
              •
            </span>
            <span className="whitespace-pre-wrap break-words">{condition.text}</span>
          </li>
        ))}
      </Card>
    </section>
  )
}
