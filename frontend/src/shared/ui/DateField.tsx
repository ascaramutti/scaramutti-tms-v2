import { Controller, type Control, type FieldValues, type Path } from 'react-hook-form'
import { cn } from '../utils/cn'

interface DateFieldProps<T extends FieldValues> {
  id: string
  label: string
  name: Path<T>
  control: Control<T>
  /** Fecha mínima (`YYYY-MM-DD`), ej. hoy para no permitir fechas pasadas. */
  min?: string
  error?: string
  disabled?: boolean
  /** Clases extra para el `<label>` (ej. reservar altura en grids multi-columna para alinear). */
  labelClassName?: string
}

/**
 * Input de fecha (`type="date"`) integrado con react-hook-form (Controller).
 * Mismo lenguaje visual que `TextField`.
 */
export function DateField<T extends FieldValues>({
  id,
  label,
  name,
  control,
  min,
  error,
  disabled,
  labelClassName,
}: DateFieldProps<T>) {
  return (
    <div>
      <label htmlFor={id} className={cn('mb-1.5 block text-sm font-medium text-slate-700', labelClassName)}>
        {label}
      </label>
      <Controller
        name={name}
        control={control}
        render={({ field }) => (
          <input
            id={id}
            type="date"
            min={min}
            disabled={disabled}
            value={field.value ?? ''}
            onChange={field.onChange}
            onBlur={field.onBlur}
            aria-invalid={!!error}
            aria-describedby={error ? `${id}-error` : undefined}
            className={cn(
              'w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500',
              error ? 'border-red-300' : 'border-slate-300',
            )}
          />
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
