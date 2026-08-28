import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SERVICE_EXIT_REASON_MIN_LENGTH,
  STATUS_NOTE_MAX_LENGTH,
  serviceExitFormSchema,
  serviceProgressFormSchema,
  toServiceExitRequest,
  toServiceProgressRequest,
} from './service-status.schema'

/**
 * La guarda de fecha futura se mide contra el reloj de Lima. Bajo la zona de Lima, esa
 * cuenta y la que usa la zona del navegador dan lo mismo, así que estos casos solo
 * distinguen una de otra si el proceso corre en otra parte.
 */
/** Lejos de Lima y del signo opuesto: en los instantes que estos casos usan, las
 * dos zonas están en días distintos, así que un error de zona no puede disfrazarse
 * de un error de minutos.
 *
 * Respeta `FORCE_TZ`, igual que el config: el pin de acá existe por si alguien saca
 * la línea de allá, no para anular la vía de escape. Sin esto,
 * `FORCE_TZ=America/Lima npm test` dejaba corriendo en Tokio justo a los archivos
 * que uno querría ver en Lima, y el guardián de abajo pasaba por el pin y no por
 * la zona real. */
const TEST_TIME_ZONE = process.env.FORCE_TZ ?? 'Asia/Tokyo'
const ORIGINAL_TZ = process.env.TZ

beforeAll(() => {
  process.env.TZ = TEST_TIME_ZONE
})

afterAll(() => {
  process.env.TZ = ORIGINAL_TZ
})

/** 25/08 02:30 UTC = 24/08 21:30 en Lima y 25/08 11:30 en Tokio. */
const NOW = new Date('2026-08-25T02:30:00Z')
const NOW_IN_LIMA = '2026-08-24T21:30'

function parseProgress(values: { dateTime?: string; note?: string }) {
  return serviceProgressFormSchema.safeParse({
    dateTime: NOW_IN_LIMA,
    note: '',
    ...values,
  })
}

describe('serviceProgressFormSchema, la fecha', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('acepta el instante que el campo trae precargado', () => {
    // El campo nace con este valor. Con el borde al revés, el formulario saldría
    // inválido de fábrica y nadie podría iniciar un viaje sin tocar la fecha.
    expect(parseProgress({ dateTime: NOW_IN_LIMA }).success).toBe(true)
  })

  it('rechaza el minuto siguiente en Lima', () => {
    // Medido contra el reloj del navegador, en Tokio ya son las 11:30 del 25 y este
    // valor pasaría por pasado.
    const result = parseProgress({ dateTime: '2026-08-24T21:31' })

    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['dateTime'])
    expect(result.error?.issues[0]?.message).toBe('La fecha no puede estar en el futuro')
  })

  it('acepta una hora que en Lima ya pasó', () => {
    expect(parseProgress({ dateTime: '2026-08-24T19:30' }).success).toBe(true)
  })

  it('acepta una fecha vieja, porque el servidor solo acota la ventana de negocio', () => {
    // Una guarda de "no muy viejo" que el contrato no pide dejaría sin registrar los
    // viajes que se cargan tarde.
    expect(parseProgress({ dateTime: '2020-01-01T08:00' }).success).toBe(true)
  })

  it('exige la fecha', () => {
    const result = parseProgress({ dateTime: '' })

    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['dateTime'])
  })

  it.each(['2026-08-24', 'mañana', '24/08/2026 21:30'])('rechaza %s, que no es un instante', (value) => {
    // Sin el patrón, estos textos llegarían al conversor y saldrían como fecha inválida.
    expect(parseProgress({ dateTime: value }).success).toBe(false)
  })

  it('acepta el valor con segundos que agregan algunos navegadores', () => {
    expect(parseProgress({ dateTime: '2026-08-24T19:30:00' }).success).toBe(true)
  })
})

