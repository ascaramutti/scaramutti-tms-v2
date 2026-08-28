import { describe, expect, it } from 'vitest'
import {
  MEASURE_MAX,
  SERVICE_DATE_MAX,
  SERVICE_DATE_MIN,
  SERVICE_OBSERVATIONS_MAX_LENGTH,
  SERVICE_PLACE_MAX_LENGTH,
  currencySchema,
  observationsSchema,
  optionalMeasureSchema,
  placeSchema,
  requiredAmountSchema,
  tentativeDateSchema,
} from './service-fields.schema'

/*
 * Estas piezas las comparten el alta y la edición, así que se prueban por su propio
 * camino y no a través de un formulario. La suite del alta las ejercita con UN juego de
 * parámetros; acá se prueba la PARAMETRIZACIÓN, que es lo que el segundo consumidor
 * puede estrenar mal sin que suene nada.
 */

/** El primer error de un parseo fallido, que es el que el campo muestra. */
function errorOf(result: { success: boolean; error?: { issues: { message: string }[] } }): string {
  expect(result.success).toBe(false)
  return result.error!.issues[0]!.message
}

describe('placeSchema', () => {
  /*
   * Dos etiquetas DISTINTAS en cada caso, y no una repetida: lo que este par de
   * expectativas mata es escribir el nombre del campo adentro de la fábrica, que deja
   * verde a quien la llame con el otro nombre. Afirmar el mensaje completo contra la
   * misma constante que lo produce no mataría nada.
   */
  it('nombra el campo que le tocó, y no otro', () => {
    expect(errorOf(placeSchema('origen').safeParse(''))).toBe('Indica el origen')
    expect(errorOf(placeSchema('destino').safeParse(''))).toBe('Indica el destino')
  })

  it('nombra el campo también al rechazar los saltos de línea', () => {
    expect(errorOf(placeSchema('origen').safeParse('Lima\nNorte'))).toBe(
      'El origen va en una sola línea, sin saltos',
    )
    expect(errorOf(placeSchema('destino').safeParse('Trujillo\nCentro'))).toBe(
      'El destino va en una sola línea, sin saltos',
    )
  })

  it('corta en el tope de la columna y lo dice con el número', () => {
    const justo = 'a'.repeat(SERVICE_PLACE_MAX_LENGTH)

    expect(placeSchema('origen').safeParse(justo).success).toBe(true)
    expect(errorOf(placeSchema('origen').safeParse(`${justo}a`))).toBe('Máximo 255 caracteres')
  })
})

describe('optionalMeasureSchema', () => {
  /*
   * El verdugo del nombre CRUZADO: con la etiqueta escrita adentro de la fábrica, o con
   * el alto llamado con 'ancho', estas dos líneas dejan de coincidir. Es el defecto que
   * el javadoc de la fábrica narra y que hasta ahora nadie medía.
   */
  it('nombra la medida que le tocó', () => {
    expect(errorOf(optionalMeasureSchema('largo').safeParse('0'))).toBe(
      'El largo debe ser mayor a 0',
    )
    expect(errorOf(optionalMeasureSchema('alto').safeParse('0'))).toBe('El alto debe ser mayor a 0')
  })

  it('distingue lo que no es un número de lo que no es positivo', () => {
    expect(errorOf(optionalMeasureSchema('largo').safeParse('mucho'))).toBe(
      'El largo tiene que ser un número',
    )
  })

  it('vacío significa ausente, y no cero', () => {
    expect(optionalMeasureSchema('alto').parse('')).toBeNull()
    expect(optionalMeasureSchema('alto').parse('2.5')).toBe(2.5)
  })

  it('admite el tope exacto y rechaza el siguiente', () => {
    expect(optionalMeasureSchema('ancho').parse(String(MEASURE_MAX))).toBe(MEASURE_MAX)
    expect(errorOf(optionalMeasureSchema('ancho').safeParse('100000000'))).toBe(
      'El ancho es demasiado grande',
    )
    expect(errorOf(optionalMeasureSchema('ancho').safeParse('1.005'))).toBe(
      'El ancho admite como máximo 2 decimales',
    )
  })
})

