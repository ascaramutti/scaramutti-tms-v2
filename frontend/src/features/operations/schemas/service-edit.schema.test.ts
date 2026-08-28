import { describe, expect, it } from 'vitest'
import {
  JUSTIFICATION_MIN_LENGTH,
  serviceEditFormSchema,
  toServiceEditFormValues,
  toServiceUpdateRequest,
  type ServiceEditFormInput,
} from './service-edit.schema'
import { fakeServiceDetail } from '../../../test/mocks/handlers/operations'
import { fakeCurrency } from '../../../test/mocks/handlers/catalogs'

const CURRENCIES = [
  fakeCurrency({ id: 1, code: 'USD' }),
  fakeCurrency({ id: 2, code: 'PEN' }),
  fakeCurrency({ id: 9, code: 'ARS', isActive: false }),
]

const JUSTIFICATION = 'Corrijo el destino que vino mal del cliente'

function validForm(overrides: Partial<ServiceEditFormInput> = {}): ServiceEditFormInput {
  return {
    ...toServiceEditFormValues(fakeServiceDetail(), CURRENCIES),
    justification: JUSTIFICATION,
    ...overrides,
  }
}

function errorFor(form: ServiceEditFormInput, field: string): string | undefined {
  const result = serviceEditFormSchema.safeParse(form)
  expect(result.success).toBe(false)
  return result.error?.issues.find((issue) => issue.path.join('.') === field)?.message
}

describe('toServiceEditFormValues', () => {
  it('abre con lo que el viaje tiene, y la justificación en blanco', () => {
    const service = fakeServiceDetail({ observations: 'Frágil', lengthM: 12.5, widthM: null })

    const values = toServiceEditFormValues(service, CURRENCIES)

    expect(values.origin).toBe(service.origin)
    expect(values.destination).toBe(service.destination)
    expect(values.weightKg).toBe(String(service.weightKg))
    expect(values.lengthM).toBe('12.5')
    // Vacío y no "null": el campo es de texto, y la medida ausente se muestra en blanco.
    expect(values.widthM).toBe('')
    expect(values.observations).toBe('Frágil')
    // La justificación NUNCA se precarga: es de este cambio, no del anterior.
    expect(values.justification).toBe('')
  })

  it('traduce el código de la moneda al id que el cuerpo pide', () => {
    // El detalle publica el código y el PUT exige el id, que la respuesta no trae.
    const values = toServiceEditFormValues(fakeServiceDetail({ currencyCode: 'USD' }), CURRENCIES)

    expect(values.currencyId).toBe(1)
  })

  it('resuelve también una moneda dada de baja, que es el caso que el contrato protege', () => {
    // Un viaje viejo cuya moneda se retiró se sigue editando mientras no se la cambie. Si
    // el catálogo llegara filtrado por activas, este caso reventaría y el usuario quedaría
    // obligado a cambiar la moneda para poder guardar cualquier otra corrección.
    const values = toServiceEditFormValues(fakeServiceDetail({ currencyCode: 'ARS' }), CURRENCIES)

    expect(values.currencyId).toBe(9)
  })

  it('revienta si el código del viaje no está en el catálogo, en vez de abrir sin moneda', () => {
    expect(() =>
      toServiceEditFormValues(fakeServiceDetail({ currencyCode: 'XYZ' }), CURRENCIES),
    ).toThrow(/XYZ/)
  })

  it('muestra las fechas reales como reloj de pared de Lima', () => {
    // 14:30 en Lima es 19:30 UTC. El campo tiene que mostrar la hora peruana sin importar
    // dónde esté el navegador, que es la regla del módulo.
    const values = toServiceEditFormValues(
      fakeServiceDetail({ startDateTime: '2026-09-10T19:30:00Z', endDateTime: null }),
      CURRENCIES,
    )

    expect(values.startDateTime).toBe('2026-09-10T14:30')
    // Sin fecha de fin el campo queda vacío, que acá significa "no la toqué".
    expect(values.endDateTime).toBe('')
  })
})

