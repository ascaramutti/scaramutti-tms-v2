import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  JUSTIFICATION_MIN_LENGTH,
  serviceEditFormSchema,
  toServiceEditFormValues,
  toServiceUpdateRequest,
  type ServiceEditFormInput,
} from './service-edit.schema'
import type { ServiceDetailResponse } from '../../../api'
import { fakeServiceDetail } from '../../../test/mocks/handlers/operations'
import { fakeCurrency } from '../../../test/mocks/handlers/catalogs'

const CURRENCIES = [
  fakeCurrency({ id: 1, code: 'USD' }),
  fakeCurrency({ id: 2, code: 'PEN' }),
  fakeCurrency({ id: 9, code: 'ARS', isActive: false }),
]

const JUSTIFICATION = 'Corrijo el destino que vino mal del cliente'

/**
 * El viaje base de este archivo: los tres campos numéricos con valores DISTINTOS entre sí
 * y ninguno nulo. Con dos medidas en null (como viene el fixture compartido), cruzar dos
 * campos en el cuerpo no se vería.
 */
function serviceToEdit(overrides: Partial<ServiceDetailResponse> = {}): ServiceDetailResponse {
  return fakeServiceDetail({
    tentativeDate: '2026-09-10',
    origin: 'Piura',
    destination: 'Lima — Callao',
    weightKg: 28000.25,
    lengthM: 12.55,
    widthM: 2.45,
    heightM: 3.65,
    price: 5800.75,
    currencyCode: 'PEN',
    observations: 'Carga frágil',
    // Pasadas a propósito: una fecha real no puede estar en el futuro, así que moverlas
    // adelante rompe media docena de casos por un motivo ajeno a lo que miden.
    startDateTime: '2026-08-20T19:30:00Z',
    endDateTime: '2026-08-20T23:45:00Z',
    ...overrides,
  })
}

/** El cuerpo que sale del viaje base sin tocar un solo campo. */
const CUERPO_BASE = {
  tentativeDate: '2026-09-10',
  origin: 'Piura',
  destination: 'Lima — Callao',
  weightKg: 28000.25,
  lengthM: 12.55,
  widthM: 2.45,
  heightM: 3.65,
  price: 5800.75,
  currencyId: 2,
  observations: 'Carga frágil',
  startDateTime: '2026-08-20T19:30:00.000Z',
  endDateTime: '2026-08-20T23:45:00.000Z',
  justification: JUSTIFICATION,
} as const

