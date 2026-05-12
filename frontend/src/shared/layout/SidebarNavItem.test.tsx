import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FileText } from 'lucide-react'
import { SidebarNavItem } from './SidebarNavItem'

function renderItem(node: React.ReactNode, initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <ul>{node}</ul>
    </MemoryRouter>,
  )
}

describe('SidebarNavItem', () => {
  it('renderiza NavLink (<a>) cuando se pasa `to`', () => {
    renderItem(<SidebarNavItem icon={FileText} label="Cotizaciones" to="/cotizaciones" />)
    const link = screen.getByRole('link', { name: /cotizaciones/i })
    expect(link).toHaveAttribute('href', '/cotizaciones')
  })

  it('renderiza <span aria-disabled> cuando NO se pasa `to`', () => {
    renderItem(<SidebarNavItem icon={FileText} label="Servicios" />)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    const span = screen.getByText(/servicios/i).closest('span')
    expect(span).toHaveAttribute('aria-disabled', 'true')
    expect(span).toHaveAttribute('title', 'Próximamente')
    // Texto para lectores de pantalla
    expect(screen.getByText('(próximamente)', { exact: false })).toBeInTheDocument()
  })

  it('marca el link activo cuando la ruta coincide', () => {
    renderItem(<SidebarNavItem icon={FileText} label="Inicio" to="/" />, '/')
    const link = screen.getByRole('link', { name: /inicio/i })
    // react-router le pone aria-current="page" al activo
    expect(link).toHaveAttribute('aria-current', 'page')
    expect(link.className).toContain('bg-blue-50')
  })

  it('NO marca activo cuando la ruta no coincide', () => {
    renderItem(<SidebarNavItem icon={FileText} label="Inicio" to="/" />, '/otra-ruta')
    const link = screen.getByRole('link', { name: /inicio/i })
    expect(link).not.toHaveAttribute('aria-current')
  })
})
