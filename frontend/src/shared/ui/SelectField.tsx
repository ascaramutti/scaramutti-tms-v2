import { Controller, type Control, type FieldValues, type Path } from 'react-hook-form'
import { cn } from '../utils/cn'
import { FIELD_DISABLED, FIELD_ERROR, FIELD_HELPER, FIELD_LABEL, fieldClasses } from './fieldClasses'

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
  /** Ayuda bajo el campo, anunciada por lector de pantalla (como en `TextField`). */
  helperText?: string
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
  helperText,
  disabled,
  labelClassName,
}: SelectFieldProps<T>) {
  const helperId = helperText ? `${id}-helper` : undefined
  const errorId = error ? `${id}-error` : undefined
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
            aria-describedby={[helperId, errorId].filter(Boolean).join(' ') || undefined}
            className={cn('w-full', fieldClasses({ invalid: !!error }), FIELD_DISABLED)}
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
      {helperText && (
        <p id={helperId} className={FIELD_HELPER}>
          {helperText}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className={FIELD_ERROR}>
          {error}
        </p>
      )}
    </div>
  )
}
