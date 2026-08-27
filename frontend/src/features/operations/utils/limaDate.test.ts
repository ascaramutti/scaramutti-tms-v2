import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  isFutureInLima,
  isPastInLima,
  limaInputToIsoInstant,
  nowInLimaForInput,
  todayInLima,
} from './limaDate'

/**
 * Estos tests fijan el reloj a propósito. La suite del sistema no lo hacía en
 * ninguna de sus 75 suites, y por eso una fecha calculada con la zona del
 * navegador pasaba inadvertida: sin reloj fijo, el único borde que importa (las
 * horas en que la zona local y Lima están en días distintos) no se visita nunca.
 *
 * Lima es UTC-5 sin horario de verano, así que el día cambia allá a las 05:00 UTC.
 * Los instantes de abajo están elegidos alrededor de ese salto.
 */
describe('todayInLima', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('devuelve el día de Lima cuando UTC ya pasó a la fecha siguiente', () => {
    // 25/08 02:30 UTC = 24/08 21:30 en Lima. Es la ventana en que un cálculo hecho
    // con la zona del navegador (o con toISOString) adelanta el día.
    vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))

    expect(todayInLima()).toBe('2026-08-24')
    // El valor que NO queremos, escrito literal para que el test falle si el
    // helper vuelve a razonar en UTC.
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-08-25')
  })

  it('sigue en el día anterior un segundo antes de que Lima cambie de fecha', () => {
    vi.setSystemTime(new Date('2026-08-25T04:59:59Z'))

    expect(todayInLima()).toBe('2026-08-24')
  })

  it('pasa al día siguiente exactamente a las 05:00 UTC', () => {
    vi.setSystemTime(new Date('2026-08-25T05:00:00Z'))

    expect(todayInLima()).toBe('2026-08-25')
  })

  it('rellena mes y día con ceros a la izquierda', () => {
    vi.setSystemTime(new Date('2026-01-05T15:00:00Z'))

    expect(todayInLima()).toBe('2026-01-05')
  })
})

describe('isPastInLima', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // 25/08 02:30 UTC = 24/08 21:30 en Lima: en la oficina todavía es el 24.
    vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('no marca como pasada la fecha que en Lima todavía es hoy', () => {
    expect(isPastInLima('2026-08-24')).toBe(false)
  })

  it('no marca como pasado el día que en Lima todavía no empezó', () => {
    expect(isPastInLima('2026-08-25')).toBe(false)
  })

  it('marca como pasada una fecha anterior', () => {
    expect(isPastInLima('2026-08-23')).toBe(true)
  })

  it('compara por día completo, no por instante', () => {
    // El 24 a las 21:30 de Lima ya pasó buena parte del día y sigue sin ser pasado:
    // la fecha tentativa de un viaje es un día, no un momento.
    expect(isPastInLima('2026-08-24')).toBe(false)
    expect(isPastInLima('2026-12-31')).toBe(false)
  })
})

/**
 * Los tests de HORA necesitan una pata más que los de día: fijar el reloj no fija la
 * zona. `todayInLima` la ignora porque su formateador lleva `America/Lima` clavado,
 * pero la conversión de un `datetime-local` sí puede leerla, y ahí está el error que
 * este bloque existe para cazar.
 *
 * La zona la fija `vitest.config.ts` en `Asia/Tokyo` para toda la suite. Estos
 * archivos la vuelven a fijar por su cuenta igual, y no es redundante: si mañana
 * alguien saca esa línea del config, sin esto los casos de abajo pasarían a medir la
 * zona de quien los corra, y bajo Lima ninguno podría fallar.
 */
