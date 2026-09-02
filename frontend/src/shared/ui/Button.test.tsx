import { useEffect, useRef } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button'
import { buttonClasses } from './buttonClasses'

/**
 * Lo que estas pruebas cuidan es la MUDANZA, no el botón: el componente reemplaza
 * tres constantes de clases que estaban aplicadas en 47 sitios de producción. (El
 * `grep` por los tres nombres devuelve 89 menciones fuera de las pruebas: 3 son las
 * declaraciones, 35 son líneas de import, y 4 son de dos constantes LOCALES que se
 * llamaban igual y decían otra cosa, la trampa que `WizardForm` documenta.
 * 89 − 3 − 35 − 4 = 47.)
 *
 * Hoy hay 45 elementos `<Button>` y 6 lugares que toman solo las clases, y no es una resta
 * de los 47: siete de aquellos sitios vivían en un mapa de cinco entradas y en un ternario
 * de dos, que hoy se resuelven en dos lugares, y el barrido por valor sumó nueve copias que
 * la búsqueda por nombre no encontraba. 47 − 7 + 2 + 9 = 51. Lo que puede romperse no es el
 * aspecto sino lo que cada botón traía consigo.
 *
 * Por eso hay dos familias de casos: una que fija la cadena de clases contra la
 * que producían las constantes viejas (traducida a tokens), y otra que verifica
 * que lo que se le pasa al componente llega al `<button>` real.
 */

/** Las clases de las constantes viejas, con cada tono cambiado por su token. */
const ESPERADAS = {
  primary:
    'inline-flex items-center rounded-lg bg-accent px-4 py-2 text-sm font-medium text-on-solid ' +
    'shadow-sm hover:bg-accent-hover focus:outline-none focus:ring-2 focus:ring-focus focus:ring-offset-2',
  secondary:
    'inline-flex items-center rounded-lg border border-border-strong bg-surface px-4 py-2 ' +
    'text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus:ring-2 focus:ring-focus',
  danger:
    'inline-flex items-center rounded-lg bg-danger px-4 py-2 text-sm font-medium text-on-solid ' +
    'shadow-sm hover:bg-danger-hover focus:outline-none focus:ring-2 focus:ring-danger focus:ring-offset-2',
} as const

const clases = (el: HTMLElement) => new Set(el.className.split(/\s+/).filter(Boolean))

describe('Button · las clases son las de las constantes que reemplaza', () => {
  it.each(Object.entries(ESPERADAS))('la variante %s produce el mismo conjunto', (variante, esperado) => {
    render(<Button variant={variante as keyof typeof ESPERADAS}>Acción</Button>)
    expect(clases(screen.getByRole('button'))).toEqual(new Set(esperado.split(/\s+/)))
  })

  it('el tamaño de solo ícono cambia el espaciado y nada más', () => {
    render(
      <Button variant="secondary" size="icon" aria-label="Siguiente">
        <span aria-hidden="true">→</span>
      </Button>,
    )
    // Se fija el conjunto ENTERO, no que `p-1.5` esté y `px-4` no. Con aserciones
    // sueltas, agregarle clases al tamaño no rompía nada: medido, `icon` pasando a
    // `'p-1.5 text-sm font-medium'` dejaba los veintidós casos en verde.
    expect(clases(screen.getByRole('button', { name: 'Siguiente' }))).toEqual(
      new Set(
        ESPERADAS.secondary
          .split(/\s+/)
          .filter((c) => !['px-4', 'py-2', 'text-sm', 'font-medium'].includes(c))
          .concat('p-1.5'),
      ),
    )
  })

  it('las clases extra del llamador se suman a las de la variante', () => {
    render(<Button className="mt-4 w-full">Guardar</Button>)
    const c = clases(screen.getByRole('button'))
    expect(c.has('mt-4')).toBe(true)
    expect(c.has('w-full')).toBe(true)
    expect(c.has('bg-accent')).toBe(true)
  })

  it('una clase en conflicto REEMPLAZA a la de la variante, no se suma', () => {
    // `cn` es `twMerge(clsx(...))`. El caso de arriba pasa con `mt-4 w-full` porque no
    // chocan con nada; con esto queda escrito qué gana cuando sí chocan, que es lo que
    // el próximo llamador necesita saber antes de pasar un color por `className`.
    render(
      <Button variant="primary" className="bg-danger">
        Anular
      </Button>,
    )
    const c = clases(screen.getByRole('button'))
    expect(c.has('bg-danger')).toBe(true)
    expect(c.has('bg-accent')).toBe(false)
  })

  it.each(['primary', 'secondary', 'danger'] as const)(
    'la variante %s toma sus colores de tokens y de ninguna paleta de Tailwind',
    (variante) => {
      // Se afirma por lista CERRADA de tokens, no por lista de paletas prohibidas: con la
      // segunda, una paleta que la enumeración no conociera (Tailwind trae veintidós y la
      // lista vieja nombraba siete) pasaba con el título intacto. Medido antes de cambiarlo.
      // Los tokens, en cambio, son once y están todos en `index.css`.
      //
      // Los nombres de las clases de ejemplo NO se escriben acá: Tailwind escanea los
      // comentarios, y nombrar una utilidad que el código no usa la publica en el CSS. Pasó
      // con la primera redacción de este bloque: cinco reglas muertas en el bundle.
      //
      // Es del componente, no del `<button>` que llega al DOM: cuatro llamadores pasan
      // `disabled:bg-blue-300` por `className` y siguen crudos a propósito (no hay token
      // para blue-300 y agregarlo sería un cambio de tono).
      const TOKENS = [
        'accent', 'accent-hover', 'accent-soft', 'danger', 'danger-hover', 'focus',
        'fg-body', 'on-solid', 'surface', 'surface-subtle', 'border-strong',
      ]
      const CON_COLOR = /^(?:[a-z-]+:)*(bg|text|border|ring|from|to|via|divide|outline|decoration|shadow)-(.+)$/
      render(<Button variant={variante}>Anular</Button>)
      const ajenas = [...clases(screen.getByRole('button'))]
        .map((c) => CON_COLOR.exec(c))
        .filter((m): m is RegExpExecArray => m !== null)
        .map((m) => m[2])
        // Lo que queda después del guion no siempre es un color: puede ser un grosor, una
        // distancia, un tamaño, o la palabra que apaga el contorno nativo. Esos cuatro se
        // descartan por su forma, sin nombrarlos, por lo dicho arriba sobre el escaneo.
        .filter(
          (valor) =>
            !/^\d+$/.test(valor) &&
            valor !== 'sm' &&
            valor !== 'none' &&
            !valor.startsWith('offset-'),
        )
        .filter((valor) => !TOKENS.includes(valor))
      expect(ajenas).toEqual([])
    },
  )
})

