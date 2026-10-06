import { afterEach, describe, expect, it, vi } from 'vitest'
import { CE, DNI, ROLES, fakeDriverProfile, fakeWorkerDetail } from '../../../test/mocks/handlers/workers'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  buildWorkerFormSchema,
  PERSON_NAME,
  toWorkerRequest,
  documentNumberChanged,
  toWorkerUpdateRequest,
  workerCreateDefaults,
  workerEditDefaults,
  type WorkerFormValues,
} from './worker.schema'

const HOY = '2026-08-24'
const schema = buildWorkerFormSchema({ documentTypes: [DNI, CE], roles: ROLES, today: () => HOY })

function valores(cambios: Partial<WorkerFormValues> = {}): WorkerFormValues {
  return {
    ...workerCreateDefaults([DNI, CE], () => HOY),
    firstName: 'Juan',
    lastName: 'Pérez',
    documentNumber: '45678912',
    role: 'operator',
    ...cambios,
  }
}

/** Los mensajes por campo, como los ve el formulario: el primero de cada campo. */
function errores(cambios: Partial<WorkerFormValues> = {}) {
  const resultado = schema.safeParse(valores(cambios))
  if (resultado.success) return {}
  const porCampo: Record<string, string> = {}
  for (const issue of resultado.error.issues) porCampo[issue.path.join('.')] ??= issue.message
  return porCampo
}