/** Lejos de Lima y del signo opuesto: en los instantes que estos casos usan, las dos
 * zonas están en días distintos, así que un error de zona no puede disfrazarse de un
 * error de minutos.
 *
 * Respeta `FORCE_TZ`, igual que el config: el pin de acá existe por si alguien saca la
 * línea de allá, no para anular la vía de escape. Sin esto,
 * `FORCE_TZ=America/Lima npm test` dejaba corriendo en Tokio justo a los archivos que
 * uno querría ver en Lima, y el guardián de abajo pasaba por el pin y no por la zona
 * real. */
const TEST_TIME_ZONE = process.env.FORCE_TZ ?? 'Asia/Tokyo'
const ORIGINAL_TZ = process.env.TZ

beforeAll(() => {
  process.env.TZ = TEST_TIME_ZONE
})

afterAll(() => {
  process.env.TZ = ORIGINAL_TZ
})

describe('la zona del proceso', () => {
  it('no es la de Lima, que es la única condición en que estos casos miden algo', () => {
    // Sin esta afirmación, correr bajo Lima deja verde todo el archivo porque el
    // cálculo correcto y el equivocado dan lo mismo. Es el guardián de los demás.
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).not.toBe('America/Lima')
    expect(new Date('2026-08-25T02:30:00Z').getTimezoneOffset()).not.toBe(300)
  })
})

describe('nowInLimaForInput', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('devuelve la hora de pared de Lima y no la del navegador', () => {
    // 25/08 02:30 UTC = 24/08 21:30 en Lima y 25/08 11:30 en Tokio. Los tres valores
    // son distintos, así que ninguno puede confundirse con otro por casualidad.
    vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))

    expect(nowInLimaForInput()).toBe('2026-08-24T21:30')
    // Los dos valores que NO queremos, escritos literales: el del navegador y el
    // recorte de toISOString. Así el test dice QUÉ salió mal, no solo que falló.
    expect(nowInLimaForInput()).not.toBe('2026-08-25T11:30')
    expect(nowInLimaForInput()).not.toBe('2026-08-25T02:30')
  })

  it('escribe la medianoche de Lima como 00:00 y nunca como 24:00', () => {
    vi.setSystemTime(new Date('2026-08-25T05:00:00Z'))

    expect(nowInLimaForInput()).toBe('2026-08-25T00:00')
  })

  it('rellena con ceros a la izquierda para que el input acepte el valor', () => {
    // Sin el relleno saldría '2026-1-5T9:07', que el input descarta y deja el campo
    // vacío sin avisar.
    vi.setSystemTime(new Date('2026-01-05T14:07:00Z'))

    expect(nowInLimaForInput()).toBe('2026-01-05T09:07')
  })
})

