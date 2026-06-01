import { useState } from 'react'
import { useFormContext } from 'react-hook-form'
import { AlertCircle, CheckCircle2, ChevronDown, ChevronUp, Trash2 } from 'lucide-react'
import { TextField } from '../../../shared/ui/TextField'
import { cn } from '../../../shared/utils/cn'
import { formatCurrency } from '../../../shared/utils/formatters'
import { CargoTypeField } from './CargoTypeField'
import { IntegralComponents } from './IntegralComponents'
import { itemTotal } from './itemCalc'
import { itemSchema, type ItemServiceKind, type WizardFormInput } from './quotation-wizard.schema'
import type { QuotationServiceTypeResponse } from '../../../api'

interface ItemCardProps {
  index: number
  /** Número visible (1-based). */
  position: number
  /** Tipos de servicio YA filtrados por el tipo de cotización. */
  serviceTypes: QuotationServiceTypeResponse[]
  igvPercentage: number
  currencyCode: string
  expanded: boolean
  onToggle: () => void
  onRemove: () => void
}

const FIELD_LABEL = 'mb-1.5 block text-sm font-medium text-slate-700'
const CONTROL =
  'w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500'
const READONLY =
  'w-full cursor-default rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm text-slate-600 focus:outline-none'

/** Empty → `null` (campos numéricos opcionales). */
function nullableNum(value: string): number | null {
  return value === '' ? null : Number(value)
}
/** Empty → `undefined` (campos numéricos requeridos: zod muestra el mensaje). */
function requiredNum(value: string): number | undefined {
  return value === '' ? undefined : Number(value)
}

/** Tarjeta colapsable de edición de un ítem root. El header (siempre visible) muestra
 * el resumen + estado + total; el cuerpo solo si está expandido. Los campos del ítem
 * se revelan recién al elegir el tipo de servicio (progressive disclosure). */
