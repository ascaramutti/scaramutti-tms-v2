import { useFormContext, useFormState } from 'react-hook-form'
import { Trash2 } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import { standbyTargets, type StandbyTarget } from './standbyTargets'
import { STANDBY_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import type { QuotationServiceTypeResponse } from '../../../api'

interface StepStandByProps {
  /** Todos los tipos de servicio (para etiquetar los ítems elegibles). */
  serviceTypes: QuotationServiceTypeResponse[]
}

const FIELD_LABEL = 'mb-1.5 block text-sm font-medium text-slate-700'
const CONTROL =
  'w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500'

/** Empty → `undefined` (precio requerido: zod muestra el mensaje). */
function requiredNum(value: string): number | undefined {
  return value === '' ? undefined : Number(value)
}

/**
 * Step 3 (opcional): Stand-By por ítem. El stand-by se persiste anidado en cada ítem
 * (`items[i].standby`), así que esta vista deriva del form: lista los ítems elegibles
 * (root no-Integral + hijos del Integral) y permite agregarle/quitarle un stand-by.
 */
export function StepStandBy({ serviceTypes }: StepStandByProps) {
  const { watch, setValue, clearErrors } = useFormContext<WizardFormInput>()
  const items = watch('items')
  const targets = standbyTargets(items ?? [], serviceTypes)
  const withStandby = targets.filter((target) => target.hasStandby)
  const without = targets.filter((target) => !target.hasStandby)

  function handleAdd(rawIndex: string) {
    const target = without[Number(rawIndex)]
    if (!target) return
    // Limpiar errores/touched stale de un stand-by previo del mismo ítem (quitar + re-agregar).
    clearErrors(`${target.path}.standby`)
    setValue(`${target.path}.standby`, { ...STANDBY_DEFAULTS }, { shouldTouch: true })
  }

  function removeStandby(path: StandbyTarget['path']) {
    clearErrors(`${path}.standby`)
    setValue(`${path}.standby`, null)
  }

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Stand-By por ítem</h2>
        <p className="text-xs text-slate-500">Paso opcional · máximo un stand-by por ítem</p>
      </div>

      {targets.length === 0 ? (
        <div
          role="alert"
          className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-6 py-10 text-center text-sm text-slate-500"
        >
          No hay ítems que admitan stand-by. Agrega ítems en el paso anterior.
        </div>
      ) : (
        <>
          {without.length > 0 && (
            <div className="max-w-md">
              <label htmlFor="standby-add" className={FIELD_LABEL}>
                Agregar stand-by a un ítem
              </label>
              <select
                id="standby-add"
                value=""
                onChange={(event) => handleAdd(event.target.value)}
                className={cn(CONTROL, 'border-slate-300')}
              >
                <option value="">Selecciona un ítem…</option>
                {without.map((target, index) => (
                  <option key={target.path} value={index}>
                    {target.label}
                  </option>
                ))}
              </select>
            </div>
          )}

          {withStandby.length === 0 ? (
            <p className="text-sm text-slate-500">Ningún ítem tiene stand-by todavía (es opcional).</p>
          ) : (
            <div className="space-y-3">
              {withStandby.map((target) => (
                <StandbyRow key={target.path} target={target} onRemove={() => removeStandby(target.path)} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}

interface StandbyRowProps {
  target: StandbyTarget
  onRemove: () => void
}

/** Fila de un stand-by activo: precio por día + "incluye IGV" + quitar. */
function StandbyRow({ target, onRemove }: StandbyRowProps) {
  const { register, getFieldState } = useFormContext<WizardFormInput>()
  const priceField = `${target.path}.standby.pricePerDay` as const
  const igvField = `${target.path}.standby.includesIgv` as const
  // Suscripción explícita al estado de este campo (no depender del re-render del padre).
  const formState = useFormState<WizardFormInput>({ name: priceField })
  const priceError = getFieldState(priceField, formState).error?.message

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm font-semibold text-slate-900">{target.label}</p>
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Quitar stand-by de ${target.label}`}
          className="shrink-0 text-slate-400 hover:text-red-600"
        >
          <Trash2 className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>
      <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div>
          <span className={FIELD_LABEL} aria-hidden="true">
            Precio por día
          </span>
          <input
            type="number"
            min={0.01}
            step={0.01}
            aria-label={`Precio por día de ${target.label}`}
            aria-invalid={!!priceError}
            onKeyDown={(event) => {
              if (['e', 'E', '+', '-'].includes(event.key)) event.preventDefault()
            }}
            {...register(priceField, { setValueAs: requiredNum })}
            className={cn(CONTROL, priceError ? 'border-red-300' : 'border-slate-300')}
          />
          {priceError && (
            <p role="alert" className="mt-1.5 text-sm text-red-600">
              {priceError}
            </p>
          )}
        </div>
        <label className="flex items-center gap-2 sm:mt-8">
          <input
            type="checkbox"
            aria-label={`El precio incluye IGV — ${target.label}`}
            {...register(igvField)}
            className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-slate-700">El precio incluye IGV</span>
        </label>
      </div>
    </div>
  )
}
