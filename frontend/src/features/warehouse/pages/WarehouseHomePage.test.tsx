import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { WarehouseHomePage } from './WarehouseHomePage'

describe('WarehouseHomePage', () => {
  it('presenta el módulo con un único h1', () => {
    render(<WarehouseHomePage />)
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Almacén')
  })

  it('avisa que las pantallas todavía no están disponibles', () => {
    render(<WarehouseHomePage />)
    expect(screen.getByText(/se está construyendo/i)).toBeInTheDocument()
  })
})
