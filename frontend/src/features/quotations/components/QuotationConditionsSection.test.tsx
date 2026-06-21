import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QuotationConditionsSection } from './QuotationConditionsSection'

describe('QuotationConditionsSection', () => {
  it('lista las condiciones ordenadas por displayOrder', () => {
    render(
      <QuotationConditionsSection
        conditions={[
          { id: 2, text: 'Condición B', displayOrder: 2 },
          { id: 1, text: 'Condición A', displayOrder: 1 },
        ]}
      />,
    )
    const a = screen.getByText('Condición A')
    const b = screen.getByText('Condición B')
    // A (displayOrder 1) aparece antes que B (displayOrder 2) en el DOM.
    expect(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('muestra el encabezado "Condiciones generales"', () => {
    render(<QuotationConditionsSection conditions={[{ id: 1, text: 'X', displayOrder: 1 }]} />)
    expect(screen.getByRole('heading', { name: /condiciones generales/i })).toBeInTheDocument()
  })

  it('no renderiza nada si no hay condiciones', () => {
    const { container } = render(<QuotationConditionsSection conditions={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renderiza el texto literal, sin HTML inyectado', () => {
    const payload = '<img src=x onerror=alert(1)>'
    const { container } = render(
      <QuotationConditionsSection conditions={[{ id: 1, text: payload, displayOrder: 1 }]} />,
    )
    expect(screen.getByText(payload)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })
})
