import { useState } from 'react'
import type { UseFormRegisterReturn } from 'react-hook-form'
import { useFormContext } from 'react-hook-form'
import { Trash2 } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import { formatCurrency } from '../../../shared/utils/formatters'
import { CargoTypeField } from './CargoTypeField'
import { componentReferenceSubtotal } from './itemCalc'
import { type ChildServiceKind, type WizardFormInput } from './quotation-wizard.schema'
import type { QuotationServiceTypeResponse } from '../../../api'

interface ChildItemCardProps {
  /** Índice del ítem padre (el Integral) en `items`. */
  parentIndex: number
  /** Índice del componente dentro de `items[parentIndex].components`. */
  index: number
  /** Número visible (1-based). */
  position: number
  /** Tipos de servicio YA filtrados a SERVICIO + COMPLEMENTARIO. */
  serviceTypes: QuotationServiceTypeResponse[]
  currencyCode: string
  onRemove: () => void
}

const FIELD_LABEL = 'mb-1.5 block text-sm font-medium text-slate-700'
const CONTROL =
  'w-full rounded-lg border bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500'
const READONLY =
  'w-full cursor-default rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600 focus:outline-none'

/** Empty → `null` (campos numéricos opcionales). */
function nullableNum(value: string): number | null {
  return value === '' ? null : Number(value)
}
/** Empty → `undefined` (campos numéricos requeridos: zod muestra el mensaje). */
function requiredNum(value: string): number | undefined {
  return value === '' ? undefined : Number(value)
}

interface NumberCellProps {
  label: string
  /** Nombre accesible único (evita colisión con los campos del ítem padre en los tests). */
  ariaLabel: string
  register: UseFormRegisterReturn
  error?: string
  min?: number
  step?: number
}

/** Celda numérica del componente. Usa `aria-label` único (no `<label htmlFor>`) para no
 * colisionar con los campos homónimos del ítem padre al consultarlos por su label. */
