import { useFieldArray, useFormContext } from 'react-hook-form'
import { Plus } from 'lucide-react'
import { ChildItemCard } from './ChildItemCard'
import { CHILD_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import type { QuotationServiceTypeResponse } from '../../../api'
import { Card } from '../../../shared/ui/Card'

interface IntegralComponentsProps {
  /** Índice del ítem Integral (padre) en `items`. */
  parentIndex: number
  /** Tipos de servicio del tipo de cotización; se filtran acá a SERVICIO + COMPLEMENTARIO. */
  serviceTypes: QuotationServiceTypeResponse[]
  currencyCode: string
}

// El borde va con el acento pleno y no con el suave del marco de una caja: este botón tiene
// relleno propio, pero su relleno NO lo separa de la tarjeta que lo contiene (1.09), así que su
// ÚNICO límite es el borde. Con el suave daba 1.67 contra su propio relleno; con este, 4.82. Es
// el mismo criterio con el que la tarjeta seleccionada de la elección de tipo lleva `border-accent`.
const ADD_COMPONENT =
  'inline-flex items-center gap-1.5 rounded-lg border border-accent bg-accent-soft px-3 py-1.5 text-sm font-medium text-accent-hover hover:bg-accent-soft-strong focus:outline-none focus:ring-2 focus:ring-focus'

/**
 * Sección "Componentes del Servicio Integral": lista anidada (`useFieldArray` sobre
 * `items[parentIndex].components`) con su propio "Agregar componente". Los componentes
 * solo pueden ser de transporte (SERVICIO) o complementarios.
 */
export function IntegralComponents({ parentIndex, serviceTypes, currencyCode }: IntegralComponentsProps) {
  const { control, watch } = useFormContext<WizardFormInput>()
  const { fields, append, remove } = useFieldArray({ control, name: `items.${parentIndex}.components` })

  const componentServiceTypes = serviceTypes.filter(
    (type) => type.kind === 'SERVICIO' || type.kind === 'COMPLEMENTARIO',
  )

  // Guía de composición EN VIVO: se mantiene/actualiza con cada cambio mientras el usuario
  // arma el paquete (no espera al "Siguiente"). Para los kinds solo cuentan los componentes
  // con tipo elegido. El zod superRefine revalida lo mismo para el gate del submit (PR3).
  const components = watch(`items.${parentIndex}.components`) ?? []
  const typed = components.filter((component) => component?.serviceTypeId)
  let compositionHint: string | undefined
  if (components.length < 2) {
    compositionHint = 'El Servicio Integral requiere mínimo 2 componentes.'
  } else if (!typed.some((component) => component.serviceKind === 'SERVICIO')) {
    compositionHint = 'Agrega al menos un componente de transporte.'
  } else if (!typed.some((component) => component.serviceKind === 'COMPLEMENTARIO')) {
    compositionHint = 'Agrega al menos un componente complementario.'
  }

  return (
    <Card padding="md" elevated={false}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold uppercase tracking-wide text-fg-body">
            Componentes del Servicio Integral
          </h3>
          <p className="text-xs text-fg-muted" aria-live="polite">
            {`Mínimo 2 componentes · ${components.length} agregado${components.length === 1 ? '' : 's'}`}
          </p>
        </div>
        <button type="button" onClick={() => append(CHILD_DEFAULTS)} className={ADD_COMPONENT}>
          <Plus className="h-4 w-4" aria-hidden="true" />
          Agregar componente
        </button>
      </div>

      {components.length > 0 && compositionHint && (
        <p aria-live="polite" className="mt-2 text-sm text-danger">
          {compositionHint}
        </p>
      )}

      {components.length === 0 ? (
        <p className="mt-3 rounded-lg border border-dashed border-border-strong bg-surface-subtle px-4 py-6 text-center text-sm text-fg-muted">
          Agrega los componentes del paquete (al menos uno de transporte y uno complementario).
        </p>
      ) : (
        <div className="mt-3 space-y-3">
          {fields.map((field, index) => (
            <ChildItemCard
              key={field.id}
              parentIndex={parentIndex}
              index={index}
              position={index + 1}
              serviceTypes={componentServiceTypes}
              currencyCode={currencyCode}
              onRemove={() => remove(index)}
            />
          ))}
        </div>
      )}
    </Card>
  )
}