describe('serviceEditFormSchema', () => {
  it('acepta el formulario que sale del viaje tal como vino', () => {
    expect(serviceEditFormSchema.safeParse(validForm()).success).toBe(true)
  })

  it('exige una justificación de al menos el mínimo, medido sin los espacios', () => {
    expect(errorFor(validForm({ justification: '' }), 'justification')).toBe(
      `Explica el cambio en al menos ${JUSTIFICATION_MIN_LENGTH} caracteres`,
    )
    // Diez espacios no son una justificación: el contrato lo dice con un patrón porque su
    // `minLength` cuenta el texto crudo.
    expect(errorFor(validForm({ justification: ' '.repeat(12) }), 'justification')).toBe(
      `Explica el cambio en al menos ${JUSTIFICATION_MIN_LENGTH} caracteres`,
    )
    expect(errorFor(validForm({ justification: 'corto' }), 'justification')).toBeDefined()
  })

  it('rechaza un fin anterior al inicio, y lo dice sobre el fin', () => {
    const error = errorFor(
      validForm({ startDateTime: '2026-09-10T14:30', endDateTime: '2026-09-10T09:00' }),
      'endDateTime',
    )

    expect(error).toBe('El fin no puede ser anterior al inicio')
  })

  it('acepta que el fin sea el mismo instante que el inicio', () => {
    const form = validForm({
      startDateTime: '2026-09-10T14:30',
      endDateTime: '2026-09-10T14:30',
    })

    expect(serviceEditFormSchema.safeParse(form).success).toBe(true)
  })

  it('no compara las fechas cuando falta una de las dos', () => {
    // Un viaje en ruta tiene inicio y no fin: sin esta rama, corregir el inicio sería
    // imposible porque la comparación tomaría el vacío como una fecha anterior.
    const soloInicio = validForm({ startDateTime: '2026-09-10T14:30', endDateTime: '' })

    expect(serviceEditFormSchema.safeParse(soloInicio).success).toBe(true)
  })

  it('nombra el campo que falló, y no otro', () => {
    // Las fábricas son compartidas con el alta y se llaman con etiquetas: lo que este caso
    // mata es invocarlas con el nombre cruzado, que el compilador no ve.
    expect(errorFor(validForm({ origin: '' }), 'origin')).toBe('Indica el origen')
    expect(errorFor(validForm({ destination: '' }), 'destination')).toBe('Indica el destino')
    expect(errorFor(validForm({ lengthM: '0' }), 'lengthM')).toBe('El largo debe ser mayor a 0')
    expect(errorFor(validForm({ widthM: '0' }), 'widthM')).toBe('El ancho debe ser mayor a 0')
    expect(errorFor(validForm({ heightM: '0' }), 'heightM')).toBe('El alto debe ser mayor a 0')
    expect(errorFor(validForm({ weightKg: '0' }), 'weightKg')).toBe('El peso debe ser mayor a 0')
    expect(errorFor(validForm({ price: '0' }), 'price')).toBe('El precio debe ser mayor a 0')
  })
})

describe('toServiceUpdateRequest', () => {
  function parsed(overrides: Partial<ServiceEditFormInput> = {}) {
    return toServiceUpdateRequest(serviceEditFormSchema.parse(validForm(overrides)))
  }

  it('vacía las medidas y las observaciones que quedaron en blanco', () => {
    // Mandarlas explícitas en null es lo que hace que borrarlas en pantalla las borre de
    // verdad: el contrato dice que un cuerpo parcial vacía lo que no incluye.
    const body = parsed({ lengthM: '', widthM: '', heightM: '', observations: '' })

    expect(body.lengthM).toBeNull()
    expect(body.widthM).toBeNull()
    expect(body.heightM).toBeNull()
    expect(body.observations).toBeNull()
  })

  it('omite las fechas reales que quedaron vacías, en vez de mandarlas en null', () => {
    // Acá ausente significa SIN CAMBIO, al revés que en las medidas: una fecha real no se
    // borra. Se afirma la ausencia de la clave y no su valor, que es la diferencia.
    const body = parsed({ startDateTime: '', endDateTime: '' })

    expect('startDateTime' in body).toBe(false)
    expect('endDateTime' in body).toBe(false)
  })

  it('manda la fecha real corregida como instante, leyendo el reloj de Lima', () => {
    const body = parsed({ startDateTime: '2026-09-10T14:30', endDateTime: '2026-09-10T18:45' })

    expect(body.startDateTime).toBe('2026-09-10T19:30:00.000Z')
    expect(body.endDateTime).toBe('2026-09-10T23:45:00.000Z')
  })

  it('manda los importes como número y la justificación recortada', () => {
    const body = parsed({ weightKg: '28000', price: '4500.50', justification: `  ${JUSTIFICATION}  ` })

    expect(body.weightKg).toBe(28000)
    expect(body.price).toBe(4500.5)
    expect(body.justification).toBe(JUSTIFICATION)
  })
})
