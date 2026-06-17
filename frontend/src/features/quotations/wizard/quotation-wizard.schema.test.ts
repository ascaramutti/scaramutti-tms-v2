import { describe, expect, it } from 'vitest'
import { ITEM_DEFAULTS, WIZARD_DEFAULTS, wizardSchema, type WizardFormInput } from './quotation-wizard.schema'

/** Form válido base con un ítem; se sobreescriben solo las notas por test. */
function formWithNotes(clientNote: string, internalNote = ''): WizardFormInput {
  return {
    ...WIZARD_DEFAULTS,
    quotationType: 'TRANSPORTE',
    clientId: 7,
    contactName: 'Juan Pérez',
    origin: 'Lima',
    destination: 'Cusco',
    currencyId: 2,
    validityDays: 15,
    items: [
      { ...ITEM_DEFAULTS, serviceTypeId: 3, serviceKind: 'SERVICIO', cargoTypeId: 4, weightKg: 100, unitPrice: 500 },
    ],
    clientNote,
    internalNote,
  }
}

/** Devuelve el primer mensaje de error de un campo de nota, o undefined si válido. */
function noteError(form: WizardFormInput, field: 'clientNote' | 'internalNote'): string | undefined {
  const result = wizardSchema.safeParse(form)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path[0] === field)?.message
}

describe('wizardSchema — observaciones (clientNote / internalNote)', () => {
  it('notas vacías ("") → válido (son opcionales)', () => {
    expect(wizardSchema.safeParse(formWithNotes('', '')).success).toBe(true)
  })

  it('nota de 500 chars → válido; de 501 → inválido ("Máximo 500 caracteres.")', () => {
    expect(wizardSchema.safeParse(formWithNotes('a'.repeat(500))).success).toBe(true)
    expect(noteError(formWithNotes('a'.repeat(501)), 'clientNote')).toBe('Máximo 500 caracteres.')
  })

  it('texto con acentos/ñ/puntuación/saltos de línea (≤500) → válido (texto libre, RN-04)', () => {
    const form = formWithNotes('Atención: tarifa por tonelada.\nIncluye peaje — ¿ñ? ¡sí!')
    expect(wizardSchema.safeParse(form).success).toBe(true)
  })

  it('#4 — texto con `< >` → válido (no se bloquean, RN-04)', () => {
    const form = formWithNotes('peso < 25t y tarifa > USD 500')
    expect(wizardSchema.safeParse(form).success).toBe(true)
  })

  it('solo whitespace → válido en schema (el mapper lo colapsa a null)', () => {
    expect(wizardSchema.safeParse(formWithNotes('   ')).success).toBe(true)
  })

  it('L3 — valor con caracter de control (\\x00) → inválido ("No se permiten caracteres de control.")', () => {
    expect(noteError(formWithNotes('texto\x00malo'), 'clientNote')).toBe('No se permiten caracteres de control.')
    // También en la interna (mismo schema).
    expect(noteError(formWithNotes('', 'malo\x07'), 'internalNote')).toBe('No se permiten caracteres de control.')
  })

  it('L3 — valor con tab y salto de línea (\\t \\n) → válido (se conservan)', () => {
    const form = formWithNotes('línea1\tcol2\nlínea2')
    expect(wizardSchema.safeParse(form).success).toBe(true)
  })
})
