interface WizardNavProps {
  isFirst: boolean
  isLast: boolean
  onBack: () => void
  onNext: () => void
  onCancel: () => void
}

const PRIMARY =
  'inline-flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:bg-blue-300'
const SECONDARY =
  'inline-flex items-center rounded-lg border border-slate-300 bg-white px-5 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500'

/**
 * Navegación del wizard. En el primer step, el botón izquierdo es "Cancelar".
 * En el último step (Resumen) "Siguiente" queda deshabilitado hasta que exista
 * el submit (`POST /quotations`, PR 3).
 */
export function WizardNav({ isFirst, isLast, onBack, onNext, onCancel }: WizardNavProps) {
  return (
    <div className="flex items-center justify-between border-t border-slate-200 pt-4">
      <button type="button" onClick={isFirst ? onCancel : onBack} className={SECONDARY}>
        {isFirst ? 'Cancelar' : 'Atrás'}
      </button>
      <button type="button" onClick={onNext} disabled={isLast} className={PRIMARY}>
        Siguiente
      </button>
    </div>
  )
}
