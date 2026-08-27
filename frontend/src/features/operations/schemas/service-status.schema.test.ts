import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  STATUS_NOTE_MAX_LENGTH,
  serviceProgressFormSchema,
  toServiceProgressRequest,
} from './service-status.schema'

/**
 * La guarda de fecha futura se mide contra el reloj de Lima. Bajo la zona de Lima, esa
 * cuenta y la que usa la zona del navegador dan lo mismo, así que estos casos solo
 * distinguen una de otra si el proceso corre en otra parte.
 */
const ORIGINAL_TZ = process.env.TZ

beforeAll(() => {
  process.env.TZ = 'Asia/Tokyo'
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
})