describe('serviceProgressFormSchema, la nota', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('acepta que no haya nota', () => {
    // Es opcional al avanzar el viaje. La transición que la exige es cancelar, y su
    // schema es otro: copiarle el mínimo a este es el error más fácil de cometer.
    expect(parseProgress({ note: '' }).success).toBe(true)
  })

  it('acepta una nota de un solo caracter', () => {
    expect(parseProgress({ note: 'x' }).success).toBe(true)
  })

  it('acepta una nota de exactamente el máximo', () => {
    expect(parseProgress({ note: 'x'.repeat(STATUS_NOTE_MAX_LENGTH) }).success).toBe(true)
  })

  it('rechaza una nota de un caracter más que el máximo', () => {
    const result = parseProgress({ note: 'x'.repeat(STATUS_NOTE_MAX_LENGTH + 1) })

    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['note'])
  })

  it('rechaza los caracteres de control', () => {
    // El NUL no sobrevive en una columna de texto de PostgreSQL, y llega hasta el motor
    // si nadie lo corta antes.
    expect(parseProgress({ note: 'salida\u0000 con escolta' }).success).toBe(false)
  })
})

describe('toServiceProgressRequest', () => {
  it('manda la hora tipeada como el instante que le corresponde en Lima', () => {
    const body = toServiceProgressRequest(
      { dateTime: NOW_IN_LIMA, note: '' },
      'IN_PROGRESS',
    )

    expect(body.dateTime).toBe('2026-08-25T02:30:00.000Z')
    // Lo que daría interpretar el texto en la zona del navegador, escrito literal.
    expect(body.dateTime).not.toBe(new Date(NOW_IN_LIMA).toISOString())
  })

  it('manda el destino que se le pide', () => {
    expect(toServiceProgressRequest({ dateTime: NOW_IN_LIMA, note: '' }, 'IN_PROGRESS').target).toBe(
      'IN_PROGRESS',
    )
    expect(toServiceProgressRequest({ dateTime: NOW_IN_LIMA, note: '' }, 'COMPLETED').target).toBe(
      'COMPLETED',
    )
  })

  it('recorta la nota por los dos lados', () => {
    // Los espacios van a los dos lados: un recorte a medias sobrevive si solo hay
    // espacios al principio.
    const body = toServiceProgressRequest(
      { dateTime: NOW_IN_LIMA, note: '  Salió con demora  ' },
      'IN_PROGRESS',
    )

    expect(body.note).toBe('Salió con demora')
  })

  it('manda la nota en blanco como null y nunca como cadena vacía', () => {
    // Una cadena vacía escribiría una entrada en blanco en la bitácora del viaje.
    expect(toServiceProgressRequest({ dateTime: NOW_IN_LIMA, note: '   ' }, 'IN_PROGRESS').note).toBe(
      null,
    )
  })

  it('no manda la bandera de forzado', () => {
    // Es la única que autoriza al servidor a pisar la reja de conflictos, solo aplica
    // al reabrir, y mandarla en cualquier otra transición es un rechazo.
    const body = toServiceProgressRequest({ dateTime: NOW_IN_LIMA, note: '' }, 'IN_PROGRESS')

    expect('force' in body).toBe(false)
  })

  it('limpia los caracteres de control aunque el schema ya los rechace', () => {
    // Igual que en la cancelación: por la aplicación no llega, y por eso esta segunda
    // reja se mide sola.
    const body = toServiceProgressRequest(
      { dateTime: NOW_IN_LIMA, note: 'salió\u0000 con escolta' },
      'IN_PROGRESS',
    )

    expect(body.note).toBe('salió con escolta')
  })
})

