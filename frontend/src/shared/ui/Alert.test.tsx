import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Alert } from './Alert'
import { alertClasses } from './alertClasses'

/**
 * El riesgo de esta mudanza no es el color: es el ROL. `alert` interrumpe al lector de
 * pantalla y `status` no, y no son intercambiables. El componente no elige ninguno de los
 * dos, así que estas pruebas fijan que no lo haga.
 *
 * Los conjuntos van contra literales escritos acá, no derivados de `alertClasses`.
 */
const FONDOS = {
  danger: 'bg-danger-soft',
  warning: 'bg-warning-soft',
  info: 'bg-accent-soft',
  success: 'bg-success-soft',
} as const

const clases = (el: HTMLElement) => new Set(el.className.split(/\s+/).filter(Boolean))

describe('Alert · el rol lo pone el sitio, no el componente', () => {
  it.each(['alert', 'status'] as const)('renderiza el rol %s tal cual se lo pasan', (rol) => {
    render(
      <Alert role={rol} data-testid="a">
        x
      </Alert>,
    )
    expect(screen.getByTestId('a')).toHaveAttribute('role', rol)
  })

  it('con role undefined no pone ninguno', () => {
    // Cuatro de los avisos del árbol no tienen rol: son cajas informativas que acompañan
    // al contenido. Inventarles uno les daría una voz que hoy no tienen.
    render(
      <Alert role={undefined} data-testid="a">
        x
      </Alert>,
    )
    expect(screen.getByTestId('a')).not.toHaveAttribute('role')
  })
})

describe('Alert · las clases son las de la forma que reemplaza', () => {
  it.each(Object.entries(FONDOS))('la variante %s produce el fondo de su token', (variante, fondo) => {
    render(
      <Alert variant={variante as keyof typeof FONDOS} role="alert" data-testid="a">
        x
      </Alert>,
    )
    expect(clases(screen.getByTestId('a')).has(fondo)).toBe(true)
  })

  it('la variante danger produce el fondo del token de peligro', () => {
    render(
      <Alert role="alert" data-testid="a">
        x
      </Alert>,
    )
    const c = clases(screen.getByTestId('a'))
    expect(c.has('bg-danger-soft')).toBe(true)
    expect(c.has('bg-warning-soft')).toBe(false)
  })

  it('con borde trae la clase de borde de su variante', () => {
    render(
      <Alert variant="warning" role="alert" data-testid="a">
        x
      </Alert>,
    )
    const c = clases(screen.getByTestId('a'))
    expect(c.has('border')).toBe(true)
    expect(c.has('border-warning-border')).toBe(true)
  })

  it('sin borde no trae ninguna clase de borde', () => {
    // Son las ocho franjas de error bajo un formulario.
    render(
      <Alert bordered={false} role="alert" data-testid="a">
        x
      </Alert>,
    )
    const c = clases(screen.getByTestId('a'))
    expect(c.has('border')).toBe(false)
    expect([...c].some((x) => x.startsWith('border-'))).toBe(false)
    expect(c.has('bg-danger-soft')).toBe(true)
  })

  it('no pone color de texto: lo pone el sitio', () => {
    // La decisión menos obvia del componente. Dos avisos no lo tienen en el contenedor, y
    // uno de esos dos tiene adentro una tabla sin color propio, que lo heredaría.
    render(
      <Alert role="alert" data-testid="a">
        x
      </Alert>,
    )
    expect([...clases(screen.getByTestId('a'))].some((c) => c.startsWith('text-'))).toBe(false)
  })

  it.each(['danger', 'warning', 'info', 'success'] as const)(
    'la variante %s no escribe ningún color crudo: fondo y borde salen de tokens',
    (variante) => {
      render(
        <Alert variant={variante} role="alert" data-testid="a">
          x
        </Alert>,
      )
      const crudas = [...clases(screen.getByTestId('a'))].filter((c) =>
        /-(slate|blue|red|amber|emerald|teal|white)(-\d{2,3})?$/.test(c),
      )
      expect(crudas).toEqual([])
    },
  )

  it('las cuatro variantes traen su propio token de borde, no uno prestado', () => {
    // La variante de éxito llegó a tener el tono suave como borde, que es un borde del
    // mismo color que el fondo. Cada una tiene el suyo.
    const bordes = new Set<string>()
    for (const v of ['danger', 'warning', 'info', 'success'] as const) {
      const { unmount } = render(
        <Alert variant={v} role="alert" data-testid="a">
          x
        </Alert>,
      )
      const borde = [...clases(screen.getByTestId('a'))].find(
        (c) => c.startsWith('border-') && c !== 'border',
      )
      expect(borde).toBeDefined()
      bordes.add(borde as string)
      unmount()
    }
    expect(bordes.size).toBe(4)
  })
})

describe('Alert · el elemento y lo que recibe', () => {
  it('por omisión es un div, y con as=p es un párrafo', () => {
    const { unmount } = render(
      <Alert role="alert" data-testid="a">
        x
      </Alert>,
    )
    expect(screen.getByTestId('a').tagName).toBe('DIV')
    unmount()
    render(
      <Alert as="p" role="alert" data-testid="a">
        x
      </Alert>,
    )
    expect(screen.getByTestId('a').tagName).toBe('P')
  })

  it('la clase del llamador se suma, y su espaciado gana', () => {
    render(
      <Alert role="alert" className="px-4 py-2.5 text-sm" data-testid="a">
        x
      </Alert>,
    )
    const c = clases(screen.getByTestId('a'))
    expect(c.has('px-4')).toBe(true)
    expect(c.has('text-sm')).toBe(true)
    expect(c.has('bg-danger-soft')).toBe(true)
  })
})

describe('alertClasses · la cadena suelta', () => {
  it.each(Object.entries(FONDOS))('la variante %s da el mismo fondo que el componente', (v, fondo) => {
    expect(alertClasses({ variant: v as keyof typeof FONDOS }).split(/\s+/)).toContain(fondo)
  })
})