describe('buttonClasses · la cadena suelta no puede divergir del componente', () => {
  /**
   * La función existe para los usos que no son botones (los enlaces de navegación y
   * los mapas de variantes), y este bloque deja escrito que las dos salidas son la
   * misma.
   *
   * No es la red: la ponen los dos bloques de arriba, que comparan cada lado contra
   * el literal de `ESPERADAS`. Ninguna mutación puede romper una fila de acá sin
   * romper antes una de aquéllas. Queda por lo que documenta, no por lo que atrapa.
   */
  it.each([
    ['primary', 'md'],
    ['secondary', 'md'],
    ['danger', 'md'],
    ['secondary', 'icon'],
  ] as const)('%s/%s da exactamente lo que el componente pone en el DOM', (variant, size) => {
    const { unmount } = render(<Button variant={variant} size={size} aria-label="x" />)
    const delComponente = clases(screen.getByRole('button', { name: 'x' }))
    unmount()
    expect(new Set(buttonClasses({ variant, size }).split(/\s+/))).toEqual(delComponente)
  })
})

describe('Button · lo que recibe llega al botón real', () => {
  it('pasa el nombre accesible', () => {
    render(<Button aria-label="Quitar filtro" />)
    expect(screen.getByRole('button', { name: 'Quitar filtro' })).toBeInTheDocument()
  })

  it('pasa el estado deshabilitado y no dispara el manejador', async () => {
    const alHacerClic = vi.fn()
    render(
      <Button disabled onClick={alHacerClic}>
        Guardar
      </Button>,
    )
    const boton = screen.getByRole('button')
    expect(boton).toBeDisabled()
    await userEvent.click(boton)
    expect(alHacerClic).not.toHaveBeenCalled()
  })

  it('pasa el manejador y los atributos sueltos', async () => {
    const alHacerClic = vi.fn()
    render(
      <Button onClick={alHacerClic} title="Ayuda" data-testid="x">
        Guardar
      </Button>,
    )
    await userEvent.click(screen.getByRole('button'))
    expect(alHacerClic).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('x')).toHaveAttribute('title', 'Ayuda')
  })
})

describe('buttonClasses · la cadena suelta, contra el literal', () => {
  it.each(['primary', 'secondary', 'danger'] as const)(
    'sin argumento de tamaño, la variante %s da las clases de `md`',
    (variante) => {
      // Los seis lugares que consumen `buttonClasses` directo (los cuatro enlaces con pinta
      // de botón y las dos entradas del mapa de cotizaciones) omiten el tamaño, así que el
      // valor por omisión es carga real. Sin este caso, cambiarlo a `icon` deja en verde a
      // los cuatro archivos que los renderizan: medido, sobrevivía.
      expect(new Set(buttonClasses({ variant: variante }).split(/\s+/))).toEqual(
        new Set(ESPERADAS[variante].split(/\s+/)),
      )
    },
  )
})

describe('Button · el tipo por omisión no envía el formulario', () => {
  it('por omisión es type=button, así que dentro de un form no envía', async () => {
    const alEnviar = vi.fn((e: React.FormEvent) => e.preventDefault())
    render(
      <form onSubmit={alEnviar}>
        <Button>Cancelar</Button>
      </form>,
    )
    expect(screen.getByRole('button')).toHaveAttribute('type', 'button')
    await userEvent.click(screen.getByRole('button'))
    expect(alEnviar).not.toHaveBeenCalled()
  })

  it('con type=submit sí envía, que es lo que hay que pedir explícitamente', async () => {
    const alEnviar = vi.fn((e: React.FormEvent) => e.preventDefault())
    render(
      <form onSubmit={alEnviar}>
        <Button type="submit">Guardar</Button>
      </form>,
    )
    await userEvent.click(screen.getByRole('button'))
    expect(alEnviar).toHaveBeenCalledTimes(1)
  })
})

describe('Button · el ref llega al <button> real', () => {
  it('un ref apunta al nodo del DOM y le puede dar el foco', () => {
    // `ServiceExitModal` hace exactamente esto cuando se despeja un conflicto que
    // estaba en pantalla: mueve el foco al botón que confirma para que el usuario pueda
    // reintentar sin buscarlo (al abrir, el foco va al motivo, no acá). Si el ref se
    // quedara en el componente en vez de llegar al nodo, el foco no se movería a ningún
    // lado y nada fallaría a gritos.
    function Anfitrion() {
      const ref = useRef<HTMLButtonElement>(null)
      useEffect(() => ref.current?.focus(), [])
      return <Button ref={ref}>Confirmar</Button>
    }
    render(<Anfitrion />)
    expect(screen.getByRole('button', { name: 'Confirmar' })).toHaveFocus()
  })
})