describe('buildWorkerFormSchema', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('acepta un alta bien formada sin licencia', () => {
    expect(errores()).toEqual({})
  })

  it.each([
    ['firstName', ''],
    ['firstName', '   '],
    ['lastName', ''],
    ['lastName', '   '],
    ['documentNumber', '   '],
  ] as const)('%s vacío se rechaza', (campo, valor) => {
    expect(errores({ [campo]: valor })[campo]).toBeDefined()
  })

  it('el tipo de documento es obligatorio', () => {
    expect(errores({ documentTypeId: null }).documentTypeId).toBe('Elige el tipo de documento.')
  })

  it('un patrón que JavaScript no compila no rompe el formulario y deja el largo vigente', () => {
    const javaSolo = { ...DNI, validationPattern: '\\d++' }
    const conJavaSolo = buildWorkerFormSchema({ documentTypes: [javaSolo], roles: ROLES, today: () => HOY })

    expect(conJavaSolo.safeParse(valores({ documentNumber: 'ABC' })).success).toBe(true)
    expect(conJavaSolo.safeParse(valores({ documentNumber: '456789123' })).success).toBe(false)
  })

  it.each([['María José'], ["D'Angelo"], ['Pérez-Gómez'], ['Ñuñez Müller'], ['Šimić'], ['Łukasz'], ['Mª José'], ["'t Hooft"]])(
    'nombre y apellido aceptan "%s"',
    (valor) => {
      expect(errores({ firstName: valor, lastName: valor })).toEqual({})
    },
  )

  it('una tilde escrita como letra más marca se acepta y sale en NFC', () => {
    const nfd = 'Jose\u0301'
    const resultado = schema.safeParse(valores({ firstName: nfd, lastName: nfd }))

    expect(resultado.success).toBe(true)
    expect(resultado.data?.firstName).toBe('Jos\u00e9')
    expect(resultado.data?.lastName).toBe('Jos\u00e9')
  })

  it.each([
    ['6564565'],
    ['Juan2'],
    ['Ana@'],
    ['2Juan'],
    ['@Ana'],
    ['J. Pérez'],
    ["'"],
    ['-'],
    ['\u3164'],
    ['Ju\u0430n'],
    ['\u{1D409}uan'],
    ['\u2160'],
    ['Juan \u2161'],
    ['Pe\u0303g\u0303a'],
  ])('nombre y apellido rechazan "%s"', (valor) => {
    expect(errores({ firstName: valor, lastName: valor })).toEqual({
      firstName: 'Solo letras, espacios, apóstrofo o guion.',
      lastName: 'Solo letras, espacios, apóstrofo o guion.',
    })
  })

  it('nombre y apellido llegan a 100, no a 101', () => {
    expect(errores({ firstName: 'A'.repeat(100), lastName: 'B'.repeat(100) })).toEqual({})
    expect(errores({ firstName: 'A'.repeat(101) }).firstName).toBe('Máximo 100 caracteres.')
    expect(errores({ lastName: 'B'.repeat(101) }).lastName).toBe('Máximo 100 caracteres.')
  })

  /** El formulario recorta antes de medir: cien letras con espacios alrededor entran. */
  it('nombre y apellido se miden ya recortados', () => {
    expect(errores({ firstName: ' ' + 'A'.repeat(100) + ' ', lastName: ' ' + 'B'.repeat(100) + ' ' })).toEqual({})
  })

  /**
   * Un texto gigante pegado avisa el largo sin medir la regla: sobre millones de caracteres desbordaría
   * la pila (en este Node, entre 4M y 9M). Si un motor aguanta más, este caso deja de ver la guarda;
   * el de 100 caracteres de abajo fija su borde.
   */
  it('un nombre gigante avisa el largo sin lanzar', () => {
    const gigante = 'a'.repeat(9_000_000)

    expect(errores({ firstName: gigante, lastName: gigante })).toEqual({
      firstName: 'Máximo 100 caracteres.',
      lastName: 'Máximo 100 caracteres.',
    })
  })

  it('con 100 caracteres justos la regla se mide', () => {
    expect(errores({ firstName: 'A'.repeat(99) + '1', lastName: 'B'.repeat(99) + '1' })).toEqual({
      firstName: 'Solo letras, espacios, apóstrofo o guion.',
      lastName: 'Solo letras, espacios, apóstrofo o guion.',
    })
  })

  it('un nombre gigante no apaga las demás validaciones', () => {
    expect(errores({ firstName: 'a'.repeat(9_000_000), documentNumber: '4567891' })).toEqual({
      firstName: 'Máximo 100 caracteres.',
      documentNumber: 'El número no tiene el formato de DNI.',
    })
  })

  /** Como en el backend: reordenar miles de marcas cuesta tiempo cuadrático, así que pasado 400 no se normaliza. */
  it('pasado 400 caracteres no se normaliza, y con 400 sí', () => {
    const normalize = vi.spyOn(String.prototype, 'normalize')
    const normalizados = () => normalize.mock.contexts.map(String)
    const conCuatrocientos = 'a' + '\u0301'.repeat(399)
    const conCuatrocientosUno = 'a' + '\u0301'.repeat(400)

    errores({ firstName: conCuatrocientos, lastName: conCuatrocientosUno })
    expect(normalizados()).toContain(conCuatrocientos)
    expect(normalizados()).not.toContain(conCuatrocientosUno)
  })

  /** Cien letras latinas de tres caracteres cada una (la composición latina más larga) caben en el tope. */
  it('cien letras descompuestas en tres caracteres se normalizan y entran', () => {
    const cien = 'u\u0308\u0301'.repeat(100)
    expect(cien).toHaveLength(300)
    expect(errores({ firstName: cien, lastName: cien })).toEqual({})
  })

  /** Los mismos casos que mide el backend contra su regla: así las dos quedan atadas al contrato. */
  it('los casos comunes entran o quedan afuera como en el backend', () => {
    const cases = JSON.parse(
      readFileSync(join(import.meta.dirname, '../../../../../backend/src/test/resources/workers/person-names.json'), 'utf8'),
    ) as { accepted: string[]; rejected: string[] }

    for (const value of cases.accepted) expect(PERSON_NAME.test(value.normalize('NFC')), value).toBe(true)
    for (const value of cases.rejected) expect(PERSON_NAME.test(value.normalize('NFC')), value).toBe(false)
  })

  /** La regla corre antes que el tope: con clases que se solapan, un texto largo que falla tardaría. */
  it('la regla de nombre rechaza un texto largo en tiempo lineal', () => {
    const start = performance.now()
    expect(PERSON_NAME.test('a'.repeat(200_000) + '1')).toBe(false)
    expect(performance.now() - start).toBeLessThan(2_000)
  })

  /** El backend aplica la misma regla: el contrato publica este patrón para los dos campos. */
  it('la regla de nombre es la que publica el contrato', () => {
    const spec = readFileSync(
      join(import.meta.dirname, '../../../../../backend/src/main/resources/META-INF/openapi.yaml'),
      'utf8',
    )
    const worker = spec.slice(spec.indexOf('    WorkerRequest:'))
    const patterns = [...worker.matchAll(/^ {8}(firstName|lastName): .*pattern: '((?:[^']|'')*)'/gm)].slice(0, 2)

    expect(patterns.map((match) => match[1])).toEqual(['firstName', 'lastName'])
    for (const match of patterns) expect(match[2].replaceAll("''", "'")).toBe(PERSON_NAME.source)
  })

  it('el tope de 100 se mide ya normalizado', () => {
    const cienEnNfc = 'A'.repeat(99) + 'e\u0301'
    expect(errores({ firstName: cienEnNfc, lastName: cienEnNfc })).toEqual({})
  })

  it('el número sigue el patrón y el largo del tipo elegido', () => {
    expect(errores({ documentNumber: '4567891' }).documentNumber).toBe('El número no tiene el formato de DNI.')
    expect(errores({ documentNumber: '4567891a' }).documentNumber).toBe('El número no tiene el formato de DNI.')
    expect(errores({ documentNumber: '456789123' }).documentNumber).toBe('Máximo 8 caracteres para DNI.')
    expect(errores({ documentTypeId: CE.id, documentNumber: 'A'.repeat(12) })).toEqual({})
    expect(errores({ documentTypeId: CE.id, documentNumber: 'A'.repeat(13) }).documentNumber).toBe(
      'Máximo 12 caracteres para Carné de extranjería.',
    )
  })

  /** El patrón se aplica al número entero: uno que solo lo contiene no alcanza. */
  it('el patrón del tipo se ancla al número entero', () => {
    const conPatron = buildWorkerFormSchema({
      documentTypes: [{ id: 9, code: 'X', name: 'X', maxLength: 10, validationPattern: 'A\\d{3}' }],
      roles: ROLES,
      today: () => HOY,
    })
    const base = { ...valores(), documentTypeId: 9 }
    expect(conPatron.safeParse({ ...base, documentNumber: 'A123' }).success).toBe(true)
    expect(conPatron.safeParse({ ...base, documentNumber: 'XA123' }).success).toBe(false)
    expect(conPatron.safeParse({ ...base, documentNumber: 'A1234' }).success).toBe(false)
  })

  /** Con alternancia, el ancla abraza a todas las ramas y no solo a la primera o la última. */
  it('el ancla vale para cada rama de un patrón con alternancia', () => {
    const alterno = buildWorkerFormSchema({
      documentTypes: [{ id: 9, code: 'X', name: 'X', maxLength: 10, validationPattern: 'A\\d|B\\d' }],
      roles: ROLES,
      today: () => HOY,
    })
    const base = { ...valores(), documentTypeId: 9 }
    expect(alterno.safeParse({ ...base, documentNumber: 'B1' }).success).toBe(true)
    expect(alterno.safeParse({ ...base, documentNumber: 'XB1' }).success).toBe(false)
    expect(alterno.safeParse({ ...base, documentNumber: 'A1X' }).success).toBe(false)
  })

  /** El backend lee el patrón con Java: una clase Unicode tiene que significar lo mismo acá. */
  it('el patrón se lee en modo Unicode', () => {
    const unicode = buildWorkerFormSchema({
      documentTypes: [{ id: 9, code: 'X', name: 'X', maxLength: 10, validationPattern: '\\p{Lu}\\d{3}' }],
      roles: ROLES,
      today: () => HOY,
    })
    const base = { ...valores(), documentTypeId: 9 }
    expect(unicode.safeParse({ ...base, documentNumber: 'Á123' }).success).toBe(true)
    expect(unicode.safeParse({ ...base, documentNumber: 'a123' }).success).toBe(false)
  })

  it('el largo y el patrón se miden sin los espacios de los bordes', () => {
    expect(errores({ documentNumber: ' 45678912 ' })).toEqual({})
  })

  it('el tope de 20 del contrato vale aunque el tipo admita más', () => {
    const largo = buildWorkerFormSchema({
      documentTypes: [{ id: 9, code: 'X', name: 'X', maxLength: 30, validationPattern: null }],
      roles: ROLES,
      today: () => HOY,
    })
    expect(largo.safeParse({ ...valores(), documentTypeId: 9, documentNumber: 'A'.repeat(21) }).success).toBe(false)
  })

  it('el teléfono es vacío o de 9 dígitos', () => {
    expect(errores({ phone: '' })).toEqual({})
    expect(errores({ phone: '   ' })).toEqual({})
    expect(errores({ phone: '987654321' })).toEqual({})
    for (const malo of ['12345678', '1234567890', '98765432a']) {
      expect(errores({ phone: malo }).phone).toBe('El teléfono debe tener 9 dígitos.')
    }
  })

  it('el cargo es obligatorio', () => {
    expect(errores({ role: null }).role).toBe('Elige el cargo.')
  })

  it('la fecha de ingreso es obligatoria y no futura; hoy vale', () => {
    expect(errores({ hireDate: '' }).hireDate).toBe('Indica la fecha de ingreso.')
    expect(errores({ hireDate: '2026-08-25' }).hireDate).toBe('La fecha de ingreso no puede ser futura.')
    expect(errores({ hireDate: HOY })).toEqual({})
  })

  it('con un cargo que la exige, la licencia es obligatoria y llega a 20', () => {
    const driver = (licenseNumber: string, licenseCategory = '') =>
      ({ licenseNumber, licenseCategory, status: 'AVAILABLE' }) as const
    expect(errores({ role: 'driver', driver: driver('') })['driver.licenseNumber']).toBe('Indica el número de licencia.')
    expect(errores({ role: 'driver', driver: driver('   ') })['driver.licenseNumber']).toBe('Indica el número de licencia.')
    expect(errores({ role: 'driver', driver: driver(` ${'A'.repeat(20)} `) })).toEqual({})
    expect(errores({ role: 'driver', driver: driver('A'.repeat(20)) })).toEqual({})
    expect(errores({ role: 'driver', driver: driver('A'.repeat(21)) })['driver.licenseNumber']).toBe('Máximo 20 caracteres.')
    expect(errores({ role: 'driver', driver: driver('Q1', 'C'.repeat(21)) })['driver.licenseCategory']).toBe(
      'Máximo 20 caracteres.',
    )
  })

  it('no valida la licencia que no viaja', () => {
    expect(errores({ role: 'assistant', hasLicense: false })).toEqual({})
    expect(errores({ role: 'operator' })).toEqual({})
    expect(errores({ role: 'assistant', hasLicense: true })['driver.licenseNumber']).toBeDefined()
  })

  /** Un campo inválido no esconde a los demás: todos los errores salen en el mismo envío. */
  it('reporta los errores de forma y los de la licencia juntos', () => {
    const todos = errores({ firstName: '', role: 'driver' })
    expect(todos.firstName).toBeDefined()
    expect(todos['driver.licenseNumber']).toBeDefined()
  })
})