function validForm(overrides: Partial<ServiceEditFormInput> = {}): ServiceEditFormInput {
  return {
    ...toServiceEditFormValues(serviceToEdit(), CURRENCIES),
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
    const service = serviceToEdit({ observations: 'Frágil', lengthM: 12.5, widthM: null })

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
    const values = toServiceEditFormValues(serviceToEdit({ currencyCode: 'USD' }), CURRENCIES)

    expect(values.currencyId).toBe(1)
  })

  it('resuelve también una moneda dada de baja, que es el caso que el contrato protege', () => {
    // Un viaje viejo cuya moneda se retiró se sigue editando mientras no se la cambie. Si
    // el catálogo llegara filtrado por activas, este caso reventaría y el usuario quedaría
    // obligado a cambiar la moneda para poder guardar cualquier otra corrección.
    const values = toServiceEditFormValues(serviceToEdit({ currencyCode: 'ARS' }), CURRENCIES)

    expect(values.currencyId).toBe(9)
  })

  it('revienta si el código del viaje no está en el catálogo, en vez de abrir sin moneda', () => {
    expect(() =>
      toServiceEditFormValues(serviceToEdit({ currencyCode: 'XYZ' }), CURRENCIES),
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

  it('rechaza el byte NUL en la justificación, que es lo que la columna no admite', () => {
    // El contrato la pone entre los textos libres donde el NUL es 400, igual que las
    // observaciones. El saneado del campo es la primera capa; esta es el respaldo, y es la
    // que queda si alguien arma el cuerpo por otro camino.
    expect(errorFor(validForm({ justification: 'Motivo con nulo\u0000 adentro' }), 'justification')).toBe(
      'No se permiten caracteres de control.',
    )
    // Los saltos y las tabulaciones SÍ son válidos acá.
    expect(
      serviceEditFormSchema.safeParse(validForm({ justification: 'Primera línea\nSegunda' })).success,
    ).toBe(true)
  })

  it('acota las fechas reales a la ventana que la columna admite', () => {
    // Un `datetime-local` acepta años de una cifra y de cinco; la columna no. Sin esto el
    // formulario los deja salir y el servidor devuelve un 400 sobre el formulario entero,
    // que es justo lo que las validaciones de esta pantalla existen para evitar.
    expect(errorFor(validForm({ startDateTime: '0005-01-01T10:00' }), 'startDateTime')).toBe(
      'La fecha debe estar entre 1900-01-01 y 2999-12-31',
    )
    expect(errorFor(validForm({ endDateTime: '3000-01-01T10:00' }), 'endDateTime')).toBe(
      'La fecha debe estar entre 1900-01-01 y 2999-12-31',
    )
    // El borde inferior entra. El superior (2999) ya no se puede afirmar acá: es futuro, y
    // desde que las fechas reales rechazan el futuro lo ataja la otra guarda antes. Que la
    // ventana siga acotando por arriba lo mide el caso del año 3000 de acá arriba, que
    // espera SU mensaje y no el del futuro.
    const enElBorde = validForm({ startDateTime: '1900-01-01T00:00', endDateTime: '' })
    expect(serviceEditFormSchema.safeParse(enElBorde).success).toBe(true)
  })

  describe('las fechas reales no pueden estar en el futuro', () => {
    /*
     * La misma regla que los diálogos que FIJAN estas fechas, y por el mismo motivo: si
     * corregir fuera más permisivo que poner, la regla tendría una puerta de atrás (iniciar
     * hoy y después corregir el inicio a mañana). En el sistema anterior entraron así tres
     * viajes que "terminan" meses después de empezar.
     *
     * El reloj se fija para que el caso no dependa del día en que corra la suite, y se
     * elige un instante que cae en DÍAS DISTINTOS según la zona: 28/08 23:00 en Lima son
     * las 13:00 del 29/08 en Tokio, que es donde corre vitest. Una guarda escrita contra el
     * reloj del proceso daría por pasado el 29/08 y dejaría entrar la fecha que Perú
     * todavía no vivió.
     */
    const AHORA = new Date('2026-08-29T04:00:00Z') // 28/08 23:00 en Lima, 29/08 13:00 en Tokio

    beforeEach(() => {
      vi.useFakeTimers()
      vi.setSystemTime(AHORA)
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it.each(['startDateTime', 'endDateTime'] as const)(
      'rechaza un %s posterior al ahora de Lima',
      (campo) => {
        // Un caso por campo y no uno genérico: copiar la guarda de un campo al otro y
        // olvidarse de cambiar el nombre es el defecto que ya apareció tres veces acá.
        //
        // La otra fecha se vacía para que la ÚNICA razón de rechazo sea el futuro: con el
        // fixture completo, mover el inicio a mañana también viola fin >= inicio, y el
        // caso quedaría verde por la guarda equivocada.
        const form = validForm({ startDateTime: '', endDateTime: '', [campo]: '2026-08-29T10:00' })

        expect(errorFor(form, campo)).toBe('La fecha no puede estar en el futuro')
      },
    )

    it('rechaza el minuto siguiente al actual, no solo el día siguiente', () => {
      // El borde en la otra dirección: sin esto, una guarda que compare por DÍA deja pasar
      // una hora futura del mismo día (las 23:59 cuando son las 23:00) y los demás casos
      // siguen verdes.
      const form = validForm({ startDateTime: '2026-08-28T23:01', endDateTime: '' })

      expect(errorFor(form, 'startDateTime')).toBe('La fecha no puede estar en el futuro')
    })

    it('acepta el minuto en curso, que no es futuro', () => {
      // Sin este borde la guarda se pasa de estricta: el usuario que corrige la fecha a
      // "ahora mismo" quedaría trabado con un formulario que rechaza el presente.
      const ahoraEnLima = '2026-08-28T23:00'
      const form = validForm({ startDateTime: ahoraEnLima, endDateTime: ahoraEnLima })

      expect(serviceEditFormSchema.safeParse(form).success).toBe(true)
    })

    it('rechaza el día que en Tokio ya llegó pero en Lima todavía no', () => {
      // El instante que separa las dos zonas: en Tokio ya es el 29, en Lima todavía es el
      // 28. Con la guarda escrita contra el reloj del proceso (que en la suite es Tokio),
      // esta fecha entraría por ser "de hoy".
      const form = validForm({ startDateTime: '2026-08-29T00:00', endDateTime: '' })

      expect(errorFor(form, 'startDateTime')).toBe('La fecha no puede estar en el futuro')
    })
  })

  it('rechaza un fin anterior al inicio, y lo dice sobre el fin', () => {
    const error = errorFor(
      validForm({ startDateTime: '2026-08-20T14:30', endDateTime: '2026-08-20T09:00' }),
      'endDateTime',
    )

    expect(error).toBe('El fin no puede ser anterior al inicio')
  })

  it('acepta que el fin sea el mismo instante que el inicio', () => {
    const form = validForm({
      startDateTime: '2026-08-20T14:30',
      endDateTime: '2026-08-20T14:30',
    })

    expect(serviceEditFormSchema.safeParse(form).success).toBe(true)
  })

  it('no compara las fechas cuando falta una de las dos', () => {
    // Un viaje en ruta tiene inicio y no fin: sin esta rama, corregir el inicio sería
    // imposible porque la comparación tomaría el vacío como una fecha anterior.
    const soloInicio = validForm({ startDateTime: '2026-08-20T14:30', endDateTime: '' })

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

describe('toServiceUpdateRequest, un campo por vez', () => {
  /*
   * Un caso por campo editable, y en cada uno se afirma el cuerpo ENTERO: el que cambia
   * con su valor nuevo, y los otros once idénticos a como llegaron.
   *
   * Lo que esto cierra no es el cruce de campos (eso ya lo caza el `toEqual` de abajo)
   * sino el EFECTO COLATERAL: que tocar un campo altere otro que el usuario no miró. El
   * riesgo es concreto y no teórico, porque el servidor descarta un cuerpo sin cambios
   * reales y no escribe nada: si el formulario reenvía un campo levemente distinto sin
   * que nadie lo tocara (una fecha que perdió los segundos, un decimal reformateado, un
   * texto recortado), queda registrado en la auditoría y en la bitácora un cambio que
   * nadie hizo, atribuido a quien solo quiso corregir otra cosa.
   *
   * El fixture trae los doce con valores DISTINTOS entre sí y ninguno nulo: con dos
   * campos iguales o vacíos, el caso pasa sin medir nada. Y los importes llevan sus dos
   * decimales, que es la precisión que la columna guarda: con valores redondos, un
   * reformateo al mandarlos no cambiaría el número y el caso lo dejaría pasar (medido).
   */
  const CAMPOS = [
    ['tentativeDate', { tentativeDate: '2026-10-01' }, { tentativeDate: '2026-10-01' }],
    ['origin', { origin: 'Chiclayo' }, { origin: 'Chiclayo' }],
    ['destination', { destination: 'Trujillo' }, { destination: 'Trujillo' }],
    ['weightKg', { weightKg: '31000.45' }, { weightKg: 31000.45 }],
    ['lengthM', { lengthM: '14.75' }, { lengthM: 14.75 }],
    ['widthM', { widthM: '2.55' }, { widthM: 2.55 }],
    ['heightM', { heightM: '4.15' }, { heightM: 4.15 }],
    ['price', { price: '6200.55' }, { price: 6200.55 }],
    ['currencyId', { currencyId: 1 }, { currencyId: 1 }],
    ['observations', { observations: 'Carga con escolta' }, { observations: 'Carga con escolta' }],
    [
      'startDateTime',
      { startDateTime: '2026-08-20T15:45' },
      { startDateTime: '2026-08-20T20:45:00.000Z' },
    ],
    [
      'endDateTime',
      { endDateTime: '2026-08-21T08:00' },
      { endDateTime: '2026-08-21T13:00:00.000Z' },
    ],
  ] as const

  it.each(CAMPOS)('cambiar %s no toca ningún otro campo', (_campo, enElFormulario, enElCuerpo) => {
    const body = toServiceUpdateRequest(serviceEditFormSchema.parse(validForm(enElFormulario)))

    expect(body).toEqual({ ...CUERPO_BASE, ...enElCuerpo })
  })

  it('los doce campos editables del contrato tienen su caso', () => {
    // Sin esto, agregar un campo al cuerpo y olvidarse de su caso pasa desapercibido: la
    // tabla de arriba seguiría verde midiendo los que ya estaban.
    const conCaso = CAMPOS.map(([campo]) => campo)
    const enElCuerpo = Object.keys(CUERPO_BASE).filter((campo) => campo !== 'justification')

    expect([...conCaso].sort()).toEqual([...enElCuerpo].sort())
  })
})

describe('toServiceUpdateRequest', () => {
  function parsed(overrides: Partial<ServiceEditFormInput> = {}) {
    return toServiceUpdateRequest(serviceEditFormSchema.parse(validForm(overrides)))
  }

  it('manda cada campo en su lugar, y no uno por otro', () => {
    /*
     * El cuerpo completo contra un literal, y no campo por campo: los que nadie nombra
     * son justo los que se pueden cruzar sin que nada falle. Con aserciones sueltas,
     * mandar el origen en el destino, reescribir la fecha tentativa o fijar la moneda en
     * un id cualquiera sobrevive a toda la suite, y la moneda es el campo por el que este
     * PR tiene un hook aparte y una resolución que revienta a propósito.
     */
    expect(parsed()).toEqual(CUERPO_BASE)
  })

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
    const body = parsed({ startDateTime: '2026-08-20T14:30', endDateTime: '2026-08-20T18:45' })

    expect(body.startDateTime).toBe('2026-08-20T19:30:00.000Z')
    expect(body.endDateTime).toBe('2026-08-20T23:45:00.000Z')
  })

  it('manda los importes como número y la justificación recortada', () => {
    const body = parsed({ weightKg: '28000', price: '4500.50', justification: `  ${JUSTIFICATION}  ` })

    expect(body.weightKg).toBe(28000)
    expect(body.price).toBe(4500.5)
    expect(body.justification).toBe(JUSTIFICATION)
  })
})
