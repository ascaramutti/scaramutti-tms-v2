import { useState } from 'react'
import { useFieldArray, useFormContext } from 'react-hook-form'
import { Info, Plus } from 'lucide-react'
import { formatCurrency } from '../../../shared/utils/formatters'
import { ItemCard } from './ItemCard'
import { itemsSubtotal } from './itemCalc'
import { ITEM_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import type { CurrencyResponse, QuotationServiceTypeResponse } from '../../../api'
import { Button } from '../../../shared/ui/Button'

interface Step2ItemsProps {
  /** Todos los tipos de servicio (se filtran acá según el tipo de cotización). */
  serviceTypes: QuotationServiceTypeResponse[]
  currencies: CurrencyResponse[]
  igvPercentage: number
  maxRootItems: number
}

export function Step2Items({ serviceTypes, currencies, igvPercentage, maxRootItems }: Step2ItemsProps) {
  const {
    control,
    watch,
    formState: { errors },
  } = useFormContext<WizardFormInput>()
  const { fields, append, remove } = useFieldArray({ control, name: 'items' })

  // Índices de ítems expandidos (acordeón). Al agregar se abre solo el nuevo; el
  // usuario puede abrir varios con click. Por índice (no `field.id`) porque `append`
  // no devuelve el id; se reindexa al eliminar.
  const [expandedIndexes, setExpandedIndexes] = useState<Set<number>>(() => new Set([0]))

  const quotationType = watch('quotationType')
  const currencyId = watch('currencyId')
  const items = watch('items')

  const currencyCode = currencies.find((currency) => currency.id === currencyId)?.code ?? 'PEN'
  // El ítem #1 con tipo Integral activa el "modo Integral" (banner + componentes anidados).
  const isIntegralMode = items?.[0]?.serviceKind === 'INTEGRAL'

  // Regla: TRANSPORTE muestra Servicio + Complementario + Integral; ALQUILER muestra solo ALQUILER.
  // El Integral solo es seleccionable como ítem #1 (lo controla ItemCard según la posición).
  const filteredServiceTypes = serviceTypes.filter((type) =>
    quotationType === 'ALQUILER' ? type.kind === 'ALQUILER' : type.kind !== 'ALQUILER',
  )

  const atMax = fields.length >= maxRootItems
  const subtotal = itemsSubtotal(items ?? [])
  const igvAmount = subtotal * (igvPercentage / 100)
  const grandTotal = subtotal + igvAmount
  // Error de nivel array (ej. "agrega al menos un ítem"), solo el mensaje raíz.
  const rootError = typeof errors.items?.message === 'string' ? errors.items.message : undefined

  function toggle(index: number) {
    setExpandedIndexes((prev) => {
      const next = new Set(prev)
      if (next.has(index)) next.delete(index)
      else next.add(index)
      return next
    })
  }

  function handleAdd() {
    if (atMax) return
    const newIndex = fields.length
    append({
      ...ITEM_DEFAULTS,
      serviceKind: quotationType === 'ALQUILER' ? 'ALQUILER' : 'SERVICIO',
    })
    // Colapsa lo que se estaba editando y abre solo el nuevo (no sobrecargar la pantalla).
    setExpandedIndexes(new Set([newIndex]))
  }

  function handleRemove(index: number) {
    remove(index)
    // Reindexar: los ítems posteriores al eliminado bajan un índice.
    setExpandedIndexes((prev) => {
      const next = new Set<number>()
      prev.forEach((i) => {
        if (i < index) next.add(i)
        else if (i > index) next.add(i - 1)
      })
      return next
    })
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-sm font-semibold uppercase tracking-wide text-fg-muted">
            Ítems de la cotización
          </h2>
          <p className="text-xs text-fg-muted">
            {`Máximo ${maxRootItems} ítems · ${fields.length}/${maxRootItems}`}
          </p>
        </div>
        <Button
          variant="primary"
          onClick={handleAdd}
          disabled={atMax}
          className="gap-2 disabled:cursor-not-allowed disabled:bg-accent-disabled"
        >
          <Plus className="h-4 w-4" aria-hidden="true" />
          Agregar ítem
        </Button>
      </div>

      {isIntegralMode && (
        <div
          role="status"
          className="flex items-start gap-2 rounded-lg border border-warning-border bg-warning-soft px-4 py-3 text-sm text-warning-fg"
        >
          <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>
            <strong className="font-semibold">Modo Integral activado</strong> — el primer ítem es un Servicio
            Integral. Agrega los componentes del paquete (mínimo 2) dentro del ítem #1.
          </span>
        </div>
      )}

      {fields.length === 0 ? (
        <div
          role="alert"
          className="rounded-lg border border-dashed border-border-strong bg-surface-subtle px-6 py-10 text-center text-sm text-fg-muted"
        >
          {rootError ?? 'Agrega al menos un ítem a la cotización.'}
        </div>
      ) : (
        <>
          {rootError && (
            <p role="alert" className="text-sm text-danger">
              {rootError}
            </p>
          )}
          <div className="space-y-3">
            {fields.map((field, index) => (
              <ItemCard
                key={field.id}
                index={index}
                position={index + 1}
                serviceTypes={filteredServiceTypes}
                igvPercentage={igvPercentage}
                currencyCode={currencyCode}
                expanded={expandedIndexes.has(index)}
                onToggle={() => toggle(index)}
                onRemove={() => handleRemove(index)}
              />
            ))}
          </div>
          <div className="ml-auto w-full max-w-xs space-y-1 rounded-xl bg-accent-soft px-5 py-4">
            <div className="flex justify-between text-sm text-fg-body">
              <span>Subtotal</span>
              <span className="font-medium text-fg">{formatCurrency(subtotal, currencyCode)}</span>
            </div>
            <div className="flex justify-between text-sm text-fg-body">
              <span>{`IGV (${igvPercentage}%)`}</span>
              <span className="font-medium text-fg">{formatCurrency(igvAmount, currencyCode)}</span>
            </div>
            <div className="mt-1 flex justify-between border-t border-accent-border pt-2">
              <span className="text-sm font-semibold text-fg-body">Total</span>
              <span className="text-lg font-semibold text-accent-hover">{formatCurrency(grandTotal, currencyCode)}</span>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
