import { Controller, type Control, type FieldValues, type Path } from 'react-hook-form'
import { cn } from '../utils/cn'
import { FIELD_ERROR, FIELD_LABEL, fieldClasses } from './fieldClasses'

export interface SelectOption {
  /** Número (un id) o texto (un nombre de sistema o un valor de enum). */
  value: number | string
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
 * Select integrado con react-hook-form (Controller). Devuelve el valor de la
 * opción con su tipo (número o texto), o `null` con la opción vacía. Mismo
 * lenguaje visual que `TextField` (label + error + focus ring azul).
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
      <label htmlFor={id} className={cn(FIELD_LABEL, labelClassName)}>
        {label}
      </label>
      <Controller
        name={name}
        control={control}
        render={({ field }) => (
          <select
            id={id}
            disabled={disabled}
            ref={field.ref}
            value={field.value ?? ''}
            onChange={(event) =>
              field.onChange(
                options.find((option) => String(option.value) === event.target.value)?.value ?? null,
              )
            }
            onBlur={field.onBlur}
            aria-invalid={!!error}
            aria-describedby={error ? `${id}-error` : undefined}
            className={cn('w-full', fieldClasses({ invalid: !!error }))}
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
        <p id={`${id}-error`} role="alert" className={FIELD_ERROR}>
          {error}
        </p>
      )}
    </div>
  )
}
