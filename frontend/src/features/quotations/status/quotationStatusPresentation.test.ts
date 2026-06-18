import { describe, expect, it } from 'vitest'
import type { QuotationStatus } from '../../../api'
import { QUOTATION_STATUS_LABELS } from '../utils/quotationLabels'
import {
  canChangeQuotationStatus,
  QUOTATION_STATUS_PRESENTATION,
} from './quotationStatusPresentation'

const ALL_STATUSES: QuotationStatus[] = ['DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED']

describe('QUOTATION_STATUS_PRESENTATION', () => {
  it('cubre los 5 estados del contrato', () => {
    expect(Object.keys(QUOTATION_STATUS_PRESENTATION).sort()).toEqual([...ALL_STATUSES].sort())
  })

  it('cada estado tiene label y badgeVariant', () => {
    for (const status of ALL_STATUSES) {
      const presentation = QUOTATION_STATUS_PRESENTATION[status]
      expect(presentation.label).toBeTruthy()
      expect(presentation.badgeVariant).toBeTruthy()
    }
  })

  it('DRAFT tiene 1 acción (Enviada → SENT, sin motivo)', () => {
    const { actions } = QUOTATION_STATUS_PRESENTATION.DRAFT
    expect(actions).toHaveLength(1)
    expect(actions[0]).toMatchObject({ target: 'SENT', label: 'Enviada', requiresReason: false })
  })

  it('SENT tiene 2 acciones (Aceptada → ACCEPTED, Rechazada → REJECTED)', () => {
    const { actions } = QUOTATION_STATUS_PRESENTATION.SENT
    expect(actions.map((a) => a.target)).toEqual(['ACCEPTED', 'REJECTED'])
  })

  it('los estados terminales no tienen acciones', () => {
    expect(QUOTATION_STATUS_PRESENTATION.ACCEPTED.actions).toHaveLength(0)
    expect(QUOTATION_STATUS_PRESENTATION.REJECTED.actions).toHaveLength(0)
    expect(QUOTATION_STATUS_PRESENTATION.EXPIRED.actions).toHaveLength(0)
  })

  it('solo el Rechazada (REJECTED) exige motivo', () => {
    const actionsRequiringReason = ALL_STATUSES.flatMap((status) =>
      QUOTATION_STATUS_PRESENTATION[status].actions.filter((a) => a.requiresReason),
    )
    expect(actionsRequiringReason).toHaveLength(1)
    expect(actionsRequiringReason[0].target).toBe('REJECTED')
  })

  it('ninguna acción apunta a DRAFT ni EXPIRED (no son destinos de usuario)', () => {
    const allTargets = ALL_STATUSES.flatMap((status) =>
      QUOTATION_STATUS_PRESENTATION[status].actions.map((a) => a.target),
    )
    expect(allTargets).not.toContain('DRAFT')
    expect(allTargets).not.toContain('EXPIRED')
  })

  it('los labels derivados (QUOTATION_STATUS_LABELS) coinciden con el mapa', () => {
    for (const status of ALL_STATUSES) {
      expect(QUOTATION_STATUS_LABELS[status]).toBe(QUOTATION_STATUS_PRESENTATION[status].label)
    }
    expect(QUOTATION_STATUS_LABELS.DRAFT).toBe('Borrador')
    expect(QUOTATION_STATUS_LABELS.EXPIRED).toBe('Vencida')
  })
})

describe('canChangeQuotationStatus', () => {
  it('permite a admin, sales y las gerencias', () => {
    expect(canChangeQuotationStatus('admin')).toBe(true)
    expect(canChangeQuotationStatus('sales')).toBe(true)
    expect(canChangeQuotationStatus('general_manager')).toBe(true)
    expect(canChangeQuotationStatus('operations_manager')).toBe(true)
  })

  it('no permite a dispatcher', () => {
    expect(canChangeQuotationStatus('dispatcher')).toBe(false)
  })

  it('no permite a un rol ausente (undefined)', () => {
    expect(canChangeQuotationStatus(undefined)).toBe(false)
  })
})
