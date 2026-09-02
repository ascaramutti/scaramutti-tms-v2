import { describe, expect, it } from 'vitest'
import { parseThemeColors } from './readTokens'

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
