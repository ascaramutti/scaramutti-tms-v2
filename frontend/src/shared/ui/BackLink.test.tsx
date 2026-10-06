import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BackLink } from './BackLink'

describe('BackLink', () => {
  it('es un enlace a su destino', () => {
    render(
      <MemoryRouter>
        <BackLink to="/lista">Volver a la lista</BackLink>
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: 'Volver a la lista' })).toHaveAttribute('href', '/lista')
  })

  it('deshabilitado no navega y se anuncia como deshabilitado', () => {
    render(
      <MemoryRouter>
        <BackLink to="/lista" disabled>
          Volver a la lista
        </BackLink>
      </MemoryRouter>,
    )

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    const enlace = screen.getByText(/Volver a la lista/)
    expect(enlace).toHaveAttribute('aria-disabled', 'true')
    expect(enlace).toHaveTextContent('Volver a la lista (no disponible)')
    expect(enlace).toHaveClass('inline-flex', 'opacity-60', 'cursor-not-allowed')
    expect(enlace.querySelector('svg[aria-hidden="true"]')).not.toBeNull()
  })
})
