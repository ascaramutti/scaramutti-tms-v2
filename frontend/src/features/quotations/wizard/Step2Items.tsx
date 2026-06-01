import { useState } from 'react'
import { useFieldArray, useFormContext } from 'react-hook-form'
import { Plus } from 'lucide-react'
import { formatCurrency } from '../../../shared/utils/formatters'
import { ItemCard } from './ItemCard'
import { itemsSubtotal } from './itemCalc'
import { ITEM_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import type { CurrencyResponse, QuotationServiceTypeResponse } from '../../../api'

interface Step2ItemsProps {
  /** Todos los tipos de servicio (se filtran acá según el tipo de cotización). */
  serviceTypes: QuotationServiceTypeResponse[]
  currencies: CurrencyResponse[]
  igvPercentage: number
  maxRootItems: number
}

const ADD_BUTTON =
  'inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:bg-blue-300'

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

  // Regla: TRANSPORTE muestra TODOS menos ALQUILER (Servicio + Complementario + Integral);
  // ALQUILER muestra solo ALQUILER. El Integral aparece pero deshabilitado (PR2b).
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
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
            Ítems de la cotización
          </h2>
          <p className="text-xs text-slate-500">
            {`Máximo ${maxRootItems} ítems · ${fields.length}/${maxRootItems}`}
          </p>
        </div>
        <button type="button" onClick={handleAdd} disabled={atMax} className={ADD_BUTTON}>
          <Plus className="h-4 w-4" aria-hidden="true" />
          Agregar ítem
        </button>
      </div>

      {fields.length === 0 ? (
        <div
          role="alert"
          className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-6 py-10 text-center text-sm text-slate-500"
        >
          {rootError ?? 'Agrega al menos un ítem a la cotización.'}
        </div>
      ) : (
        <>
          {rootError && (
            <p role="alert" className="text-sm text-red-600">
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
          <div className="ml-auto w-full max-w-xs space-y-1 rounded-xl bg-blue-50 px-5 py-4">
            <div className="flex justify-between text-sm text-slate-600">
              <span>Subtotal</span>
              <span className="font-medium text-slate-900">{formatCurrency(subtotal, currencyCode)}</span>
            </div>
            <div className="flex justify-between text-sm text-slate-600">
              <span>{`IGV (${igvPercentage}%)`}</span>
              <span className="font-medium text-slate-900">{formatCurrency(igvAmount, currencyCode)}</span>
            </div>
            <div className="mt-1 flex justify-between border-t border-blue-200 pt-2">
              <span className="text-sm font-semibold text-slate-700">Total</span>
              <span className="text-lg font-semibold text-blue-700">{formatCurrency(grandTotal, currencyCode)}</span>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