describe('workerCreateDefaults', () => {
  it('abre con el primer tipo, la fecha de hoy y la licencia disponible', () => {
    expect(workerCreateDefaults([CE, DNI], () => HOY)).toMatchObject({
      documentTypeId: CE.id,
      hireDate: HOY,
      role: null,
      hasLicense: false,
      driver: { status: 'AVAILABLE' },
    })
  })
})

describe('toWorkerRequest', () => {
  it('recorta los textos y manda el teléfono vacío como null, sin licencia', () => {
    const cuerpo = toWorkerRequest(
      valores({ firstName: ' Juan ', lastName: ' Pérez ', documentNumber: ' 45678912 ', phone: '  ' }),
      ROLES,
    )
    expect(cuerpo).toEqual({
      firstName: 'Juan',
      lastName: 'Pérez',
      documentTypeId: DNI.id,
      documentNumber: '45678912',
      phone: null,
      role: 'operator',
      hireDate: HOY,
    })
    expect(cuerpo).not.toHaveProperty('driver')
  })

  it('con un cargo que la exige, la licencia viaja con la categoría vacía como null', () => {
    const cuerpo = toWorkerRequest(
      valores({ role: 'driver', driver: { licenseNumber: ' Q1 ', licenseCategory: ' ', status: 'AVAILABLE' } }),
      ROLES,
    )
    expect(cuerpo.driver).toEqual({ licenseNumber: 'Q1', licenseCategory: null, status: 'AVAILABLE' })
  })

  it('opcional: viaja solo con la casilla marcada', () => {
    const conTexto = { licenseNumber: 'Q1', licenseCategory: '', status: 'AVAILABLE' } as const
    expect(toWorkerRequest(valores({ role: 'assistant', hasLicense: false, driver: conTexto }), ROLES)).not.toHaveProperty('driver')
    expect(toWorkerRequest(valores({ role: 'assistant', hasLicense: true, driver: conTexto }), ROLES).driver).toBeDefined()
  })
})

