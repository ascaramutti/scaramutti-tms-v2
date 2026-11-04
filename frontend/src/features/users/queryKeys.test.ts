import { describe, expect, it } from 'vitest'
import { userAccountKeys } from './queryKeys'

// Literales y no las mismas funciones: si `list` y `lists` cambian juntas, invalidar las listas
// dejaría de alcanzar a alguna sin que la aserción lo note.
describe('userAccountKeys', () => {
  it('la raíz y las listas son propias del módulo', () => {
    expect(userAccountKeys.all).toEqual(['users'])
    expect(userAccountKeys.lists()).toEqual(['users', 'list'])
  })

  it('una lista cuelga de la rama de listas', () => {
    expect(userAccountKeys.list({ isActive: true })).toEqual(['users', 'list', { isActive: true }])
  })

  it('cada estado es una entrada distinta de caché', () => {
    const activas = userAccountKeys.list({ isActive: true })
    const inactivas = userAccountKeys.list({ isActive: false })
    const todas = userAccountKeys.list({})
    expect(activas).not.toEqual(inactivas)
    expect(activas).not.toEqual(todas)
    expect(inactivas).not.toEqual(todas)
  })
})
