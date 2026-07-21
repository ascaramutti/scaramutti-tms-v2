import { Plus, Trash2 } from 'lucide-react'
import {
  useFieldArray,
  type Control,
  type FieldErrors,
  type UseFormRegister,
} from 'react-hook-form'
import { cn } from '../../../shared/utils/cn'
import type { ProductFormInput } from '../schemas/product.schema'

interface ProductAttributesFieldProps {
  control: Control<ProductFormInput>
  register: UseFormRegister<ProductFormInput>
  errors: FieldErrors<ProductFormInput>['attributes']
  disabled?: boolean
}

const inputClasses =
  'w-full rounded-lg border bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 disabled:bg-slate-50 disabled:text-slate-400'

/**
 * Editor de las características del producto (rosca, viscosidad, medida…). El
 * contrato las guarda como un objeto libre clave-valor; acá se editan como filas
 * para poder agregarlas y quitarlas de a una.
 *
 * Cada input lleva su propio rótulo accesible porque las filas no tienen label
 * visible: los encabezados de la grilla son visuales y no están asociados a los
 * campos.
 */
export function ProductAttributesField({
  control,
  register,
  errors,
  disabled,
}: ProductAttributesFieldProps) {
  const { fields, append, remove } = useFieldArray({ control, name: 'attributes' })

  return (
    <fieldset className="space-y-2">
      <legend className="mb-1.5 text-sm font-medium text-slate-700">Características</legend>

      {fields.length === 0 && (
        <p className="text-sm text-slate-500">
          Sin características. Agrega las que ayuden a identificar el producto (rosca, medida,
          viscosidad).
        </p>
      )}

      {fields.map((field, index) => {
        const keyError = errors?.[index]?.key?.message
        const valueError = errors?.[index]?.value?.message
        return (
          <div key={field.id} className="flex items-start gap-2">
            <div className="flex-1">
              <input
                {...register(`attributes.${index}.key`)}
                aria-label={`Nombre de la característica ${index + 1}`}
                aria-invalid={!!keyError}
                placeholder="Rosca"
                disabled={disabled}
                className={cn(inputClasses, keyError ? 'border-red-300' : 'border-slate-300')}
              />
              {keyError && (
                <p role="alert" className="mt-1 text-xs text-red-600">
                  {keyError}
                </p>
              )}
            </div>
            <div className="flex-1">
              <input
                {...register(`attributes.${index}.value`)}
                aria-label={`Valor de la característica ${index + 1}`}
                aria-invalid={!!valueError}
                placeholder="3/4-16"
                disabled={disabled}
                className={cn(inputClasses, valueError ? 'border-red-300' : 'border-slate-300')}
              />
              {valueError && (
                <p role="alert" className="mt-1 text-xs text-red-600">
                  {valueError}
                </p>
              )}
            </div>
            <button
              type="button"
              onClick={() => remove(index)}
              disabled={disabled}
              aria-label={`Quitar la característica ${index + 1}`}
              className="mt-1 rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            >
              <Trash2 className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>
        )
      })}

      <button
        type="button"
        onClick={() => append({ key: '', value: '' })}
        disabled={disabled}
        className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
      >
        <Plus className="h-4 w-4" aria-hidden="true" />
        Agregar característica
      </button>
    </fieldset>
  )
}