describe('requiredAmountSchema', () => {
  /*
   * Los dos primeros parámetros son mensajes y tienen el mismo tipo, así que
   * intercambiarlos compila. Se prueban con textos distintos y por separado para que ese
   * cruce falle.
   */
  it('usa el mensaje de ausencia y el de no-positivo donde corresponde', () => {
    const schema = requiredAmountSchema('Indica el peso', 'El peso debe ser mayor a 0', MEASURE_MAX)

    expect(errorOf(schema.safeParse(''))).toBe('Indica el peso')
    expect(errorOf(schema.safeParse('0'))).toBe('El peso debe ser mayor a 0')
    expect(errorOf(schema.safeParse('-1'))).toBe('El peso debe ser mayor a 0')
  })

  it('convierte a número y respeta el tope que se le pasa', () => {
    const schema = requiredAmountSchema('Falta', 'Muy chico', 100)

    expect(schema.parse('99.99')).toBe(99.99)
    expect(errorOf(schema.safeParse('101'))).toBe('El valor es demasiado grande')
    expect(errorOf(schema.safeParse('1.005'))).toBe('Como máximo 2 decimales')
    expect(errorOf(schema.safeParse('abc'))).toBe('Tiene que ser un número')
  })
})

describe('tentativeDateSchema', () => {
  it('exige una fecha y la acota a la ventana de la columna', () => {
    expect(errorOf(tentativeDateSchema().safeParse(''))).toBe('Indica la fecha tentativa')
    expect(tentativeDateSchema().safeParse(SERVICE_DATE_MIN).success).toBe(true)
    expect(tentativeDateSchema().safeParse(SERVICE_DATE_MAX).success).toBe(true)
    // El mensaje entero y no un fragmento: nombra los dos bordes, y quien borre uno deja
    // al usuario sin saber cuál violó.
    expect(errorOf(tentativeDateSchema().safeParse('1899-12-31'))).toBe(
      'La fecha debe estar entre 1900-01-01 y 2999-12-31',
    )
    expect(tentativeDateSchema().safeParse('3000-01-01').success).toBe(false)
  })
})

describe('currencySchema', () => {
  it('pide elegir una, y no acepta el cero del selector vacío', () => {
    expect(errorOf(currencySchema().safeParse(undefined))).toBe('Elige la moneda del servicio')
    expect(errorOf(currencySchema().safeParse(0))).toBe('Elige la moneda del servicio')
    expect(currencySchema().parse(2)).toBe(2)
  })
})

describe('observationsSchema', () => {
  it('deja pasar los saltos y las tabulaciones, que acá son legítimos', () => {
    expect(observationsSchema().parse('Primera línea\nSegunda\tcon tabulación')).toBe(
      'Primera línea\nSegunda\tcon tabulación',
    )
  })

  it('rechaza el byte NUL, que es lo que la columna no admite', () => {
    // La guarda que el contrato exige y que vive en un solo lugar del módulo: si esta
    // línea se pierde al copiar el campo a otro formulario, el 400 llega del servidor.
    expect(errorOf(observationsSchema().safeParse('Carga frágil\u0000'))).toBe(
      'No se permiten caracteres de control.',
    )
  })

  it('corta en el tope y admite el vacío', () => {
    expect(observationsSchema().parse(undefined)).toBeUndefined()
    expect(observationsSchema().parse('a'.repeat(SERVICE_OBSERVATIONS_MAX_LENGTH))).toHaveLength(
      SERVICE_OBSERVATIONS_MAX_LENGTH,
    )
    expect(errorOf(observationsSchema().safeParse('a'.repeat(501)))).toBe('Máximo 500 caracteres')
  })
})
