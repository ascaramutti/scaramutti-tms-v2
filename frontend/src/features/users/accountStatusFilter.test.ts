import { describe, expect, it } from 'vitest'
import {
  ACCOUNT_STATUS_FILTER_DEFAULT,
  ACCOUNT_STATUS_FILTER_OPTIONS,
  isActiveParam,
} from './accountStatusFilter'

describe('accountStatusFilter', () => {
  it('abre en Activas', () => {
    expect(ACCOUNT_STATUS_FILTER_DEFAULT).toBe('active')
  })

  it('ofrece Activas, Inactivas y Todas, en ese orden', () => {
    expect(ACCOUNT_STATUS_FILTER_OPTIONS.map((option) => option.label)).toEqual([
      'Activas',
      'Inactivas',
      'Todas',
    ])
  })

  it('Todas no manda el parámetro', () => {
    expect(isActiveParam('active')).toBe(true)
    expect(isActiveParam('inactive')).toBe(false)
    expect(isActiveParam('all')).toBeUndefined()
  })
})
