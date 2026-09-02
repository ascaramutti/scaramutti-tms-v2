import { describe, expect, it } from 'vitest'
import { contrastRatio, parseHex, relativeLuminance } from './contrast'

/**
 * Prueba del cálculo en sí, contra valores de referencia conocidos.
 *
 * Existe separada de la del tema porque mide otra cosa: la del tema pregunta si
 * los colores elegidos alcanzan, y esta pregunta si la cuenta está bien hecha.
 * Sin ella, un error en la fórmula haría pasar la otra por el motivo equivocado.
 *
 * El caso que más importa es el de la rama lineal (canales por debajo de
 * 0.04045, o sea menores a 11 sobre 255). NO es código muerto ni algo que solo
 * vaya a servir para el modo oscuro: **seis de los veintisiete tokens tienen un
 * canal ahí** y el canal más bajo del tema es 0, no 15. Son los de color, no los
 * grises: el rojo, el ámbar y el verde llevan un canal en cero o casi. Ocho de
 * los pares medidos la atraviesan hoy, incluida la aritmética de una de las
 * excepciones conocidas. Quien toque esa rama creyendo que no afecta nada de lo
 * que se mide hoy, se equivoca por un tercio de la lista.
 */
describe('contrastRatio', () => {
  it('da 21 entre negro y blanco, que es el máximo de la escala', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 2)
  })

  it('da 1 entre un color y sí mismo', () => {
    expect(contrastRatio('#3b82f6', '#3b82f6')).toBeCloseTo(1, 10)
  })

  it('no depende del orden de los argumentos', () => {
    expect(contrastRatio('#0f172b', '#f8fafc')).toBeCloseTo(
      contrastRatio('#f8fafc', '#0f172b'),
      12,
    )
  })

  it('reproduce el gris de referencia que apenas alcanza AA sobre blanco', () => {
    // #767676 es el valor canónico que se usa para calibrar el 4.5:1.
    expect(contrastRatio('#767676', '#ffffff')).toBeCloseTo(4.54, 2)
  })

  it('mide bien un color cuyos tres canales caen en la rama lineal', () => {
    // slate-950 es rgb(2, 6, 24): el rojo y el verde están por debajo del codo
    // de 0.04045. Se elige un color de fuera del tema para fijar el codo con dos
    // canales del mismo lado; los tokens que ya pasan por esa rama lo hacen con
    // un canal solo. Sin este caso, cambiar el divisor 12.92 no lo mata nadie.
    // El 20.157 sale de aplicar la fórmula de la norma a mano, no de correr
    // esta implementación: canales 0.00060705 / 0.00182116 / 0.00913406,
    // luminancia 0.00209103, y (1 + 0.05) / (0.00209103 + 0.05) = 20.157.
    expect(contrastRatio('#020618', '#ffffff')).toBeCloseTo(20.157, 2)
  })

  /**
   * Dos mutaciones de esa rama NO las mata ninguna prueba, y no es un hueco:
   * son equivalentes. Cambiar `<=` por `<`, o el 0.04045 por el 0.03928 que
   * también circula en la literatura, no cambia el resultado para NINGÚN canal
   * de 8 bits. Medido: el límite cae entre v=10 (0.039216) y v=11 (0.043137),
   * y los dos umbrales viven en ese hueco, así que ningún valor entero los
   * separa. Se anota para que nadie gaste tiempo buscando el caso que los mate.
   */
  it('mide bien un color justo por encima del codo', () => {
    // rgb(11, 11, 11): 11/255 = 0.0431, un pelo ARRIBA del codo, así que los
    // tres canales van por la rama gamma. Fija de qué lado cae el límite, y a
    // CUATRO decimales a propósito: las dos ramas son continuas en el codo, así
    // que a dos decimales mover el 0.04045 a cualquier valor entre 0.0431 y 1 da
    // exactamente el mismo número y no lo mata nadie. Medido.
    expect(contrastRatio('#0b0b0b', '#ffffff')).toBeCloseTo(19.6826, 4)
  })
})

describe('relativeLuminance', () => {
  it('vale 0 en negro y 1 en blanco', () => {
    expect(relativeLuminance([0, 0, 0])).toBeCloseTo(0, 12)
    expect(relativeLuminance([255, 255, 255])).toBeCloseTo(1, 12)
  })

  it('pesa el verde más que el rojo y el rojo más que el azul', () => {
    const rojo = relativeLuminance([255, 0, 0])
    const verde = relativeLuminance([0, 255, 0])
    const azul = relativeLuminance([0, 0, 255])
    expect(verde).toBeGreaterThan(rojo)
    expect(rojo).toBeGreaterThan(azul)
  })
})

describe('parseHex', () => {
  it('acepta la forma larga', () => {
    expect(parseHex('#0f172b')).toEqual([15, 23, 43])
  })

  it('acepta la forma corta y la expande duplicando cada dígito', () => {
    expect(parseHex('#abc')).toEqual(parseHex('#aabbcc'))
  })

  it('no distingue mayúsculas de minúsculas', () => {
    expect(parseHex('#E2E8F0')).toEqual(parseHex('#e2e8f0'))
  })

  it('rechaza un hexadecimal con canal alfa, que falsearía el cálculo', () => {
    // Ocho dígitos traen transparencia, y un color translúcido no se puede
    // medir sin saber sobre qué se dibuja. Mejor que falle a que mienta.
    expect(() => parseHex('#0f172b80')).toThrow(/no reconocido/i)
  })

  it('tolera espacios alrededor', () => {
    expect(parseHex('  #0f172b  ')).toEqual([15, 23, 43])
  })

  it('exige el numeral, para no aceptar algo que no es un color', () => {
    // Los dos casos, y el segundo es el que mide de verdad el ancla: sin ella,
    // cualquier cadena de siete caracteres cuyos últimos seis sean hexadecimales
    // se leería como color, porque el primer carácter se descarta a ciegas.
    // Medido: sin el ancla, 'x0f172b' devolvía [15, 23, 43] sin chistar.
    expect(() => parseHex('0f172b')).toThrow(/no reconocido/i)
    expect(() => parseHex('x0f172b')).toThrow(/no reconocido/i)
  })

  it('rechaza lo que no es un hexadecimal', () => {
    expect(() => parseHex('rgb(15, 23, 43)')).toThrow(/no reconocido/i)
    expect(() => parseHex('#12345')).toThrow(/no reconocido/i)
  })
})
