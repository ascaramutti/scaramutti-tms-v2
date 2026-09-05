import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  FIELD_BASE,
  FIELD_BORDER,
  FIELD_BORDER_INVALID,
  FIELD_CHECKBOX,
  FIELD_DENSITY,
  FIELD_DISABLED,
  FIELD_FOCUS,
  FIELD_FOCUS_INVALID,
  FIELD_PLACEHOLDER,
  fieldClasses,
  fieldReadonlyClasses,
} from './fieldClasses'

/**
 * Lo que estas pruebas cuidan es la MUDANZA: el molde reemplaza nueve cadenas escritas a
 * mano, y lo que puede romperse no es el aspecto de un control sino que el molde le agregue
 * a alguno una clase que no tenía. Por eso los conjuntos se fijan contra literales escritos
 * acá, y no derivados del módulo.
 */
const clases = (s: string) => new Set(s.split(/\s+/).filter(Boolean))

const ESPERADO_COMODO =
  'rounded-lg border bg-surface text-sm text-fg px-3.5 py-2.5 ' +
  'focus:outline-none focus:ring-2 focus:ring-focus focus:border-focus border-border-strong'

const ESPERADO_COMPACTO =
  'rounded-lg border bg-surface text-sm text-fg px-3 py-2 ' +
  'focus:outline-none focus:ring-2 focus:ring-focus focus:border-focus border-border-strong'

describe('fieldClasses · la forma común de los controles', () => {
  it('el espaciado cómodo es el de los formularios', () => {
    expect(clases(fieldClasses())).toEqual(clases(ESPERADO_COMODO))
  })

  it('el compacto es el de las barras y las tablas de ítems', () => {
    expect(clases(fieldClasses({ density: 'compact' }))).toEqual(clases(ESPERADO_COMPACTO))
  })

  it('en error cambia el borde y nada más', () => {
    const normal = clases(fieldClasses())
    const malo = clases(fieldClasses({ invalid: true }))
    expect(malo.has(FIELD_BORDER_INVALID)).toBe(true)
    expect(malo.has(FIELD_BORDER)).toBe(false)
    // El resto es idéntico: el error no toca el foco ni el fondo. El foco en error es una
    // pieza aparte, porque solo la tiene el campo de texto.
    normal.delete(FIELD_BORDER)
    malo.delete(FIELD_BORDER_INVALID)
    expect(malo).toEqual(normal)
  })

  it('NO trae el ancho: lo declara cada sitio', () => {
    // La barra de reportes no lleva ancho porque va en una fila fija; meterlo en el molde le
    // cambiaría el ancho a esa barra.
    expect(clases(fieldClasses()).has('w-full')).toBe(false)
  })

  it('NO trae el placeholder ni el deshabilitado: los suma quien los tenga', () => {
    const c = clases(fieldClasses())
    expect([...c].some((x) => x.startsWith('placeholder:'))).toBe(false)
    expect([...c].some((x) => x.startsWith('disabled:'))).toBe(false)
  })

  it('los dos espaciados son los dos que el árbol ya tenía', () => {
    expect(FIELD_DENSITY).toEqual({ comfortable: 'px-3.5 py-2.5', compact: 'px-3 py-2' })
  })
})

describe('fieldClasses · ninguna pieza escribe un color crudo', () => {
  it.each([
    ['base', FIELD_BASE],
    ['foco', FIELD_FOCUS],
    ['borde', FIELD_BORDER],
    ['borde en error', FIELD_BORDER_INVALID],
    ['foco en error', FIELD_FOCUS_INVALID],
    ['placeholder', FIELD_PLACEHOLDER],
    ['deshabilitado', FIELD_DISABLED],
    ['casilla', FIELD_CHECKBOX],
  ])('la pieza de %s sale de tokens', (_nombre, pieza) => {
    const crudas = pieza
      .split(/\s+/)
      .filter((c) => /-(slate|blue|red|amber|emerald|teal|white|gray)(-\d{2,3})?$/.test(c))
    expect(crudas).toEqual([])
  })

  it('el control de solo lectura tampoco', () => {
    const crudas = fieldReadonlyClasses()
      .split(/\s+/)
      .filter((c) => /-(slate|blue|red|amber|emerald|teal|white|gray)(-\d{2,3})?$/.test(c))
    expect(crudas).toEqual([])
  })
})

describe('fieldReadonlyClasses · el control apagado', () => {
  it('no tiene foco ni borde fuerte: se lee como un campo, no se edita', () => {
    const c = clases(fieldReadonlyClasses())
    expect(c.has('cursor-default')).toBe(true)
    expect(c.has('bg-surface-subtle')).toBe(true)
    expect(c.has('border-border')).toBe(true)
    expect(c.has('focus:ring-2')).toBe(false)
  })

  it('acepta el mismo espaciado compacto que el editable', () => {
    expect(clases(fieldReadonlyClasses({ density: 'compact' })).has('px-3')).toBe(true)
  })
})

/**
 * La asimetría del estado de error, que es de antes de esta mudanza y por eso hay que
 * fijarla: los cuatro componentes cambian el BORDE cuando el campo tiene error, pero solo
 * el campo de texto y el área de texto cambian además el anillo del foco. Sin una prueba
 * que lo diga, "unificar" el molde de buena fe le agrega al select y a la fecha un anillo
 * rojo que hoy no tienen. Se mide sobre la fuente, como el guard de la nota interna,
 * porque lo que se cuida es qué componente aplica la pieza, no qué renderiza.
 */
describe('el foco en error, que no lo tienen los cuatro', () => {
  const fuente = (nombre: string) =>
    readFileSync(join(process.cwd(), 'src', 'shared', 'ui', `${nombre}.tsx`), 'utf8')

  it.each<[string, boolean]>([
    ['TextField', true],
    ['Textarea', true],
    ['SelectField', false],
    ['DateField', false],
  ])('%s cambia el anillo del foco en error: %s', (nombre, cambia) => {
    expect(fuente(nombre).includes('FIELD_FOCUS_INVALID')).toBe(cambia)
  })

  it('los cuatro sí cambian el borde', () => {
    for (const nombre of ['TextField', 'Textarea', 'SelectField', 'DateField']) {
      expect(fuente(nombre)).toMatch(/fieldClasses\(\{ invalid: !!error \}\)/)
    }
  })
})
