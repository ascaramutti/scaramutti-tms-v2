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
    // Y que se VEA apagado, que es lo que el lector de pantalla no cubre. La opacidad no está
    // de adorno: el tono apagado y el navegable se separan poco, y esa distancia depende de dos
    // tokens que se mueven por otro motivo. Sin esta línea, sacarla no rompe nada.
    expect(span?.className.split(/\s+/)).toContain('opacity-60')
  })

  it('marca el link activo cuando la ruta coincide', () => {
    renderItem(<SidebarNavItem icon={FileText} label="Inicio" to="/" />, '/')
    const link = screen.getByRole('link', { name: /inicio/i })
    // react-router le pone aria-current="page" al activo
    expect(link).toHaveAttribute('aria-current', 'page')
    expect(link.className).toContain('bg-accent-soft')
  })

  it('NO marca activo cuando la ruta no coincide', () => {
    renderItem(<SidebarNavItem icon={FileText} label="Inicio" to="/" />, '/otra-ruta')
    const link = screen.getByRole('link', { name: /inicio/i })
    expect(link).not.toHaveAttribute('aria-current')
  })

  it('marca activo en las rutas hijas (prefijo por segmento)', () => {
    renderItem(
      <SidebarNavItem icon={FileText} label="Clientes" to="/clientes" />,
      '/clientes/123',
    )
    expect(screen.getByRole('link', { name: /clientes/i })).toHaveAttribute('aria-current', 'page')
  })

  it('el prefijo respeta el borde de segmento (/clientesX no es /clientes)', () => {
    renderItem(<SidebarNavItem icon={FileText} label="Clientes" to="/clientes" />, '/clientesX')
    expect(screen.getByRole('link', { name: /clientes/i })).not.toHaveAttribute('aria-current')
  })

  it('con activeWhen, el resaltado y el aria-current salen del mismo criterio', () => {
    // Un item de otro módulo anidado bajo el mismo prefijo: el matcher custom
    // lo excluye, así que no debe quedar ni resaltado ni anunciado como actual.
    renderItem(
      <SidebarNavItem
        icon={FileText}
        label="Cotizaciones"
        to="/cotizaciones"
        activeWhen={(pathname) => !pathname.startsWith('/cotizaciones/almacen')}
      />,
      '/cotizaciones/almacen',
    )
    const link = screen.getByRole('link', { name: /cotizaciones/i })
    expect(link).not.toHaveAttribute('aria-current')
    expect(link.className).not.toContain('bg-accent-soft')
  })
})