describe('la edición', () => {
  const ORIGINAL = { documentNumber: '45678912', hireDate: '2099-01-01' }
  const edicion = buildWorkerFormSchema({ documentTypes: [DNI, CE], roles: ROLES, today: () => HOY, original: ORIGINAL })

  function erroresEdicion(cambios: Partial<WorkerFormValues> = {}) {
    const resultado = edicion.safeParse(valores({ hireDate: ORIGINAL.hireDate, ...cambios }))
    if (resultado.success) return {}
    const porCampo: Record<string, string> = {}
    for (const issue of resultado.error.issues) porCampo[issue.path.join('.')] ??= issue.message
    return porCampo
  }

  it('el motivo se pide solo si el número, recortado, difiere del guardado', () => {
    expect(documentNumberChanged(' 45678912 ', ORIGINAL)).toBe(false)
    expect(documentNumberChanged('45678913', ORIGINAL)).toBe(true)
    expect(documentNumberChanged('45678913', undefined)).toBe(false)
  })

  it('con el número cambiado, el motivo va de 10 a 500 caracteres, recortado como viaja', () => {
    const motivo = 'Indica el motivo del cambio, de al menos 10 caracteres.'
    expect(erroresEdicion({ documentNumber: '45678913', reason: '  123456789  ' }).reason).toBe(motivo)
    expect(erroresEdicion({ documentNumber: '45678913', reason: '1234567890' })).toEqual({})
    expect(erroresEdicion({ documentNumber: '45678913', reason: 'a'.repeat(500) })).toEqual({})
    expect(erroresEdicion({ documentNumber: '45678913', reason: 'a'.repeat(501) }).reason).toBe('Máximo 500 caracteres.')
    expect(erroresEdicion({ documentNumber: '45678913', reason: ' ' + 'a'.repeat(500) + ' ' })).toEqual({})
    expect(erroresEdicion({ reason: '' })).toEqual({})
  })

  it('respeta la fecha de ingreso guardada aunque sea futura, pero no otra futura', () => {
    expect(erroresEdicion()).toEqual({})
    expect(erroresEdicion({ hireDate: '2099-01-02' }).hireDate).toBe('La fecha de ingreso no puede ser futura.')
  })

  it('abre con lo guardado; la casilla, marcada solo con la licencia activa', () => {
    const activo = fakeWorkerDetail({ phone: null, driver: fakeDriverProfile({ licenseCategory: null, status: 'MAINTENANCE' }) })
    expect(workerEditDefaults(activo)).toEqual({
      firstName: activo.firstName,
      lastName: activo.lastName,
      documentTypeId: activo.documentType.id,
      documentNumber: activo.documentNumber,
      phone: '',
      role: activo.role.name,
      hireDate: activo.hireDate,
      hasLicense: true,
      driver: { licenseNumber: 'Q12345678', licenseCategory: '', status: 'AVAILABLE' },
      reason: '',
    })
    expect(workerEditDefaults(fakeWorkerDetail({ driver: fakeDriverProfile({ isActive: false }) })).hasLicense).toBe(false)
    expect(workerEditDefaults(fakeWorkerDetail({ driver: null })).driver).toEqual({
      licenseNumber: '',
      licenseCategory: '',
      status: 'AVAILABLE',
    })
  })

  /** Así una edición no pisa la disponibilidad que puso un viaje mientras estaba abierta. */
  it('la disponibilidad no viaja en la edición, ni con la licencia ni sin ella', () => {
    const conLicencia = valores({ role: 'driver', driver: { licenseNumber: 'Q1', licenseCategory: '', status: 'MAINTENANCE' } })
    const driver = toWorkerUpdateRequest(conLicencia, ROLES, ORIGINAL).driver
    expect(driver).toEqual({ licenseNumber: 'Q1', licenseCategory: null })
    expect(driver).not.toHaveProperty('status')
    expect(toWorkerUpdateRequest(valores(), ROLES, ORIGINAL)).not.toHaveProperty('driver')
  })

  it('el número guardado con espacios se compara tal cual, como en el backend', () => {
    expect(documentNumberChanged(' 45678912 ', { documentNumber: ' 45678912 ' })).toBe(true)
    expect(documentNumberChanged('45678912', { documentNumber: '45678912' })).toBe(false)
  })

  it('el cuerpo lleva el motivo recortado solo si cambió el número', () => {
    const cambiado = toWorkerUpdateRequest(valores({ documentNumber: '45678913', reason: '  un motivo  ' }), ROLES, ORIGINAL)
    expect(cambiado.reason).toBe('un motivo')
    expect(toWorkerUpdateRequest(valores({ documentNumber: '45678912', reason: 'un motivo' }), ROLES, ORIGINAL).reason).toBeNull()
  })
})