describe('limaInputToIsoInstant', () => {
  it('convierte el reloj de pared de Lima al instante que le corresponde', () => {
    expect(limaInputToIsoInstant('2026-08-24T21:30')).toBe('2026-08-25T02:30:00.000Z')
    // El valor que daría interpretar el texto en la zona del navegador, literal.
    expect(limaInputToIsoInstant('2026-08-24T21:30')).not.toBe(
      new Date('2026-08-24T21:30').toISOString(),
    )
  })

  it('da el mismo instante desde cualquier zona del proceso', () => {
    // Incluye a Lima a propósito: es la única zona donde el cálculo ingenuo acierta,
    // así que su presencia deja escrito que las otras tres son las que miden.
    //
    // El `finally` no es adorno: sin él, una aserción que falle en la segunda zona deja
    // el proceso ahí, y los `describe` siguientes se caen en cascada bajo una zona que
    // nadie eligió. Serían rojos que parecen del código y son del armado.
    try {
      for (const timeZone of ['Asia/Tokyo', 'Pacific/Honolulu', 'UTC', 'America/Lima']) {
        process.env.TZ = timeZone

        expect(limaInputToIsoInstant('2026-08-24T21:30')).toBe('2026-08-25T02:30:00.000Z')
      }
    } finally {
      process.env.TZ = TEST_TIME_ZONE
    }
  })

  it('conserva los minutos', () => {
    // 21:30 se camufla con un desplazamiento de media hora; 21:07 no.
    expect(limaInputToIsoInstant('2026-08-24T21:07')).toBe('2026-08-25T02:07:00.000Z')
  })

  it('cruza el límite del día sin correrlo', () => {
    // 19:00 en Lima ya es del día siguiente en UTC.
    expect(limaInputToIsoInstant('2026-08-24T19:00')).toBe('2026-08-25T00:00:00.000Z')
    expect(limaInputToIsoInstant('2026-08-25T00:00')).toBe('2026-08-25T05:00:00.000Z')
  })

  it('acierta cuando la corrección aterriza del otro lado de un cambio de hora', () => {
    // El 1 de enero de 1994 Perú adelantó los relojes. Para esa hora de pared, medir
    // el desplazamiento una sola vez lo mide del lado equivocado del salto y el
    // resultado sale corrido una hora entera: 06:00Z en vez de 05:00Z. Es el único
    // caso del archivo que ejercita la segunda pasada de la conversión, y sin él ese
    // bucle se puede recortar a una vuelta sin que nada se ponga en rojo.
    expect(limaInputToIsoInstant('1994-01-01T01:00')).toBe('1994-01-01T05:00:00.000Z')
    expect(limaInputToIsoInstant('1994-01-01T01:00')).not.toBe('1994-01-01T06:00:00.000Z')
  })

  it('devuelve un instante real para una hora de pared que no existió', () => {
    // El 1 de enero de 1994 los relojes saltaron de las 23:59 a la 01:00, así que las
    // 00:00 de ese día nunca ocurrieron en Lima. No hay instante que les corresponda:
    // el resultado es el de antes del salto, y el caso lo fija para que nadie lea la
    // conversión como una ida y vuelta que siempre coincide.
    expect(limaInputToIsoInstant('1994-01-01T00:00')).toBe('1994-01-01T04:00:00.000Z')
  })

  it('acepta el valor con segundos que devuelven algunos navegadores', () => {
    expect(limaInputToIsoInstant('2026-08-24T21:30:00')).toBe('2026-08-25T02:30:00.000Z')
  })

  it('ancla la zona por su nombre y no por un -05:00 fijo', () => {
    // Enero de 1994 cae dentro de la ventana en que Perú adelantó los relojes, y ahí
    // el desplazamiento fue de cuatro horas y no de cinco. Los dos valores de abajo
    // son lo que separa una cosa de la otra: la conversión anclada al nombre de la
    // zona da 01:30, y escribir '-05:00' a mano daría 02:30. Este caso es el único
    // del archivo donde las dos implementaciones difieren.
    const summerOfNinetyFour = limaInputToIsoInstant('1994-01-15T21:30')

    expect(summerOfNinetyFour).toBe('1994-01-16T01:30:00.000Z')
    expect(summerOfNinetyFour).not.toBe('1994-01-16T02:30:00.000Z')
  })
})

describe('isFutureInLima', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // 24/08 21:30 en Lima; 25/08 11:30 en Tokio.
    vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('no considera futuro al instante exacto', () => {
    // Es el valor con el que viene precargado el campo: con el borde al revés, el
    // formulario nacería inválido sin que nadie lo toque.
    expect(isFutureInLima('2026-08-24T21:30')).toBe(false)
  })

  it('considera futuro al minuto siguiente en Lima', () => {
    // Bajo Tokio, medir contra el reloj del navegador daría este valor por pasado
    // (allá ya son las 11:30 del 25) y la guarda lo dejaría pasar.
    expect(isFutureInLima('2026-08-24T21:31')).toBe(true)
  })

  it('no rechaza una hora que en Lima ya pasó', () => {
    expect(isFutureInLima('2026-08-24T19:30')).toBe(false)
  })

  it('compara al minuto, ignorando los segundos del input', () => {
    expect(isFutureInLima('2026-08-24T21:30:45')).toBe(false)
  })
})
