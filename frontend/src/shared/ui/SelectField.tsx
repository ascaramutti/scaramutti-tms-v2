import { Controller, type Control, type FieldValues, type Path } from 'react-hook-form'
import { cn } from '../utils/cn'

export interface SelectOption {
  value: number
  label: string
}

interface SelectFieldProps<T extends FieldValues> {
  id: string
  label: string
  name: Path<T>
  control: Control<T>
  options: SelectOption[]
  /** Texto de la opción vacía inicial. Si se omite, no se muestra. */
  placeholder?: string
  error?: string
  disabled?: boolean
  /** Clases extra para el `<label>` (ej. reservar altura en grids multi-columna para alinear). */
  labelClassName?: string
}

/**
 * Select numérico integrado con react-hook-form (Controller). El valor se
 * normaliza a `number` (o `null` si se elige la opción vacía). Mismo lenguaje
 * visual que `TextField` (label + error + focus ring azul).
 */
export function SelectField<T extends FieldValues>({
  id,
  label,
  name,
  control,
  options,
  placeholder,
  error,
  disabled,
  labelClassName,
}: SelectFieldProps<T>) {
  return (
    <div>
      <label htmlFor={id} className={cn('mb-1.5 block text-sm font-medium text-slate-700', labelClassName)}>
        {label}
      </label>
      <Controller
        name={name}
        control={control}
        render={({ field }) => (
          <select
            id={id}
            disabled={disabled}
            value={field.value ?? ''}
            onChange={(event) =>
              field.onChange(event.target.value === '' ? null : Number(event.target.value))
            }
            onBlur={field.onBlur}
            aria-invalid={!!error}
            aria-describedby={error ? `${id}-error` : undefined}
            className={cn(
              'w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500',
              error ? 'border-red-300' : 'border-slate-300',
            )}
          >
            {placeholder && <option value="">{placeholder}</option>}
            {options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        )}
      />
      {error && (
        <p id={`${id}-error`} role="alert" className="mt-1.5 text-sm text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}
