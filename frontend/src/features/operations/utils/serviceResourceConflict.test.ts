import { describe, expect, it } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { getServiceOperationError } from './serviceResourceConflict'

/** Un error del backend con el `Problem` que se quiera. */
function apiError(data: unknown, status = 409) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('fallo', 'ERR', config, null, {
    status,
    statusText: '',
    headers: {},
    config,
    data,
  })
}

const FORCIBLE = {
  type: 'urn:tms:error:ops-002',
  title: 'Resource conflict',
  status: 409,
  detail: 'El conductor Juan Pérez Huamán ya está asignado al servicio SRV-0042 (en ruta).',
  code: 'OPS-002',
  forcible: true,
  conflicts: [
    {
      resource: 'DRIVER',
      resourceName: 'Juan Pérez Huamán',
      serviceCode: 'SRV-0042',
      serviceStatus: 'IN_PROGRESS',
    },
  ],
}

describe('getServiceOperationError', () => {
  it('lee el código, el detalle y los conflictos de un forzable', () => {
    const result = getServiceOperationError(apiError(FORCIBLE))
    expect(result?.code).toBe('OPS-002')
    expect(result?.detail).toBe(FORCIBLE.detail)
    expect(result?.forcible).toBe(true)
    expect(result?.conflicts).toHaveLength(1)
  })

  it('el conflicto duro no es forzable, y llega sin conflictos', () => {
    // El `Problem` viaja PELADO: sin `forcible` y sin `conflicts`, que es la forma
    // real del duplicado en el mismo viaje.
    const result = getServiceOperationError(
      apiError({
        status: 409,
        detail: 'El conductor Ana Ríos Chávez ya participa de este servicio.',
        code: 'OPS-003',
      }),
    )
    expect(result?.forcible).toBe(false)
    expect(result?.conflicts).toEqual([])
  })

  it('la bandera SIN el código de conflicto no habilita forzar', () => {
    // Es la promesa del javadoc, y el único caso que la mide: el día que `forcible`
    // aparezca en otro código, la pantalla no puede ofrecer un botón que el servidor
    // va a rechazar igual. Sin este caso, quedarse solo con la bandera sobrevive.
    const result = getServiceOperationError(
      apiError({ status: 409, detail: 'Otro conflicto', code: 'OPS-008', forcible: true }),
    )
    expect(result?.code).toBe('OPS-008')
    expect(result?.forcible).toBe(false)
  })

  it('el código de conflicto SIN la bandera tampoco', () => {
    // La otra dirección: exigir las dos cosas es lo que se está midiendo, así que
    // hacen falta los dos casos cruzados.
    const result = getServiceOperationError(
      apiError({ status: 409, detail: 'Conflicto sin bandera', code: 'OPS-002' }),
    )
    expect(result?.forcible).toBe(false)
  })

  it('un error que no es de la API no devuelve nada', () => {
    expect(getServiceOperationError(new Error('se cayó la red'))).toBeNull()
  })

  it('una respuesta de la API sin cuerpo no devuelve nada', () => {
    // Es el camino del 500 pelado: sin `Problem` no hay nada que mostrar, y la
    // pantalla tiene que caer en su mensaje propio.
    expect(getServiceOperationError(apiError(undefined, 500))).toBeNull()
  })
})
