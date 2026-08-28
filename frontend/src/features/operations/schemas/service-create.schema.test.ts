import { describe, expect, it } from 'vitest'
import {
  SERVICE_OBSERVATIONS_MAX_LENGTH,
  SERVICE_PLACE_MAX_LENGTH,
  serviceCreateFormSchema,
  toServiceCreateRequest,
  type ServiceCreateFormInput,
} from './service-create.schema'

/** Alta válida mínima; cada caso cambia solo lo que mide. */
function validForm(overrides: Partial<ServiceCreateFormInput> = {}): ServiceCreateFormInput {
  return {
    clientId: 12,
    tripScope: 'PROVINCIA',
    tentativeDate: '2026-09-10',
    origin: 'Piura',
    destination: 'Lima',
    cargoTypeId: 3,
    weightKg: '28000',
    lengthM: '',
    widthM: '',
    heightM: '',
    price: '5800',
    currencyId: 1,
    observations: '',
    ...overrides,
  }
}

/** El primer mensaje de error del campo pedido, o undefined si el campo pasó. */
function errorFor(form: ServiceCreateFormInput, field: string): string | undefined {
  const result = serviceCreateFormSchema.safeParse(form)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path[0] === field)?.message
}

describe('serviceCreateFormSchema', () => {
  it('acepta un alta completa', () => {
    expect(serviceCreateFormSchema.safeParse(validForm()).success).toBe(true)
  })

  // ----- Textos de una línea -----
  it('rechaza un origen con salto de línea, que el servidor tampoco admite', () => {
    // El input de una línea ya descarta el salto al pegar, así que esta regla es el
    // backstop: cubre lo que llegue por cualquier otro camino.
    expect(errorFor(validForm({ origin: 'Piura\nCentro' }), 'origin')).toBe(
      'El origen va en una sola línea, sin saltos',
    )
  })

  it('rechaza una tabulación en el destino', () => {
    expect(errorFor(validForm({ destination: 'Lima\tCallao' }), 'destination')).toBe(
      'El destino va en una sola línea, sin saltos',
    )
  })

  it('rechaza los separadores de línea de Unicode, que el servidor también rechaza', () => {
    // No son controles ISO, así que una regla escrita solo con ese rango los dejaría
    // pasar para volver con un 400: son exactamente lo que la regla dice prohibir.
    expect(errorFor(validForm({ origin: 'Piura\u2028Centro' }), 'origin')).toMatch(/una sola línea/i)
    expect(errorFor(validForm({ destination: 'Lima\u2029Callao' }), 'destination')).toMatch(
      /una sola línea/i,
    )
  })

  it('acepta acentos, eñes y guiones en los lugares', () => {
    // Lo que se prohíbe son los controles, no los imprimibles: un destino real es
    // "Lima — Callao" y una provincia puede ser "Cañete".
    expect(serviceCreateFormSchema.safeParse(validForm({ destination: 'Lima — Callao' })).success).toBe(true)
    expect(serviceCreateFormSchema.safeParse(validForm({ origin: 'Cañete' })).success).toBe(true)
  })

  it('exige origen y destino', () => {
    expect(errorFor(validForm({ origin: '   ' }), 'origin')).toBe('Indica el origen')
    expect(errorFor(validForm({ destination: '' }), 'destination')).toBe('Indica el destino')
  })

  it('corta los lugares en el tope de la columna', () => {
    // Literal y no la constante: derivar el esperado de lo mismo que se mide deja
    // que el tope se aleje del contrato sin que nada falle.
    expect(SERVICE_PLACE_MAX_LENGTH).toBe(255)
    const enElTope = 'x'.repeat(SERVICE_PLACE_MAX_LENGTH)
    expect(serviceCreateFormSchema.safeParse(validForm({ origin: enElTope })).success).toBe(true)
    expect(errorFor(validForm({ origin: `${enElTope}x` }), 'origin')).toMatch(/máximo/i)
  })

  // ----- Importes -----
  it('exige el peso y el precio', () => {
    expect(errorFor(validForm({ weightKg: '' }), 'weightKg')).toBe('Indica el peso')
    expect(errorFor(validForm({ price: '' }), 'price')).toBe('Indica el precio')
  })

  it('rechaza el precio en cero y en negativo: un viaje siempre tiene precio', () => {
    expect(errorFor(validForm({ price: '0' }), 'price')).toBe('El precio debe ser mayor a 0')
    expect(errorFor(validForm({ price: '-1' }), 'price')).toBe('El precio debe ser mayor a 0')
  })

  it('rechaza un importe que no es un número', () => {
    expect(errorFor(validForm({ weightKg: 'mucho' }), 'weightKg')).toMatch(/número/i)
  })

  it('rechaza más de dos decimales, que la columna no guarda', () => {
    expect(errorFor(validForm({ weightKg: '1.234' }), 'weightKg')).toMatch(/2 decimales/i)
    expect(errorFor(validForm({ price: '99.999' }), 'price')).toMatch(/2 decimales/i)
    expect(errorFor(validForm({ lengthM: '1.234' }), 'lengthM')).toMatch(/2 decimales/i)
  })

  it('rechaza un importe más grande que su columna', () => {
    // Literales del contrato: ocho cifras enteras en peso y medidas, diez en precio.
    expect(errorFor(validForm({ weightKg: '100000000' }), 'weightKg')).toMatch(/demasiado grande/i)
    expect(errorFor(validForm({ price: '10000000000' }), 'price')).toMatch(/demasiado grande/i)
    expect(errorFor(validForm({ widthM: '100000000' }), 'widthM')).toMatch(/demasiado grande/i)
  })

  it('acepta el valor más grande que sí entra', () => {
    expect(serviceCreateFormSchema.safeParse(validForm({ weightKg: '99999999.99' })).success).toBe(true)
    expect(serviceCreateFormSchema.safeParse(validForm({ price: '9999999999.99' })).success).toBe(true)
  })

  it('acepta decimales en el peso y el precio', () => {
    const parsed = serviceCreateFormSchema.parse(validForm({ weightKg: '1250.55', price: '99.9' }))
    expect(parsed.weightKg).toBe(1250.55)
    expect(parsed.price).toBe(99.9)
  })

  // ----- Medidas opcionales -----
  it('deja pasar las medidas vacías y las convierte en null', () => {
    const parsed = serviceCreateFormSchema.parse(validForm())
    expect(parsed.lengthM).toBeNull()
    expect(parsed.widthM).toBeNull()
    expect(parsed.heightM).toBeNull()
  })

  it('rechaza una medida en cero cuando sí se escribió, y nombra la que falló', () => {
    // Vacío significa "no la sé"; cero significa "mide cero", que no existe.
    //
    // Las tres, y no solo una: las tres líneas del schema son idénticas salvo la
    // etiqueta, así que el defecto probable no es que falte la regla sino que una llame
    // a la fábrica con el nombre de otra. Con un solo caso, el usuario ve "el ancho
    // debe ser mayor a 0" sobre el campo del alto y nada falla.
    expect(errorFor(validForm({ lengthM: '0' }), 'lengthM')).toBe('El largo debe ser mayor a 0')
    expect(errorFor(validForm({ widthM: '0' }), 'widthM')).toBe('El ancho debe ser mayor a 0')
    expect(errorFor(validForm({ heightM: '0' }), 'heightM')).toBe('El alto debe ser mayor a 0')
  })

  it('convierte a número las medidas cargadas', () => {
    const parsed = serviceCreateFormSchema.parse(validForm({ lengthM: '12.5', heightM: '4' }))
    expect(parsed.lengthM).toBe(12.5)
    expect(parsed.heightM).toBe(4)
  })

  // ----- Fecha -----
  it('acepta una fecha pasada: el registro retroactivo es válido', () => {
    expect(serviceCreateFormSchema.safeParse(validForm({ tentativeDate: '1999-01-01' })).success).toBe(true)
  })

  it('acepta los extremos exactos de la ventana, que el contrato declara inclusivos', () => {
    // Los casos de afuera no distinguen un borde inclusivo de uno exclusivo: sin
    // estos dos, cambiar `>=` por `>` pasa desapercibido.
    expect(serviceCreateFormSchema.safeParse(validForm({ tentativeDate: '1900-01-01' })).success).toBe(true)
    expect(serviceCreateFormSchema.safeParse(validForm({ tentativeDate: '2999-12-31' })).success).toBe(true)
  })

  it('rechaza una fecha fuera de la ventana que la columna admite', () => {
    expect(errorFor(validForm({ tentativeDate: '1899-12-31' }), 'tentativeDate')).toMatch(/entre/i)
    expect(errorFor(validForm({ tentativeDate: '3000-01-01' }), 'tentativeDate')).toMatch(/entre/i)
  })

  // ----- Observaciones -----
  it('admite saltos y tabulaciones en las observaciones, que son texto libre', () => {
    expect(
      serviceCreateFormSchema.safeParse(validForm({ observations: 'Primera línea\nSegunda' })).success,
    ).toBe(true)
  })

  it('rechaza un carácter de control que la columna no puede guardar', () => {
    expect(errorFor(validForm({ observations: 'texto\u0001roto' }), 'observations')).toMatch(
      /caracteres de control/i,
    )
  })

  it('rechaza el byte NUL, que es el que el contrato nombra', () => {
    // El contrato lo singulariza porque la columna no lo admite, y es el que corta
    // los textos por la mitad en cualquier herramienta que los procese.
    expect(errorFor(validForm({ observations: 'texto\u0000roto' }), 'observations')).toMatch(
      /caracteres de control/i,
    )
    expect(errorFor(validForm({ origin: 'Piura\u0000' }), 'origin')).toMatch(/una sola línea/i)
  })

  it('corta las observaciones en su tope', () => {
    expect(SERVICE_OBSERVATIONS_MAX_LENGTH).toBe(500)
    const pasado = 'x'.repeat(SERVICE_OBSERVATIONS_MAX_LENGTH + 1)
    expect(errorFor(validForm({ observations: pasado }), 'observations')).toMatch(/máximo/i)
  })
})

describe('toServiceCreateRequest', () => {
  it('arma el cuerpo con todo lo cargado', () => {
    const values = serviceCreateFormSchema.parse(
      validForm({ lengthM: '12.5', observations: 'Coordinar ingreso' }),
    )

    expect(toServiceCreateRequest(values)).toEqual({
      clientId: 12,
      tripScope: 'PROVINCIA',
      tentativeDate: '2026-09-10',
      origin: 'Piura',
      destination: 'Lima',
      cargoTypeId: 3,
      weightKg: 28000,
      lengthM: 12.5,
      widthM: null,
      heightM: null,
      price: 5800,
      currencyId: 1,
      observations: 'Coordinar ingreso',
    })
  })

  it('manda null y no cadena vacía cuando no hay observaciones', () => {
    const values = serviceCreateFormSchema.parse(validForm({ observations: '   ' }))
    expect(toServiceCreateRequest(values).observations).toBeNull()
  })

  it('recorta los espacios de los lugares antes de mandarlos', () => {
    const values = serviceCreateFormSchema.parse(validForm({ origin: '  Piura  ' }))
    expect(toServiceCreateRequest(values).origin).toBe('Piura')
  })
})
