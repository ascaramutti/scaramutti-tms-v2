import { Controller, type Control, type FieldValues, type Path } from 'react-hook-form'
import { cn } from '../utils/cn'
import { FIELD_ERROR, FIELD_LABEL, fieldClasses } from './fieldClasses'

interface DateFieldProps<T extends FieldValues> {
  id: string
  label: string
  name: Path<T>
  control: Control<T>
  /** Fecha mínima (`YYYY-MM-DD`), ej. hoy para no permitir fechas pasadas. */
  min?: string
  /** Fecha máxima (`YYYY-MM-DD`), ej. hoy para no permitir fechas futuras. */
  max?: string
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
  max,
  error,
  disabled,
  labelClassName,
}: DateFieldProps<T>) {
  return (
    <div>
      <label htmlFor={id} className={cn(FIELD_LABEL, labelClassName)}>
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
            max={max}
            disabled={disabled}
            value={field.value ?? ''}
            onChange={field.onChange}
            onBlur={field.onBlur}
            aria-invalid={!!error}
            aria-describedby={error ? `${id}-error` : undefined}
            className={cn('w-full', fieldClasses({ invalid: !!error }))}
          />
        )}
      />
      {error && (
        <p id={`${id}-error`} role="alert" className={FIELD_ERROR}>
          {error}
        </p>
      )}
    </div>
  )
}
