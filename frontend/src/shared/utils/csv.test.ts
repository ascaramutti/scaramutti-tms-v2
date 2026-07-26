import { describe, expect, it } from 'vitest'
import { buildCsv, csvBlob } from './csv'

/** El BOM que `buildCsv` antepone, para poder aseverar sobre el resto del texto. */
const BOM = '﻿'

describe('buildCsv', () => {
  it('separa las celdas con comas y las filas con CRLF', () => {
    const csv = buildCsv([
      ['Etiqueta', 'Monto'],
      ['Tracto ABC123', 150],
    ])
    expect(csv).toBe(`${BOM}Etiqueta,Monto\r\nTracto ABC123,150\r\n`)
  })

  it('antepone el BOM UTF-8 para que Excel lea las tildes', () => {
    const csv = buildCsv([['Camión Volvo']])
    expect(csv.startsWith(BOM)).toBe(true)
    expect(csv).toContain('Camión Volvo')
  })

  it('entrecomilla los campos que contienen el separador', () => {
    const csv = buildCsv([['Repuestos, S.A.']])
    expect(csv).toBe(`${BOM}"Repuestos, S.A."\r\n`)
  })

  it('duplica las comillas internas y entrecomilla el campo', () => {
    const csv = buildCsv([['Repuestos "El Rápido"']])
    expect(csv).toBe(`${BOM}"Repuestos ""El Rápido"""\r\n`)
  })

  it('entrecomilla los campos con saltos de línea', () => {
    const csv = buildCsv([['Primera\nSegunda']])
    expect(csv).toBe(`${BOM}"Primera\nSegunda"\r\n`)
  })

  it('serializa los números crudos, sin separador de miles ni símbolo', () => {
    const csv = buildCsv([[1234.5]])
    expect(csv).toBe(`${BOM}1234.5\r\n`)
  })

  it('serializa null y undefined como campo vacío', () => {
    const csv = buildCsv([['a', null, undefined, 'b']])
    expect(csv).toBe(`${BOM}a,,,b\r\n`)
  })

  it('neutraliza los textos que Excel interpretaría como fórmula', () => {
    // Un nombre de producto que arranca con "=" o "-" no debe ejecutarse al abrir.
    const csv = buildCsv([['=SUMA(A1:A9)'], ['-Filtro'], ['+Aceite'], ['@arroba']])
    expect(csv).toBe(`${BOM}'=SUMA(A1:A9)\r\n'-Filtro\r\n'+Aceite\r\n'@arroba\r\n`)
  })

  it('neutraliza también las fórmulas precedidas por espacios o tabs', () => {
    // Excel ignora el whitespace inicial antes de evaluar la fórmula.
    const csv = buildCsv([[' =SUMA(A1:A9)'], ['\t=SUMA(A1:A9)']])
    expect(csv).toContain("' =SUMA(A1:A9)")
    expect(csv).toContain(`'\t=SUMA(A1:A9)`)
  })

  it('acepta un separador alternativo y entrecomilla según ese separador', () => {
    const csv = buildCsv([['a;b', 'c,d']], { separator: ';' })
    // Con ';' de separador, la coma deja de ser especial y el ';' pasa a serlo.
    expect(csv).toBe(`${BOM}"a;b";c,d\r\n`)
  })

  it('serializa una matriz vacía como solo el BOM y el fin de línea', () => {
    expect(buildCsv([])).toBe(`${BOM}\r\n`)
  })
})

describe('csvBlob', () => {
  it('arma el blob con el tipo y charset de CSV', () => {
    expect(csvBlob('a,b').type).toBe('text/csv;charset=utf-8')
  })

  it('conserva el contenido tal cual', async () => {
    await expect(csvBlob(`${BOM}a,b\r\n`).text()).resolves.toBe(`${BOM}a,b\r\n`)
  })
})
