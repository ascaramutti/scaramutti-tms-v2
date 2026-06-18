import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QuotationRejectionReasonSection } from './QuotationRejectionReasonSection'
import { getQuotationResponse } from '../../../test/mocks/handlers/quotations'

describe('QuotationRejectionReasonSection', () => {
  it('REJECTED + motivo → muestra el motivo y el badge "🔒 interno"', () => {
    render(
      <QuotationRejectionReasonSection
        quotation={getQuotationResponse({ status: 'REJECTED', rejectionReason: 'El cliente eligió otro proveedor.' })}
      />,
    )
    expect(screen.getByRole('heading', { name: /motivo del rechazo/i })).toBeInTheDocument()
    expect(screen.getByText('El cliente eligió otro proveedor.')).toBeInTheDocument()
    expect(screen.getByText(/interno/i)).toBeInTheDocument()
  })

  it('REJECTED sin motivo (null) → no renderiza (no rompe con rechazadas viejas)', () => {
    const { container } = render(
      <QuotationRejectionReasonSection quotation={getQuotationResponse({ status: 'REJECTED', rejectionReason: null })} />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('REJECTED con motivo solo-whitespace → no renderiza', () => {
    const { container } = render(
      <QuotationRejectionReasonSection quotation={getQuotationResponse({ status: 'REJECTED', rejectionReason: '   ' })} />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('estado ≠ REJECTED → no renderiza aunque venga un rejectionReason', () => {
    const { container } = render(
      <QuotationRejectionReasonSection
        quotation={getQuotationResponse({ status: 'SENT', rejectionReason: 'colado' })}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('respeta los saltos de línea (whitespace-pre-wrap)', () => {
    render(
      <QuotationRejectionReasonSection
        quotation={getQuotationResponse({ status: 'REJECTED', rejectionReason: 'línea1\nlínea2' })}
      />,
    )
    expect(screen.getByText(/línea1/)).toHaveClass('whitespace-pre-wrap')
  })

  it('XSS — el motivo con markup se renderiza literal, sin nodo real (escape de JSX)', () => {
    const payload = '<img src=x onerror=alert(1)>'
    const { container } = render(
      <QuotationRejectionReasonSection
        quotation={getQuotationResponse({ status: 'REJECTED', rejectionReason: payload })}
      />,
    )
    expect(screen.getByText(payload)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })
})