export function ItemCard({
  index,
  position,
  serviceTypes,
  igvPercentage,
  currencyCode,
  expanded,
  onToggle,
  onRemove,
}: ItemCardProps) {
  const {
    register,
    watch,
    setValue,
    trigger,
    clearErrors,
    formState: { errors },
  } = useFormContext<WizardFormInput>()

  const item = watch(`items.${index}`)
  const hasServiceType = !!item?.serviceTypeId
  const isServicio = item?.serviceKind === 'SERVICIO'
  const isIntegral = item?.serviceKind === 'INTEGRAL'
  const itemErrors = errors.items?.[index]
  const total = item ? itemTotal(item, igvPercentage) : 0
  const isComplete = item ? itemSchema.safeParse(item).success : false
  const typeName = serviceTypes.find((type) => type.id === item?.serviceTypeId)?.name

  // El total es editable: permite cotizar "al revés" (tipear el total con IGV y derivar el
  // precio unitario, EN VIVO). `totalDraft` retiene lo tipeado mientras se edita; al salir
  // del campo se limpia y el total vuelve a mostrarse derivado del precio unitario.
  const [totalDraft, setTotalDraft] = useState<string | null>(null)
  const totalDisplay = totalDraft ?? (total > 0 ? total.toFixed(2) : '')

  function handleTotalChange(raw: string) {
    setTotalDraft(raw)
    const totalNum = Number(raw)
    if (raw === '' || !Number.isFinite(totalNum) || totalNum < 0) return
    const factor = (Number(item?.quantity) || 0) * (1 + igvPercentage / 100)
    if (factor <= 0) return
    // Elegir el precio unitario (2 decimales) cuyo total recalculado quede más cerca del
    // tipeado: por el redondeo a 2 dec, un total redondo puede no ser alcanzable exacto.
    const base = Math.round((totalNum / factor) * 100) / 100
    const newUnitPrice = [base - 0.01, base, base + 0.01]
      .filter((price) => price >= 0)
      .reduce((best, price) =>
        Math.abs(price * factor - totalNum) < Math.abs(best * factor - totalNum) ? price : best,
      )
    setValue(`items.${index}.unitPrice`, Math.round(newUnitPrice * 100) / 100, {
      shouldValidate: true,
      shouldTouch: true,
    })
  }

  function handleServiceTypeChange(rawValue: string) {
    const id = rawValue === '' ? 0 : Number(rawValue)
    const serviceType = serviceTypes.find((type) => type.id === id)
    const kind = (serviceType?.kind ?? 'SERVICIO') as ItemServiceKind
    setValue(`items.${index}.serviceTypeId`, id, { shouldTouch: true })
    setValue(`items.${index}.serviceKind`, kind)
    // No validar todo el ítem acá: mostraría el error de peso antes de tocarlo. Limpiamos
    // los errores del ítem; reaparecen al interactuar (onTouched) o al apretar "Siguiente".
    clearErrors(`items.${index}`)
    // Al dejar de ser SERVICIO, limpiar carga/medidas (no aplican a otros kinds).
    if (kind !== 'SERVICIO') {
      setValue(`items.${index}.cargoTypeId`, null)
      setValue(`items.${index}.cargoTypeName`, '')
      setValue(`items.${index}.weightKg`, null)
      setValue(`items.${index}.lengthMeters`, null)
      setValue(`items.${index}.widthMeters`, null)
      setValue(`items.${index}.heightMeters`, null)
    }
    // Al dejar de ser INTEGRAL, descartar los componentes del paquete.
    if (kind !== 'INTEGRAL') {
      setValue(`items.${index}.components`, [])
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white shadow-sm">
      {/* Header colapsable: resumen + estado + total. */}
      <div className="flex items-center gap-2 p-4">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={expanded}
          className="flex min-w-0 flex-1 items-center gap-3 rounded-lg text-left focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-100 text-sm font-semibold text-blue-700">
            {position}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-semibold text-slate-900">Ítem {position}</span>
            <span className="block truncate text-xs text-slate-500">
              {typeName ?? 'Sin tipo de servicio'}
            </span>
          </span>
          {!expanded && (
            <span
              className={cn(
                'flex shrink-0 items-center gap-1 text-xs font-medium',
                isComplete ? 'text-emerald-600' : 'text-red-500',
              )}
            >
              {isComplete ? (
                <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              ) : (
                <AlertCircle className="h-4 w-4" aria-hidden="true" />
              )}
              <span className="hidden sm:inline">{isComplete ? 'Completo' : 'Faltan datos'}</span>
            </span>
          )}
          <span className="shrink-0 text-sm font-semibold text-slate-900">
            {formatCurrency(total, currencyCode)}
          </span>
          {expanded ? (
            <ChevronUp className="h-4 w-4 shrink-0 text-slate-400" aria-hidden="true" />
          ) : (
            <ChevronDown className="h-4 w-4 shrink-0 text-slate-400" aria-hidden="true" />
          )}
        </button>
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Eliminar ítem ${position}`}
          className="shrink-0 text-slate-400 hover:text-red-600"
        >
          <Trash2 className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>

      {expanded && (
        <div className="space-y-4 border-t border-slate-100 p-5 pt-4">
          <div>
            <label htmlFor={`item-${index}-serviceType`} className={FIELD_LABEL}>
              Tipo de servicio
            </label>
            <select
              id={`item-${index}-serviceType`}
              value={item?.serviceTypeId || ''}
              onChange={(event) => handleServiceTypeChange(event.target.value)}
              onBlur={() => trigger(`items.${index}.serviceTypeId`)}
              aria-invalid={!!itemErrors?.serviceTypeId}
              className={cn(CONTROL, itemErrors?.serviceTypeId ? 'border-red-300' : 'border-slate-300')}
            >
              <option value="">Selecciona</option>
              {serviceTypes.map((type) => {
                // El Servicio Integral (paquete jerárquico) solo se permite como ítem #1.
                const integralLocked = type.kind === 'INTEGRAL' && position !== 1
                return (
                  <option key={type.id} value={type.id} disabled={integralLocked}>
                    {type.name}
                  </option>
                )
              })}
            </select>
            {itemErrors?.serviceTypeId?.message && (
              <p role="alert" className="mt-1.5 text-sm text-red-600">
                {itemErrors.serviceTypeId.message}
              </p>
            )}
          </div>

          {!hasServiceType ? (
            <p className="text-sm text-slate-500">
              Elige un tipo de servicio para completar el ítem.
            </p>
          ) : (
            <>
              {isIntegral && (
                <IntegralComponents
                  parentIndex={index}
                  serviceTypes={serviceTypes}
                  currencyCode={currencyCode}
                />
              )}
              {isServicio && (
                <>
                  <CargoTypeField
                    id={`item-${index}-cargoType`}
                    value={item?.cargoTypeId ?? null}
                    valueName={item?.cargoTypeName}
                    onChange={(cargoType) => {
                      setValue(`items.${index}.cargoTypeId`, cargoType?.id ?? null, {
                        shouldValidate: true,
                        shouldTouch: true,
                      })
                      setValue(`items.${index}.cargoTypeName`, cargoType?.name ?? '')
                      // Al elegir: precarga el peso/dimensiones estándar (editable). Al quitarlo: los vacía.
                      setValue(`items.${index}.weightKg`, cargoType?.standardWeight ?? null, {
                        shouldValidate: true,
                      })
                      setValue(`items.${index}.lengthMeters`, cargoType?.standardLength ?? null)
                      setValue(`items.${index}.widthMeters`, cargoType?.standardWidth ?? null)
                      setValue(`items.${index}.heightMeters`, cargoType?.standardHeight ?? null)
                    }}
                    onBlur={() => trigger(`items.${index}.cargoTypeId`)}
                    error={itemErrors?.cargoTypeId?.message}
                  />
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
                    <TextField
                      id={`item-${index}-weight`}
                      label="Peso (kg)"
                      type="number"
                      min={0}
                      step={0.01}
                      error={itemErrors?.weightKg?.message}
                      register={register(`items.${index}.weightKg`, { setValueAs: nullableNum })}
                    />
                    <TextField
                      id={`item-${index}-length`}
                      label="Largo (m)"
                      type="number"
                      min={0}
                      step={0.01}
                      register={register(`items.${index}.lengthMeters`, { setValueAs: nullableNum })}
                    />
                    <TextField
                      id={`item-${index}-width`}
                      label="Ancho (m)"
                      type="number"
                      min={0}
                      step={0.01}
                      register={register(`items.${index}.widthMeters`, { setValueAs: nullableNum })}
                    />
                    <TextField
                      id={`item-${index}-height`}
                      label="Alto (m)"
                      type="number"
                      min={0}
                      step={0.01}
                      register={register(`items.${index}.heightMeters`, { setValueAs: nullableNum })}
                    />
                  </div>
                </>
              )}

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
                <TextField
                  id={`item-${index}-quantity`}
                  label="Cantidad"
                  type="number"
                  min={1}
                  step={1}
                  error={itemErrors?.quantity?.message}
                  register={register(`items.${index}.quantity`, { setValueAs: requiredNum })}
                />
                <TextField
                  id={`item-${index}-unitPrice`}
                  label="Precio unitario"
                  type="number"
                  min={0}
                  step={0.01}
                  error={itemErrors?.unitPrice?.message}
                  register={register(`items.${index}.unitPrice`, { setValueAs: requiredNum })}
                />
                <div>
                  <label htmlFor={`item-${index}-igv`} className={FIELD_LABEL}>
                    IGV (%)
                  </label>
                  <input id={`item-${index}-igv`} type="text" value={igvPercentage} readOnly className={READONLY} />
                </div>
                <div>
                  <label htmlFor={`item-${index}-total`} className={FIELD_LABEL}>
                    Total (incl. IGV)
                  </label>
                  <input
                    id={`item-${index}-total`}
                    type="number"
                    min={0}
                    step={0.01}
                    value={totalDisplay}
                    onChange={(event) => handleTotalChange(event.target.value)}
                    onBlur={() => setTotalDraft(null)}
                    onKeyDown={(event) => {
                      if (['e', 'E', '+', '-'].includes(event.key)) event.preventDefault()
                    }}
                    aria-label={`Total del ítem ${position}`}
                    className={cn(CONTROL, 'border-slate-300')}
                  />
                </div>
              </div>

              <div>
                <label htmlFor={`item-${index}-observations`} className={FIELD_LABEL}>
                  Observaciones (opcional)
                </label>
                <textarea
                  id={`item-${index}-observations`}
                  rows={2}
                  {...register(`items.${index}.observations`)}
                  className={cn(CONTROL, 'resize-none', itemErrors?.observations ? 'border-red-300' : 'border-slate-300')}
                />
                {itemErrors?.observations?.message && (
                  <p role="alert" className="mt-1.5 text-sm text-red-600">
                    {itemErrors.observations.message}
                  </p>
                )}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
