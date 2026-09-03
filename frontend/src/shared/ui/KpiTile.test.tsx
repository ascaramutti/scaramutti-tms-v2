import { Package } from 'lucide-react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { KpiTile } from './KpiTile'

const clases = (el: HTMLElement) => new Set(el.className.split(/\s+/).filter(Boolean))

/**
 * Lo que estas pruebas cuidan es la EXTRACCIÓN: el tile reemplaza al cuerpo que los dos
 * strips de KPI escribían por su cuenta, y lo que puede romperse no es el aspecto sino las
 * tres cosas que ese cuerpo decidía y que ahora decide un solo lugar: que el cero se muestre,
 * que el tile clickeable siga siendo un botón, y que en carga no se dibuje un valor viejo.
 */
describe('KpiTile · el valor', () => {
  it('el cero es un dato y se muestra', () => {
    render(<KpiTile label="Productos activos" value={0} />)
    expect(screen.getByText('0')).toBeInTheDocument()
  })

  it('sin dato dibuja un guion, que es otra cosa que un cero', () => {
    render(<KpiTile label="Productos activos" />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('acepta un valor compuesto, que es lo que usan los tiles de razón', () => {
    render(
      <KpiTile
        label="Conductores en ruta"
        value={
          <>
            3<span> de 5 de alta</span>
          </>
        }
      />,
    )
    expect(screen.getByText(/de 5 de alta/)).toBeInTheDocument()
  })

  it('el valor destacado va en ámbar y el normal no', () => {
    const { rerender } = render(<KpiTile label="Con stock bajo" value={7} highlight />)
    expect(clases(screen.getByText('7')).has('text-warning')).toBe(true)
    rerender(<KpiTile label="Con stock bajo" value={7} />)
    expect(clases(screen.getByText('7')).has('text-fg')).toBe(true)
  })
})

describe('KpiTile · la carga', () => {
  it('en carga anuncia y NO deja un número viejo en pantalla', () => {
    render(<KpiTile label="Productos activos" value={9} isLoading />)
    expect(screen.getByRole('status', { name: 'Cargando indicadores' })).toBeInTheDocument()
    expect(screen.queryByText('9')).not.toBeInTheDocument()
  })
})

describe('KpiTile · las dos formas', () => {
  it('por defecto no es un control: no tiene rol de botón', () => {
    render(<KpiTile label="Productos activos" value={1} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  /**
   * El tile clickeable tiene que seguir siendo un `<button>` y no un `<div>` con `onClick`:
   * con un div pierde el foco y el teclado, y es el único KPI accionable de la aplicación.
   */
  it('el clickeable es un botón de verdad, con teclado y con sus props propias', async () => {
    const onClick = vi.fn()
    render(
      <KpiTile
        as="button"
        type="button"
        onClick={onClick}
        aria-pressed={true}
        aria-label="Con stock bajo: 7. Filtrar: solo stock bajo"
        label="Con stock bajo"
        value={7}
      />,
    )
    const boton = screen.getByRole('button', { name: 'Con stock bajo: 7. Filtrar: solo stock bajo' })
    expect(boton).toHaveAttribute('aria-pressed', 'true')
    boton.focus()
    await userEvent.keyboard('{Enter}')
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})

describe('KpiTile · el ícono', () => {
  // El `aria-hidden` lo pone también `lucide-react` por su cuenta cuando el ícono no trae
  // ninguna prop de accesibilidad, así que este caso describe el DOM que sale, no una línea
  // nuestra: sacarla del componente no lo hace fallar (medido con la tabla de mutaciones).
  // Lo que sí es nuestro, y lo que el caso protege, es que el ícono pueda faltar.
  it('es opcional y, cuando está, no lo lee el lector de pantalla', () => {
    const { container, rerender } = render(
      <KpiTile icon={Package} label="Productos activos" value={1} />,
    )
    const svg = container.querySelector('svg')
    expect(svg).toHaveAttribute('aria-hidden', 'true')
    rerender(<KpiTile label="Productos activos" value={1} />)
    expect(container.querySelector('svg')).toBeNull()
  })
})
