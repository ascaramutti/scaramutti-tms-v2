import { describe, expect, it } from 'vitest'
import { toClientUpdateRequest } from './useUpdateClient'

/**
 * El mapeo del formulario al cuerpo, sin pantalla.
 *
 * Vale aparte del test de la pantalla porque acá el borde se mide sin montar
 * nada: sobrevive a cualquier rediseño del formulario y corre en milisegundos.
 */
describe('toClientUpdateRequest', () => {
  const base = { name: 'ACME S.A.C.', ruc: '20123456789', phone: '987654321', contactName: 'Ana' }

  it('manda el teléfono vacío como nulo y no como cadena vacía', () => {
    // La cadena vacía no cumple el patrón del backend y volvería como un 400.
    expect(toClientUpdateRequest({ ...base, phone: '' }).phone).toBeNull()
  })

  it('manda el contacto vacío como nulo y no como cadena vacía', () => {
    expect(toClientUpdateRequest({ ...base, contactName: '' }).contactName).toBeNull()
  })

  it('conserva el teléfono y el contacto cargados', () => {
    const cuerpo = toClientUpdateRequest(base)
    expect(cuerpo.phone).toBe('987654321')
    expect(cuerpo.contactName).toBe('Ana')
  })

  it('recorta los espacios de los bordes', () => {
    expect(toClientUpdateRequest({ ...base, name: '  ACME S.A.C.  ' }).name).toBe('ACME S.A.C.')
  })

  it('manda los cuatro campos editables y ninguno más', () => {
    const cuerpo = toClientUpdateRequest(base)
    expect(Object.keys(cuerpo).sort()).toEqual(['contactName', 'name', 'phone', 'ruc'])
  })
})
