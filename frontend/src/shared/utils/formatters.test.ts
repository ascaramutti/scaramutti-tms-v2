import { describe, expect, it } from 'vitest'
import { formatDate, formatDateOnly, formatDateTime } from './formatters'

/**
 * Los formateadores de fecha muestran la hora del negocio, no la de quien mira. Estos casos
 * usan instantes literales alrededor de las 05:00 UTC, que es la medianoche de Lima: un minuto
 * antes y un minuto después caen en días distintos allá, y la suite corre en Asia/Tokyo, donde
 * los dos instantes ya son del día siguiente. Si alguien quita el `timeZone`, el esperado deja
 * de dar.
 *
 * Van con valores escritos a mano y no calculados: un esperado que se calcula con las mismas
 * funciones que mide no mide nada.
 */
describe('formatDate — el día que es en Lima', () => {
  it('un minuto antes de la medianoche de Lima todavía es el día anterior', () => {
    expect(formatDate('2026-08-25T04:59:00Z')).toBe('24/08/2026')
  })

  it('a las 05:00 UTC ya es el día siguiente en Lima', () => {
    expect(formatDate('2026-08-25T05:00:00Z')).toBe('25/08/2026')
  })
})

describe('formatDateTime — el día y la hora que son en Lima', () => {
  it('un minuto antes de la medianoche de Lima', () => {
    expect(formatDateTime('2026-08-25T04:59:00Z')).toBe('24/08/2026, 23:59')
  })

  /** La medianoche sale como `00:00` y no como `24:00`: eso es lo que fija `hourCycle: 'h23'`. */
  it('la medianoche de Lima sale como 00:00', () => {
    expect(formatDateTime('2026-08-25T05:00:00Z')).toBe('25/08/2026, 00:00')
  })
})

describe('formatDateOnly — una fecha pura no se corre de día', () => {
  /**
   * Una fecha sin hora no tiene instante que convertir, así que sale tal cual vino. El error
   * clásico es pasarla por `new Date(iso)`, que la lee como medianoche UTC: eso corre el día
   * hacia atrás en las zonas al oeste de UTC, y Lima es una. En Tokio, donde corre esta suite,
   * no lo corre, así que el que mide acá es el segundo caso, el del texto que trae hora.
   */
  it('sale el mismo día que dice el texto', () => {
    expect(formatDateOnly('2026-08-25')).toBe('25/08/2026')
  })

  it('ignora la parte de hora si el texto la trae', () => {
    expect(formatDateOnly('2026-08-25T23:30:00Z')).toBe('25/08/2026')
  })
})
