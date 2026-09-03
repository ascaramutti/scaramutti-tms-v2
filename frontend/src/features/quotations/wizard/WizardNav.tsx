import { Spinner } from '../../../shared/ui/Spinner'

interface WizardNavProps {
  isFirst: boolean
  isLast: boolean
  onBack: () => void
  onNext: () => void
  onCancel: () => void
  /** Guardar la cotización (solo se usa en el último paso). */
  onSubmit: () => void
  /** Envío en curso: deshabilita el botón de guardar (anti doble-click). */
  isSubmitting: boolean
  /** Label del botón de guardar en el último paso (default "Guardar cotización"). */
  submitLabel?: string
}

const PRIMARY =
  'inline-flex items-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-sm font-medium text-on-solid shadow-sm hover:bg-accent-hover focus:outline-none focus:ring-2 focus:ring-focus focus:ring-offset-2 disabled:cursor-not-allowed disabled:bg-blue-300'
const SECONDARY =
  'inline-flex items-center rounded-lg border border-border-strong bg-surface px-5 py-2.5 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus:ring-2 focus:ring-focus'

/**
 * Navegación del wizard. En el primer paso el botón izquierdo es "Cancelar"; en el
 * último (Resumen) el botón derecho pasa de "Siguiente" a "Guardar cotización", que
 * dispara el submit y se deshabilita mientras el guardado está en curso.
 */
export function WizardNav({
  isFirst,
  isLast,
  onBack,
  onNext,
  onCancel,
  onSubmit,
  isSubmitting,
  submitLabel = 'Guardar cotización',
}: WizardNavProps) {
  return (
    <div className="flex items-center justify-between border-t border-border pt-4">
      <button type="button" onClick={isFirst ? onCancel : onBack} className={SECONDARY}>
        {isFirst ? 'Cancelar' : 'Atrás'}
      </button>
      {isLast ? (
        <button type="button" onClick={onSubmit} disabled={isSubmitting} className={PRIMARY}>
          {isSubmitting ? (
            <>
              <Spinner size={16} label="Guardando cotización" />
              <span aria-hidden="true">Guardando…</span>
            </>
          ) : (
            submitLabel
          )}
        </button>
      ) : (
        <button type="button" onClick={onNext} className={PRIMARY}>
          Siguiente
        </button>
      )}
    </div>
  )
}
