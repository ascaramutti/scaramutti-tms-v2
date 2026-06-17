import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QuotationStatusBadge } from './QuotationStatusBadge'
import { QUOTATION_STATUS_PRESENTATION } from '../status/quotationStatusPresentation'
import type { QuotationStatus } from '../../../api'

// label esperado + una clase representativa de la variante de cada estado.
const CASES: { status: QuotationStatus; label: string; className: string }[] = [
  { status: 'DRAFT', label: 'Borrador', className: 'bg-slate-100' },
  { status: 'SENT', label: 'Enviada', className: 'bg-blue-100' },
  { status: 'ACCEPTED', label: 'Aceptada', className: 'bg-teal-100' },
  { status: 'REJECTED', label: 'Rechazada', className: 'bg-rose-100' },
  { status: 'EXPIRED', label: 'Vencida', className: 'bg-amber-100' },
]

describe('QuotationStatusBadge', () => {
  it.each(CASES)('estado $status → label "$label" + variante correcta', ({ status, label, className }) => {
    render(<QuotationStatusBadge status={status} />)
    const badge = screen.getByText(label)
    expect(badge).toBeInTheDocument()
    expect(badge).toHaveClass(className)
  })

  it('el label siempre acompaña al color (el color no es el único portador de significado)', () => {
    render(<QuotationStatusBadge status="REJECTED" />)
    // Texto legible presente, no solo la pista de color.
    expect(screen.getByText('Rechazada')).toBeInTheDocument()
  })

  it('deriva del status: ignora el prop redundante isExpired', () => {
    // Una DRAFT marcada isExpired sigue mostrando "Borrador" (la expiración real = estado EXPIRED).
    render(<QuotationStatusBadge status="DRAFT" isExpired />)
    expect(screen.getByText('Borrador')).toBeInTheDocument()
    expect(screen.queryByText('Vencida')).not.toBeInTheDocument()
  })

  it('los labels coinciden con la fuente de verdad', () => {
    for (const { status, label } of CASES) {
      expect(QUOTATION_STATUS_PRESENTATION[status].label).toBe(label)
    }
  })
})
