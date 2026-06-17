import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QuotationNotesSection } from './QuotationNotesSection'
import { getQuotationResponse } from '../../../test/mocks/handlers/quotations'

describe('QuotationNotesSection', () => {
  it('ambas notas con contenido → muestra los dos bloques + badge "🔒 interno"', () => {
    render(
      <QuotationNotesSection
        quotation={getQuotationResponse({
          clientNote: 'Precio sujeto a variación del combustible.',
          internalNote: 'Margen ajustado por urgencia.',
        })}
      />,
    )
    expect(screen.getByRole('heading', { name: /observaciones/i, level: 2 })).toBeInTheDocument()
    expect(screen.getByText('Precio sujeto a variación del combustible.')).toBeInTheDocument()
    expect(screen.getByText('Margen ajustado por urgencia.')).toBeInTheDocument()
    expect(screen.getByText(/interno/i)).toBeInTheDocument()
  })

  it('solo clientNote → bloque cliente con texto, bloque interno con "—"', () => {
    render(
      <QuotationNotesSection
        quotation={getQuotationResponse({ clientNote: 'Solo para el cliente.', internalNote: null })}
      />,
    )
    expect(screen.getByText('Solo para el cliente.')).toBeInTheDocument()
    // El bloque interno sigue presente (diferenciado) con placeholder "—".
    expect(screen.getByRole('heading', { name: /observaciones internas/i })).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('ambas vacías (null) → la sección NO se renderiza (RN-06)', () => {
    const { container } = render(
      <QuotationNotesSection quotation={getQuotationResponse({ clientNote: null, internalNote: null })} />,
    )
    expect(screen.queryByRole('heading', { name: /observaciones/i })).not.toBeInTheDocument()
    expect(container).toBeEmptyDOMElement()
  })

  it('ambas solo-whitespace → la sección NO se renderiza (RN-06)', () => {
    const { container } = render(
      <QuotationNotesSection quotation={getQuotationResponse({ clientNote: '   ', internalNote: '  ' })} />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('#20 XSS — clientNote con markup se renderiza LITERAL, sin nodo <img> ni ejecución', () => {
    const payload = '<img src=x onerror=alert(1)>'
    const { container } = render(
      <QuotationNotesSection quotation={getQuotationResponse({ clientNote: payload, internalNote: null })} />,
    )
    // El string aparece literal (escape-on-output de JSX) y NO hay un <img> real en el DOM.
    expect(screen.getByText(payload)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('#20 XSS — internalNote con markup también se renderiza literal, sin <img>', () => {
    const payload = '<script>alert(2)</script>'
    const { container } = render(
      <QuotationNotesSection quotation={getQuotationResponse({ clientNote: null, internalNote: payload })} />,
    )
    expect(screen.getByText(payload)).toBeInTheDocument()
    expect(container.querySelector('script')).toBeNull()
  })

  it('conserva el formato del texto libre con whitespace-pre-wrap', () => {
    render(
      <QuotationNotesSection
        quotation={getQuotationResponse({ clientNote: 'línea1\nlínea2', internalNote: null })}
      />,
    )
    const paragraph = screen.getByText(/línea1/)
    expect(paragraph).toHaveClass('whitespace-pre-wrap')
  })
})
