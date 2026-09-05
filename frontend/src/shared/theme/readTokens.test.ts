import { describe, expect, it } from 'vitest'
import { parseThemeColors, parseThemeOverrides } from './readTokens'

/**
 * El parser tiene tres decisiones que un cambio descuidado puede revertir sin
 * que nada se queje, y las tres se fijan acá: que tolere un modificador en la
 * declaración, que se detenga en el primer bloque, y que falle fuerte cuando no
 * encuentra nada en vez de devolver un objeto vacío que se lea como "el tema no
 * tiene colores".
 */
describe('parseThemeColors', () => {
  it('lee los pares del bloque, sin el prefijo de la variable', () => {
    const css = '@theme {\n  --color-fg: #0f172b;\n  --color-surface: #ffffff;\n}\n'
    expect(parseThemeColors(css)).toEqual({ fg: '#0f172b', surface: '#ffffff' })
  })

  it('tolera un modificador en la declaración', () => {
    // La tolerancia existe para que un cambio de modificador no deje la prueba
    // de contraste sin nada que medir. Sin este caso, quitar el comodín del
    // patrón no lo mata nadie.
    const css = '@theme static {\n  --color-fg: #0f172b;\n}\n'
    expect(parseThemeColors(css)).toEqual({ fg: '#0f172b' })
  })

  it('acepta nombres con guiones y con dígitos', () => {
    const css = '@theme {\n  --color-danger-soft: #fef2f2;\n  --color-gris2: #abc;\n}\n'
    expect(Object.keys(parseThemeColors(css))).toEqual(['danger-soft', 'gris2'])
  })

  it('ignora lo que no es un color', () => {
    const css = '@theme {\n  --font-sans: Inter;\n  --color-fg: #0f172b;\n}\n'
    expect(parseThemeColors(css)).toEqual({ fg: '#0f172b' })
  })

  it('se detiene en el primer bloque y no arrastra lo que venga después', () => {
    // Es lo que hace que la prueba de contraste mida UN tema y no una mezcla de
    // dos. El día que exista un segundo tema hay que leerlo aparte, no acá.
    const css =
      '@theme {\n  --color-fg: #0f172b;\n}\n\n' +
      '@theme {\n  --color-fg: #ffffff;\n  --color-otro: #123456;\n}\n'
    expect(parseThemeColors(css)).toEqual({ fg: '#0f172b' })
  })

  it('falla fuerte si no hay bloque, en vez de devolver un tema vacío', () => {
    // Devolver {} haría que la prueba de contraste no midiera nada y quedara en
    // verde, que es el peor resultado posible para una prueba.
    expect(() => parseThemeColors('body { margin: 0 }')).toThrow(/no se encontró/i)
  })
})

/**
 * `parseThemeOverrides` alimenta las aserciones de contraste del tema oscuro, una por cada uno
 * de sus pares, y entró sin una sola prueba propia. Las tres decisiones que un cambio
 * descuidado puede revertir son las mismas que las de su hermana, más una que es peor: si en vez de LANZAR devolviera un
 * objeto vacío, la paleta oscura quedaría sin valores, cada par mediría `undefined` contra
 * `undefined` y la suite decidiría sola si eso es verde.
 */
describe('parseThemeOverrides', () => {
  const css = `
:root {
  --color-fg: #111111;
}
:root[data-theme='dark'] {
  color-scheme: dark;
  --color-fg: #eeeeee;
  --color-canvas: #000000;
}
`

  it('lee el bloque del selector pedido y no el otro', () => {
    expect(parseThemeOverrides(css, ":root[data-theme='dark']")).toEqual({
      fg: '#eeeeee',
      canvas: '#000000',
    })
  })

  /**
   * El escapado, medido contra un señuelo. La versión anterior de este caso repetía la llamada
   * del caso de arriba y afirmaba menos, así que sacar el escapado no lo mataba a él: hacía
   * lanzar a la función y caía primero el otro. Acá el patrón sin escapar SÍ encuentra algo, y
   * encuentra el bloque equivocado, que es el modo de falla que de verdad importa.
   */
  it('escapa el selector en vez de tratarlo como un patrón', () => {
    const conSeñuelo = `
:rootXtema-oscuro {
  --color-fg: #ff0000;
}
:root.tema-oscuro {
  --color-fg: #eeeeee;
}
`
    // El punto sin escapar es un comodín y el señuelo va primero: sin escapar, esto devuelve
    // el rojo.
    expect(parseThemeOverrides(conSeñuelo, ':root.tema-oscuro')).toEqual({ fg: '#eeeeee' })
  })

  it('ignora lo que no es un color', () => {
    expect(parseThemeOverrides(css, ":root[data-theme='dark']")).not.toHaveProperty('scheme')
  })

  it('LANZA si el bloque no está, en vez de devolver una paleta vacía', () => {
    // Es la diferencia entre fallar y medir aire: con `{}` cada par de contraste compararía
    // `undefined` contra `undefined`.
    expect(() => parseThemeOverrides(css, ":root[data-theme='alto-contraste']")).toThrow(
      /no se encontró/i,
    )
  })

  it('se detiene en el cierre de su bloque y no arrastra el siguiente', () => {
    const dos = css + `
:root[data-theme='otro'] {
  --color-fg: #abcdef;
}
`
    expect(parseThemeOverrides(dos, ":root[data-theme='dark']").fg).toBe('#eeeeee')
  })
})
