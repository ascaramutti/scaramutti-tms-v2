import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Badge, type BadgeVariant } from './Badge'

/**
 * Lo que estas pruebas cuidan es la MUDANZA de color: las siete variantes pasan a tokens y
 * dos de ellas cambian de familia por decisión del dueño (el peligro deja de ser rosa, y las
 * decorativas se recortan a dos con nombre por función). Lo que puede romperse no es la
 * forma, que no se toca, sino que una variante quede pintada con la de al lado.
 *
 * El conjunto se fija contra literales escritos ACÁ y no derivados del módulo: si las dos
 * puntas salieran de la misma fuente, ninguna mutación podría hacerlas fallar.
 */
const ESPERADAS: Record<BadgeVariant, string> = {
  default: 'bg-surface-muted text-fg-body',
  info: 'bg-accent-soft-strong text-accent-hover',
  success: 'bg-success-soft text-success-fg',
  warning: 'bg-warning-soft-strong text-warning',
  danger: 'bg-danger-soft text-danger-fg',
  progress: 'bg-progress-soft text-progress-fg',
  transition: 'bg-transition-soft text-transition-fg',
}

const FORMA = 'inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium'

const clases = (el: HTMLElement) => new Set(el.className.split(/\s+/).filter(Boolean))

describe('Badge · el color de cada variante', () => {
  it.each(Object.entries(ESPERADAS))('la variante %s produce el mismo conjunto', (variante, esperado) => {
    render(<Badge variant={variante as Exclude<BadgeVariant, 'info'>}>x</Badge>)
    expect(clases(screen.getByText('x'))).toEqual(new Set(`${FORMA} ${esperado}`.split(' ')))
  })

  it('sin variante es la neutra', () => {
    render(<Badge>x</Badge>)
    expect(clases(screen.getByText('x'))).toEqual(
      new Set(`${FORMA} ${ESPERADAS.default}`.split(' ')),
    )
  })

  /**
   * Las siete tienen que ser SIETE colores, no seis con una repetida. Es el riesgo concreto
   * del recorte de las decorativas: `slate` valía exactamente lo mismo que la neutra, y
   * fundir una de más deja dos estados del mismo listado pintados igual.
   */
  it('ninguna variante repite el color de otra', () => {
    // Se mide sobre lo RENDERIZADO y no sobre la tabla de arriba: comparar la tabla contra
    // sí misma es una tautología, y una mutación que pinte dos variantes iguales pasaba.
    const pintadas = (Object.keys(ESPERADAS) as BadgeVariant[]).map((variante) => {
      const { container, unmount } = render(
        <Badge variant={variante as Exclude<BadgeVariant, 'info'>}>x</Badge>,
      )
      const clase = (container.firstChild as HTMLElement).className
      unmount()
      return clase
    })
    expect(new Set(pintadas).size).toBe(pintadas.length)
  })
})

describe('Badge · el filete del marcador interno', () => {
  it('la informativa con filete suma el borde y nada más', () => {
    render(
      <Badge variant="info" bordered>
        x
      </Badge>,
    )
    expect(clases(screen.getByText('x'))).toEqual(
      new Set(`${FORMA} ${ESPERADAS.info} border border-blue-200`.split(' ')),
    )
  })

  it('la informativa sin filete no lo trae', () => {
    render(<Badge variant="info">x</Badge>)
    expect(clases(screen.getByText('x')).has('border')).toBe(false)
  })
})

/**
 * El color crudo que queda, medido sobre la fuente y no sobre el render: es UNO, es el filete
 * de la pastilla interna, y está escrito con su motivo. Sin este caso, el día que alguien
 * agregue otro color suelto nadie lo va a ver, que es exactamente cómo llegaron los ocho
 * literales que la revisión del PR de los campos encontró tapando a su token.
 */
describe('Badge · lo que queda sin token', () => {
  it('el único color crudo del archivo es el filete de la pastilla interna', () => {
    const fuente = readFileSync(join(process.cwd(), 'src', 'shared', 'ui', 'Badge.tsx'), 'utf8')
    const crudos = [
      ...fuente.matchAll(/\b(?:bg|text|border|ring)-(?:blue|amber|slate|rose|teal|violet|emerald)-\d{2,3}\b/g),
    ].map((m) => m[0])
    expect(crudos).toEqual(['border-blue-200'])
  })
})
