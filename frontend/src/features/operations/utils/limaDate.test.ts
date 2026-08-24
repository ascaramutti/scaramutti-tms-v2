import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isPastInLima, todayInLima } from './limaDate'

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