function NumberCell({ label, ariaLabel, register, error, min = 0, step = 0.01 }: NumberCellProps) {
  return (
    <div>
      <span className={FIELD_LABEL} aria-hidden="true">
        {label}
      </span>
      <input
        type="number"
        min={min}
        step={step}
        aria-label={ariaLabel}
        onKeyDown={(event) => {
          if (['e', 'E', '+', '-'].includes(event.key)) event.preventDefault()
        }}
        {...register}
        className={cn(CONTROL, error ? 'border-red-300' : 'border-slate-300')}
      />
      {error && (
        <p role="alert" className="mt-1 text-xs text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}

/**
 * Componente (hijo) de un Servicio Integral. Mini-card compacta e indentada. Su precio
 * es de REFERENCIA interna (no se cobra al cliente ni suma al total). Los campos se
 * revelan al elegir el tipo; un componente de transporte (SERVICIO) pide carga + peso.
 */
export function ChildItemCard({
  parentIndex,
  index,
  position,
  serviceTypes,
  currencyCode,
  onRemove,
}: ChildItemCardProps) {
  const {
    register,
    watch,
    setValue,
    trigger,
    clearErrors,
    formState: { errors },
  } = useFormContext<WizardFormInput>()

  const base = `items.${parentIndex}.components.${index}` as const
  const component = watch(base)
  // Retiene lo tipeado (ej. "1.") mientras se edita el precio ref; al salir se deriva del form.
  const [priceDraft, setPriceDraft] = useState<string | null>(null)
  const hasServiceType = !!component?.serviceTypeId
  const isServicio = component?.serviceKind === 'SERVICIO'
  const childErrors = errors.items?.[parentIndex]?.components?.[index]
  const refSubtotal = component ? componentReferenceSubtotal(component) : 0
  const idPrefix = `child-${parentIndex}-${index}`

  function handleServiceTypeChange(rawValue: string) {
    const id = rawValue === '' ? 0 : Number(rawValue)
    const serviceType = serviceTypes.find((type) => type.id === id)
    const kind = (serviceType?.kind ?? 'COMPLEMENTARIO') as ChildServiceKind
    setValue(`${base}.serviceTypeId`, id, { shouldTouch: true })
    setValue(`${base}.serviceKind`, kind)
    // No validar todo el componente acá (mostraría el error de peso antes de tocarlo). La guía
    // de composición se recalcula sola (reactiva); acá solo limpiamos los errores de campo.
    clearErrors(base)
    // Al dejar de ser SERVICIO, limpiar carga/medidas (no aplican a complementarios).
    if (kind !== 'SERVICIO') {
      setValue(`${base}.cargoTypeId`, null)
      setValue(`${base}.cargoTypeName`, '')
      setValue(`${base}.weightKg`, null)
      setValue(`${base}.lengthMeters`, null)
      setValue(`${base}.widthMeters`, null)
      setValue(`${base}.heightMeters`, null)
    }
  }

  return (
    <div
      data-testid="integral-child"
      className="rounded-lg border-l-2 border-dashed border-orange-300 bg-slate-50/60 pl-4"
    >
      <div className="flex items-center justify-between gap-2 px-2 py-2">
        <span className="text-sm font-semibold text-slate-700">Componente {position}</span>
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Eliminar componente ${position}`}
          className="shrink-0 text-slate-400 hover:text-red-600"
        >
          <Trash2 className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>

      <div className="space-y-3 px-2 pb-3">
        <div>
          <span className={FIELD_LABEL} aria-hidden="true">
            Tipo de servicio
          </span>
          <select
            aria-label={`Tipo de servicio del componente ${position}`}
            value={component?.serviceTypeId || ''}
            onChange={(event) => handleServiceTypeChange(event.target.value)}
            onBlur={() => trigger(`${base}.serviceTypeId`)}
            aria-invalid={!!childErrors?.serviceTypeId}
            className={cn(CONTROL, childErrors?.serviceTypeId ? 'border-red-300' : 'border-slate-300')}
          >
            <option value="">Selecciona</option>
            {serviceTypes.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
              </option>
            ))}
          </select>
          {childErrors?.serviceTypeId?.message && (
            <p role="alert" className="mt-1 text-xs text-red-600">
              {childErrors.serviceTypeId.message}
            </p>
          )}
        </div>

        {hasServiceType && (
          <>
            {isServicio && (
              <>
                <CargoTypeField
                  id={`${idPrefix}-cargoType`}
                  value={component?.cargoTypeId ?? null}
                  valueName={component?.cargoTypeName}
                  onChange={(cargoType) => {
                    setValue(`${base}.cargoTypeId`, cargoType?.id ?? null, {
                      shouldValidate: true,
                      shouldTouch: true,
                    })
                    setValue(`${base}.cargoTypeName`, cargoType?.name ?? '')
                    setValue(`${base}.weightKg`, cargoType?.standardWeight ?? null, { shouldValidate: true })
                    setValue(`${base}.lengthMeters`, cargoType?.standardLength ?? null)
                    setValue(`${base}.widthMeters`, cargoType?.standardWidth ?? null)
                    setValue(`${base}.heightMeters`, cargoType?.standardHeight ?? null)
                  }}
                  onBlur={() => trigger(`${base}.cargoTypeId`)}
                  error={childErrors?.cargoTypeId?.message}
                />
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <NumberCell
                    label="Peso (kg)"
                    ariaLabel={`Peso del componente ${position}`}
                    error={childErrors?.weightKg?.message}
                    register={register(`${base}.weightKg`, { setValueAs: nullableNum })}
                  />
                  <NumberCell
                    label="Largo (m)"
                    ariaLabel={`Largo del componente ${position}`}
                    register={register(`${base}.lengthMeters`, { setValueAs: nullableNum })}
                  />
                  <NumberCell
                    label="Ancho (m)"
                    ariaLabel={`Ancho del componente ${position}`}
                    register={register(`${base}.widthMeters`, { setValueAs: nullableNum })}
                  />
                  <NumberCell
                    label="Alto (m)"
                    ariaLabel={`Alto del componente ${position}`}
                    register={register(`${base}.heightMeters`, { setValueAs: nullableNum })}
                  />
                </div>
              </>
            )}

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <NumberCell
                label="Cantidad"
                ariaLabel={`Cantidad del componente ${position}`}
                min={1}
                step={1}
                error={childErrors?.quantity?.message}
                register={register(`${base}.quantity`, { setValueAs: requiredNum })}
              />
              <div>
                <span className={FIELD_LABEL} aria-hidden="true">
                  Precio ref. (opcional)
                </span>
                <input
                  type="number"
                  min={0}
                  step={0.01}
                  aria-label={`Precio de referencia del componente ${position}`}
                  // Controlado (no `register`): un campo sin tocar queda vacío. Si fuera registrado,
                  // react-hook-form coerce el number vacío a 0 al revalidar (= precio ref "fantasma").
                  value={
                    priceDraft ??
                    (component?.internalReferencePrice != null ? String(component.internalReferencePrice) : '')
                  }
                  onChange={(event) => {
                    const raw = event.target.value
                    setPriceDraft(raw)
                    setValue(`${base}.internalReferencePrice`, raw === '' ? null : Number(raw), {
                      shouldTouch: true,
                    })
                  }}
                  onBlur={() => setPriceDraft(null)}
                  onKeyDown={(event) => {
                    if (['e', 'E', '+', '-'].includes(event.key)) event.preventDefault()
                  }}
                  className={cn(CONTROL, childErrors?.internalReferencePrice ? 'border-red-300' : 'border-slate-300')}
                />
                {childErrors?.internalReferencePrice?.message && (
                  <p role="alert" className="mt-1 text-xs text-red-600">
                    {childErrors.internalReferencePrice.message}
                  </p>
                )}
              </div>
              <div>
                <span className={FIELD_LABEL} aria-hidden="true">
                  Total ref.
                </span>
                <input
                  type="text"
                  readOnly
                  value={refSubtotal > 0 ? formatCurrency(refSubtotal, currencyCode) : '—'}
                  aria-label={`Total del componente ${position}`}
                  className={READONLY}
                />
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
