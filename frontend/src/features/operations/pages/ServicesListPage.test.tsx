import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { axe } from 'vitest-axe'
import { ServicesListPage } from './ServicesListPage'

function renderServicios() {
  return render(
    <MemoryRouter initialEntries={['/cotizaciones/operaciones']}>
      <ServicesListPage />
    </MemoryRouter>,
  )
}

describe('ServicesListPage', () => {
  it('anuncia el módulo con un único h1', () => {
    renderServicios()
    const encabezados = screen.getAllByRole('heading', { level: 1 })
    expect(encabezados).toHaveLength(1)
    // Por nombre accesible exacto: `toHaveTextContent` es por subcadena y dejaba
    // pasar cualquier título que contuviera la palabra.
    expect(encabezados[0]).toHaveAccessibleName('Servicios')
  })

  it('le dice al usuario qué va a poder hacer acá', () => {
    // El texto es todo lo que esta pantalla entrega: si se vacía o se cambia por
    // cualquier cosa, nadie más lo nota.
    renderServicios()
    expect(screen.getByText(/control de viajes/i)).toBeInTheDocument()
    expect(screen.getByText(/viajes registrados/i)).toBeInTheDocument()
    expect(screen.getByText(/en preparación/i)).toBeInTheDocument()
  })

  it('avisa que el listado todavía no está y no promete datos', () => {
    renderServicios()
    expect(screen.getByText(/todavía no está disponible/i)).toBeInTheDocument()
    // Sin tabla ni indicadores: el placeholder no debe simular una pantalla que
    // no existe. Cuando llegue el listado real, esta expectativa se cae y es la
    // señal de que hay que reemplazar el test entero.
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })


  it('no tiene violaciones de accesibilidad', async () => {
    const { container } = renderServicios()
    expect(await axe(container)).toHaveNoViolations()
  })
})
