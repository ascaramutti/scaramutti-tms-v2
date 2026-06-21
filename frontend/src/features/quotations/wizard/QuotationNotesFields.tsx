import { useFormContext } from 'react-hook-form'
import { Textarea } from '../../../shared/ui/Textarea'
import { stripControlChars } from '../../../shared/utils/sanitizeText'
import type { WizardFormInput } from './quotation-wizard.schema'

const NOTE_MAX_LENGTH = 500

/**
 * Sección "Observaciones" del paso "Observaciones y condiciones": observación para el cliente
 * (va al PDF) e interna (uso interno, nunca cara al cliente — RN-03/ADR-003). El Resumen las
 * muestra read-only vía QuotationNotesSection. Lee del contexto del wizard
 * (`useFormContext`) → `register` para los textarea y `watch` para el contador. Las notas
 * pasan `sanitize={stripControlChars}` (L2) + `maxLength={500}` (L1); la validación zod (L3)
 * vive en el `wizardSchema`. Ambas notas van en card (tono azul); la interna se marca claramente
 * (badge "🔒 interno" + azul más marcado + helper) para que no se confunda de un vistazo — el
 * significado no depende solo del color.
 */
export function QuotationNotesFields() {
  const {
    register,
    watch,
    formState: { errors },
  } = useFormContext<WizardFormInput>()

  const clientNote = watch('clientNote') ?? ''
  const internalNote = watch('internalNote') ?? ''

  return (
    <fieldset className="border-0 p-0">
      <legend className="p-0 text-base font-semibold text-slate-900">Observaciones</legend>
      <div className="mt-3 grid grid-cols-1 gap-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm md:grid-cols-2">
        <div className="rounded-lg border border-sky-200 bg-sky-50/60 p-4">
          <Textarea
            id="quotation-client-note"
            label="Observaciones para el cliente"
            rows={3}
            maxLength={NOTE_MAX_LENGTH}
            showCounter
            value={clientNote}
            placeholder="Ej.: precio sujeto a variación del combustible…"
            helperText="Se incluye en el PDF que ve el cliente."
            error={errors.clientNote?.message}
            register={register('clientNote')}
            sanitize={stripControlChars}
          />
        </div>

        <div className="rounded-lg border border-blue-300 bg-blue-50 p-4">
          <Textarea
            id="quotation-internal-note"
            label="Observaciones internas"
            labelSlot={
              <span className="inline-flex items-center gap-1 rounded-full border border-blue-200 bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
                <span aria-hidden="true">🔒</span>
                interno — no se muestra al cliente
              </span>
            }
            rows={3}
            maxLength={NOTE_MAX_LENGTH}
            showCounter
            value={internalNote}
            placeholder="Ej.: margen ajustado por urgencia; revisar con gerencia…"
            helperText="Solo para uso interno. No aparece en el PDF ni se envía al cliente."
            error={errors.internalNote?.message}
            register={register('internalNote')}
            sanitize={stripControlChars}
          />
        </div>
      </div>
    </fieldset>
  )
}
