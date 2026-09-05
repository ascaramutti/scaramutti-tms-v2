import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Card } from './Card'
import { cardClasses } from './cardClasses'

/**
 * Lo que estas pruebas cuidan es la MUDANZA, no la tarjeta: el componente reemplaza la
 * forma que 57 sitios escribían a mano, y lo que puede romperse no es el aspecto sino el
 * elemento y lo que cada sitio traía consigo.
 *
 * Por eso el conjunto de clases se fija contra un literal escrito acá, y no derivado de
 * `cardClasses`: si las dos puntas salieran de la misma fuente, ninguna mutación podría
 * hacerlas fallar.
 */
const ESPERADAS = {
  lg: 'rounded-xl border border-border bg-surface p-5 shadow-sm',
  md: 'rounded-xl border border-border bg-surface p-4 shadow-sm',
  none: 'rounded-xl border border-border bg-surface shadow-sm',
} as const

const clases = (el: HTMLElement) => new Set(el.className.split(/\s+/).filter(Boolean))

describe('Card · las clases son las de la forma que reemplaza', () => {
  it.each(Object.entries(ESPERADAS))('el padding %s produce el mismo conjunto', (padding, esperado) => {
    render(
      <Card padding={padding as keyof typeof ESPERADAS} data-testid="c">
        x
      </Card>,
    )
    expect(clases(screen.getByTestId('c'))).toEqual(new Set(esperado.split(/\s+/)))
  })

  it('el padding por omisión es el grande', () => {
    render(<Card data-testid="c">x</Card>)
    expect(clases(screen.getByTestId('c'))).toEqual(new Set(ESPERADAS.lg.split(/\s+/)))
  })

  it('sin elevación no trae la clase de sombra, y conserva todo lo demás', () => {
    // Tres de las 28 tarjetas no la llevan: son los paneles internos del asistente, que ya
    // viven dentro de otra tarjeta.
    render(
      <Card elevated={false} data-testid="c">
        x
      </Card>,
    )
    const c = clases(screen.getByTestId('c'))
    expect([...c].some((x) => x.startsWith('shadow-'))).toBe(false)
    expect(c.has('border-border')).toBe(true)
    expect(c.has('bg-surface')).toBe(true)
  })

  it('no escribe ningún color crudo: el fondo y el borde salen de tokens', () => {
    render(<Card data-testid="c">x</Card>)
    const crudas = [...clases(screen.getByTestId('c'))].filter((c) =>
      /-(slate|blue|red|amber|emerald|teal|white)(-\d{2,3})?$/.test(c),
    )
    expect(crudas).toEqual([])
  })

  it('la clase del llamador se suma a la de la forma', () => {
    render(
      <Card className="mt-3" data-testid="c">
        x
      </Card>,
    )
    const c = clases(screen.getByTestId('c'))
    expect(c.has('mt-3')).toBe(true)
    expect(c.has('bg-surface')).toBe(true)
  })
})

describe('Card · el elemento y lo que recibe', () => {
  it('por omisión es un div', () => {
    render(<Card data-testid="c">x</Card>)
    expect(screen.getByTestId('c').tagName).toBe('DIV')
  })

  it.each(['section', 'ul', 'label'] as const)('con as=%s renderiza ese elemento', (etiqueta) => {
    render(
      <Card as={etiqueta} data-testid="c">
        x
      </Card>,
    )
    expect(screen.getByTestId('c').tagName).toBe(etiqueta.toUpperCase())
  })

  it('como botón sigue siendo un botón: recibe el clic y el teclado', async () => {
    // Es el caso que obliga a la prop `as`: los dos tiles de KPI accionables. Un `<div>`
    // con `onClick` los dejaría fuera del foco y del teclado, y ninguna prueba de aspecto
    // lo vería.
    const alHacerClic = vi.fn()
    render(
      <Card as="button" type="button" onClick={alHacerClic} aria-pressed={false}>
        Con stock bajo
      </Card>,
    )
    const boton = screen.getByRole('button', { name: 'Con stock bajo' })
    expect(boton).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(boton)
    expect(alHacerClic).toHaveBeenCalledTimes(1)
  })

  it('pasa al elemento lo que recibe', () => {
    render(
      <Card as="section" aria-labelledby="t" data-testid="c" title="Ayuda">
        x
      </Card>,
    )
    const el = screen.getByTestId('c')
    expect(el).toHaveAttribute('aria-labelledby', 't')
    expect(el).toHaveAttribute('title', 'Ayuda')
  })
})

describe('cardClasses · la cadena suelta', () => {
  it.each(Object.entries(ESPERADAS))('el padding %s da lo mismo que el componente', (padding, esperado) => {
    expect(new Set(cardClasses({ padding: padding as keyof typeof ESPERADAS }).split(/\s+/))).toEqual(
      new Set(esperado.split(/\s+/)),
    )
  })
})
