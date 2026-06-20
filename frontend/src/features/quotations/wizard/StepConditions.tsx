import { useFormContext } from 'react-hook-form'
import { AlertTriangle } from 'lucide-react'
import { QuotationNotesFields } from './QuotationNotesFields'
import type { WizardFormInput } from './quotation-wizard.schema'
import type { ConditionResponse, QuotationConditionResponse } from '../../../api'

interface StepConditionsProps {
  /** Catálogo de condiciones ACTIVAS (las elegibles, ordenadas por displayOrder). */
  conditions: ConditionResponse[]
  /** Condiciones linkeadas a la cotización (solo edición; creación = []). Se usan para detectar
   *  las que quedaron INACTIVAS tras emitirse y mostrarlas como "ya no vigentes". */
  linkedConditions?: QuotationConditionResponse[]
}

/**
 * Paso "Observaciones y condiciones" (opcional). Arriba: las OBSERVACIONES (cliente + internas).
 * Abajo: elige con checkboxes las CONDICIONES GENERALES que aplican a la cotización (en creación
 * las activas vienen pre-marcadas, RN-07; el pre-check lo hace WizardForm en los defaults). La
 * selección de condiciones vive en el form (`conditionIds`); este paso solo la lee/escribe.
 *
 * Edición: una condición que estaba linkeada pero se DESACTIVÓ del catálogo no es re-elegible
 * (la escritura exige todas activas → 409 QUO-007). No se oculta: se lista aparte como "ya no
 * vigente" (deshabilitada) y al guardar se pierde del set — el usuario ve qué deja de aplicar.
 */
export function StepConditions({ conditions, linkedConditions = [] }: StepConditionsProps) {
  const { watch, setValue } = useFormContext<WizardFormInput>()
  const selected = watch('conditionIds') ?? []

  // Linkeadas que ya no están vigentes (snapshot histórico): las que el catálogo activo no incluye.
  const activeIds = new Set(conditions.map((c) => c.id))
  const inactiveLinked = linkedConditions.filter((c) => !activeIds.has(c.id))

  function toggle(id: number, checked: boolean) {
    const next = checked ? [...selected, id] : selected.filter((value) => value !== id)
    setValue('conditionIds', next, { shouldDirty: true, shouldTouch: true })
  }

  return (
    <div className="space-y-6">
      <QuotationNotesFields />

      <div className="space-y-4 border-t border-slate-200 pt-6">
        <div>
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
            Condiciones generales
          </h2>
          <p className="text-xs text-slate-500">
            Paso opcional · marca las que aplican a esta cotización (se imprimen en el PDF)
          </p>
        </div>

        {conditions.length === 0 ? (
          <div
            role="status"
            className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-6 py-10 text-center text-sm text-slate-500"
          >
            No hay condiciones generales configuradas. La cotización se guardará sin condiciones.
          </div>
        ) : (
          <fieldset className="space-y-2">
            <legend className="sr-only">Condiciones generales aplicables</legend>
            {conditions.map((condition) => (
              <label
                key={condition.id}
                className="flex cursor-pointer items-start gap-3 rounded-xl border border-slate-200 bg-white p-4 hover:bg-slate-50"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(condition.id)}
                  onChange={(event) => toggle(condition.id, event.target.checked)}
                  className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="text-sm text-slate-700">{condition.text}</span>
              </label>
            ))}
          </fieldset>
        )}

        {inactiveLinked.length > 0 && (
          <div className="space-y-2">
            <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-amber-700">
              <AlertTriangle className="h-3.5 w-3.5" aria-hidden="true" />
              Ya no vigentes
            </h3>
            <p className="text-xs text-slate-500">
              Estas condiciones se desactivaron del catálogo y no se pueden volver a aplicar. Si
              guardas, dejarán de estar en la cotización.
            </p>
            {inactiveLinked.map((condition) => (
              <label
                key={condition.id}
                className="flex items-start gap-3 rounded-xl border border-slate-200 bg-slate-50 p-4 opacity-70"
              >
                <input
                  type="checkbox"
                  checked={false}
                  disabled
                  aria-label={`${condition.text} (ya no vigente)`}
                  className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300"
                />
                <span className="text-sm text-slate-500 line-through">{condition.text}</span>
              </label>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
