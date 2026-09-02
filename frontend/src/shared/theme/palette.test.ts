import { describe, expect, it } from 'vitest'
import { oklchToRgb, toHex } from './palette'

/**
 * El conversor tiene una rama que los 27 tokens de hoy NO ejercitan de forma
 * distinguible: la lineal de la codificación gamma, para canales por debajo de
 * 0.0031308. El único token que la toca es el verde, cuyo canal rojo vale
 * exactamente 0, y ahí las dos ramas dan lo mismo. La cobertura la marca como
 * tomada y no distingue su cuenta.
 *
 * Importa porque es la rama que va a usar el modo oscuro: barriendo los 286
 * colores de la paleta, doce distinguen el divisor correcto del equivocado, y
 * los doce son de la franja oscura. Los dos casos de acá son de esa franja.
 *
 * Sobre el límite del codo (0.0031308), y con cuidado porque el argumento del
 * módulo hermano NO se importa acá: allá la entrada es un canal de 8 bits, con
 * 256 valores discretos y ninguno en el hueco, así que `<=` contra `<` es
 * genuinamente equivalente. Acá la entrada es un flotante continuo y no hay
 * discretización que proteja nada: lo único equivalente sigue siendo `<=` contra
 * `<`, pero MOVER el umbral sí cambia resultados, y el caso de green-800 lo fija.
 */
describe('oklchToRgb', () => {
  it('convierte un color de la franja clara', () => {
    // slate-700, el texto de cuerpo del tema.
    expect(toHex(oklchToRgb(0.372, 0.044, 257.287))).toBe('#314158')
  })

  it('convierte un color cuya rama es la lineal', () => {
    // red-950 es el candidato natural al fondo suave de peligro en oscuro, y su
    // canal azul cae por debajo del codo. Con el divisor equivocado da #460708.
    expect(toHex(oklchToRgb(0.258, 0.092, 26.042))).toBe('#460809')
  })

  it('convierte otro de la misma franja, sin croma', () => {
    expect(toHex(oklchToRgb(0.141, 0.005, 285.823))).toBe('#09090b')
  })

  it('el canal muy por debajo del codo no puede ir por la rama gamma', () => {
    // green-800. Su canal rojo cae MUY por debajo del codo, donde las dos ramas
    // ya dejaron de coincidir: por la gamma el resultado tiende a -0.055 y el
    // canal sale negativo, con lo que el hexadecimal ni siquiera queda bien
    // formado. Los dos casos de arriba fijan el multiplicador, no la condición:
    // caen justo por debajo del codo, donde las ramas todavía coinciden.
    expect(toHex(oklchToRgb(0.448, 0.119, 151.328))).toBe('#016630')
  })

  it('recorta al gamut en vez de devolver un canal fuera de rango', () => {
    // Un croma imposible para esa luminosidad: sin recorte, el canal se iría
    // por encima de 255 y el hexadecimal saldría de más de dos dígitos.
    expect(toHex(oklchToRgb(0.5, 0.4, 0))).toMatch(/^#[0-9a-f]{6}$/)
  })

  it('rechaza la luminosidad escrita como porcentaje', () => {
    // `theme.css` la escribe como `20.8%`, y quien copie ese número sin dividir
    // obtiene blanco en silencio. Mejor que falle.
    expect(() => oklchToRgb(63.7, 0.237, 25.331)).toThrow(/luminosidad/i)
  })

  it('rechaza un componente que no es un número', () => {
    expect(() => oklchToRgb(0.5, 0.1, Number.NaN)).toThrow(/número/i)
  })
})

describe('toHex', () => {
  it('rellena con cero los canales de un solo dígito', () => {
    expect(toHex([0, 122, 85])).toBe('#007a55')
  })
})