describe('serviceExitFormSchema', () => {
  function parseCancel(note: string) {
    return serviceExitFormSchema.safeParse({ note })
  }

  it('rechaza un motivo de un caracter menos que el mínimo', () => {
    const result = parseCancel('x'.repeat(SERVICE_EXIT_REASON_MIN_LENGTH - 1))

    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['note'])
    expect(result.error?.issues[0]?.message).toBe('El motivo debe tener al menos 10 caracteres')
  })

  it('acepta un motivo de exactamente el mínimo', () => {
    // El vecino del caso anterior. Con uno solo, un mínimo escrito de más pasa igual.
    expect(parseCancel('x'.repeat(SERVICE_EXIT_REASON_MIN_LENGTH)).success).toBe(true)
  })

  it('acepta un motivo de exactamente el máximo', () => {
    expect(parseCancel('x'.repeat(STATUS_NOTE_MAX_LENGTH)).success).toBe(true)
  })

  it('rechaza un motivo de un caracter más que el máximo', () => {
    // El mínimo no reemplaza al máximo: son dos bordes y cada uno necesita su par.
    expect(parseCancel('x'.repeat(STATUS_NOTE_MAX_LENGTH + 1)).success).toBe(false)
  })

  it('no acepta un motivo hecho solo de espacios', () => {
    // Doce espacios pasan un mínimo de diez medido antes del recorte, así que este
    // largo es el que distingue las dos implementaciones.
    expect(parseCancel(' '.repeat(12)).success).toBe(false)
  })

  it('exige el motivo', () => {
    expect(parseCancel('').success).toBe(false)
  })

  it('rechaza los caracteres de control', () => {
    // El motivo es el único texto obligatorio de la entrega: siempre viaja, y va a una
    // columna de texto de PostgreSQL, que no admite el byte NUL. La nota del avance ya
    // tenía su caso y este se había quedado sin el suyo, que es el hueco clásico de
    // copiar la regla y no el caso.
    expect(parseCancel('reprogramó\u0000 la obra').success).toBe(false)
  })
})

describe('toServiceExitRequest', () => {
  const VALID_REASON = 'El cliente reprogramó la obra'

  it('manda el destino y el motivo recortado', () => {
    const body = toServiceExitRequest({ note: `  ${VALID_REASON}  ` }, 'CANCELLED')

    expect(body.target).toBe('CANCELLED')
    expect(body.note).toBe(VALID_REASON)
  })

  it('omite la fecha en vez de mandarla en null', () => {
    // Las dos formas funcionan (el servidor mira el valor, no la presencia de la
    // clave), así que la elección es por cuerpo mínimo. Se afirma sobre la CLAVE
    // porque `toBeUndefined()` no distingue "no está" de "está en null".
    const body = toServiceExitRequest({ note: VALID_REASON }, 'CANCELLED')

    expect('dateTime' in body).toBe(false)
  })

  it('no manda la bandera de forzado', () => {
    expect('force' in toServiceExitRequest({ note: VALID_REASON }, 'CANCELLED')).toBe(false)
  })

  it.each(['CANCELLED', 'DELETED', 'REOPENED'] as const)('manda el destino %s', (target) => {
    expect(toServiceExitRequest({ note: VALID_REASON }, target).target).toBe(target)
  })

  it('manda la bandera de forzado SOLO al reabrir y solo cuando se pidió', () => {
    // Es la única bandera que autoriza al servidor a pisar la reja de conflictos, así que
    // no viaja por defecto: ausente y `false` son lo mismo para él, y un cuerpo mínimo no
    // deja lugar a que alguien lea la clave como que acá se puede forzar siempre.
    const forzado = toServiceExitRequest({ note: VALID_REASON }, 'REOPENED', true)
    const sinForzar = toServiceExitRequest({ note: VALID_REASON }, 'REOPENED', false)

    expect(forzado.force).toBe(true)
    expect('force' in sinForzar).toBe(false)
  })

  it('limpia los caracteres de control aunque el schema ya los rechace', () => {
    // Se le pasa el valor SIN pasar por el schema, a propósito: por la aplicación esto
    // no llega nunca (el schema lo corta antes), y por eso la limpieza del mapper no
    // tenía quien la matara. Es la segunda reja de la misma regla y se mide aparte,
    // porque el día que alguien arme el cuerpo por otro camino es la única que queda.
    const body = toServiceExitRequest({ note: 'reprogramó\u0000 la obra' }, 'CANCELLED')

    expect(body.note).toBe('reprogramó la obra')
  })
})
