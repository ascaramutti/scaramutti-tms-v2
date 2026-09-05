import type { ChangeEvent, ReactNode } from 'react'
import type { UseFormRegisterReturn } from 'react-hook-form'
import { cn } from '../utils/cn'
import { FIELD_FOCUS_INVALID, FIELD_PLACEHOLDER, fieldClasses } from './fieldClasses'

interface TextareaProps {
  /** Id del textarea. Base de los ids de helper/contador/error. */
  id: string
  /** Label visible y asociado al textarea vía htmlFor. */
  label: string
  /** Filas visibles. Default `3`. */
  rows?: number
  /** Tope blando de entrada (teclado + paste). La validación real es zod + backend. */
  maxLength?: number
  placeholder?: string
  /** Texto de ayuda bajo el label (propósito del campo). */
  helperText?: string
  /** Mensaje de error inline (suele venir de `formState.errors[field].message`). */
  error?: string
  /** Muestra el contador `value.length/maxLength`. Requiere `maxLength` y `value`. */
  showCounter?: boolean
  /** Valor actual, solo para el contador (vía `watch`). El valor real lo maneja `register`. */
  value?: string
  /** Registro de react-hook-form: `register('fieldName')`. */
  register: UseFormRegisterReturn
  /** Bloquea la escritura, p. ej. mientras el formulario se está enviando. */
  disabled?: boolean
  /** Contenido extra junto al label (ej. el badge "🔒 interno"). */
  labelSlot?: ReactNode
  /** Saneo aplicado en `onChange` antes de pasar el valor a RHF (ej. `stripControlChars`). */
  sanitize?: (value: string) => string
}

/**
 * Textarea reusable. Encapsula label + textarea + helper + contador (x/max) + error con a11y
 * completa (`aria-invalid`, `aria-describedby` encadenado, `role="alert"`, contador `aria-live`).
 * Espeja la API de `TextField`. La prop `sanitize` permite limpiar la entrada (control-chars)
 * sin acoplar el componente a una feature concreta.
 */
export function Textarea({
  id,
  label,
  rows = 3,
  maxLength,
  placeholder,
  helperText,
  error,
  showCounter,
  value,
  register,
  labelSlot,
  sanitize,
  disabled,
}: TextareaProps) {
  const helperId = helperText ? `${id}-helper` : undefined
  const counterId = showCounter ? `${id}-counter` : undefined
  const errorId = error ? `${id}-error` : undefined
  // El `labelSlot` (ej. badge "🔒 interno") va FUERA del <label> para no contaminar el nombre
  // accesible del control (que queda = `label`); su texto se expone vía aria-describedby.
  const labelSlotId = labelSlot ? `${id}-labelslot` : undefined
  const describedBy =
    [labelSlotId, helperId, counterId, errorId].filter(Boolean).join(' ') || undefined

  const currentLength = value?.length ?? 0
  const overLimit = maxLength != null && currentLength > maxLength

  // Envuelve el onChange de RHF: sanea el valor antes de que llegue al estado del form.
  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    if (sanitize) {
      const cleaned = sanitize(event.target.value)
      if (cleaned !== event.target.value) {
        event.target.value = cleaned
      }
    }
    return register.onChange(event)
  }

  return (
    <div>
      <div className="mb-1.5 flex flex-wrap items-center gap-2">
        <label htmlFor={id} className="text-sm font-medium text-fg-body">
          {label}
        </label>
        {labelSlot && <span id={labelSlotId}>{labelSlot}</span>}
      </div>
      <textarea
        id={id}
        rows={rows}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={disabled}
        aria-invalid={!!error}
        aria-describedby={describedBy}
        className={cn(
          'w-full',
          fieldClasses({ invalid: !!error }),
          FIELD_PLACEHOLDER,
          'resize-none',
          error && FIELD_FOCUS_INVALID,
          // El deshabilitado de acá NO usa los prefijos `disabled:` como el campo de texto:
          // se aplica por condición, y con `text-fg-muted` en vez de `text-fg-subtle`. Se
          // deja tal cual: unificarlo cambiaría el gris del texto deshabilitado.
          disabled && 'cursor-not-allowed bg-surface-subtle text-fg-muted',
        )}
        {...register}
        onChange={handleChange}
      />
      <div className="mt-1.5 flex items-start justify-between gap-3">
        <div className="flex-1">
          {helperText && (
            <p id={helperId} className="text-xs text-fg-muted">
              {helperText}
            </p>
          )}
          {error && (
            <p id={errorId} role="alert" className="text-sm text-danger">
              {error}
            </p>
          )}
        </div>
        {showCounter && maxLength != null && (
          <span
            id={counterId}
            aria-live="polite"
            className={cn('shrink-0 text-xs tabular-nums', overLimit ? 'text-danger' : 'text-fg-subtle')}
          >
            {currentLength}/{maxLength}
          </span>
        )}
      </div>
    </div>
  )
}
