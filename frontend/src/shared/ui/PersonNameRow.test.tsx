import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { PersonNameRow } from './PersonNameRow'

/** La raya larga de las fichas, escrita acá y no importada: si saliera de la pieza, una raya cambiada pasaría. */
const RAYA = '\u2014'

type Partes = Parameters<typeof PersonNameRow>[0]

/** Lo que muestra cada rótulo con las partes de `renderFila`. */
const PARTES = { Nombres: 'Ana María', 'Apellido paterno': 'Torres', 'Apellido materno': 'Ruiz' }

function renderFila(partes: Partial<Partes> = {}) {
  return render(<PersonNameRow firstName="Ana María" paternalLastName="Torres" maternalLastName="Ruiz" {...partes} />)
}

const fila = () => screen.getByLabelText('Nombre')

/** El valor bajo su rótulo, nunca suelto. */
function valor(rotulo: string) {
  return within(within(fila()).getByText(rotulo, { selector: 'dt' }).parentElement as HTMLElement).getByRole('definition')
}

describe('PersonNameRow', () => {
  it('cada parte va bajo su rótulo, en este orden', () => {
    renderFila()

    expect(within(fila()).getAllByRole('term').map((dt) => dt.textContent)).toEqual([
      'Nombres',
      'Apellido paterno',
      'Apellido materno',
    ])
    expect(valor('Nombres')).toHaveTextContent(/^Ana María$/)
    expect(valor('Apellido paterno')).toHaveTextContent(/^Torres$/)
    expect(valor('Apellido materno')).toHaveTextContent(/^Ruiz$/)
  })

  it.each([
    ['Apellido materno', 'nulo', { maternalLastName: null }],
    ['Apellido materno', 'vacío', { maternalLastName: '' }],
    ['Apellido materno', 'de espacios', { maternalLastName: '   ' }],
    ['Apellido paterno', 'vacío', { paternalLastName: '' }],
    ['Apellido paterno', 'de espacios', { paternalLastName: '   ' }],
  ] as const)('%s %s: la raya larga, y las otras partes igual', (rotulo, _caso, partes) => {
    renderFila(partes)

    expect(valor(rotulo)).toHaveTextContent(new RegExp(`^${RAYA}$`))
    for (const [otro, esperado] of Object.entries(PARTES).filter(([otro]) => otro !== rotulo)) {
      expect(valor(otro)).toHaveTextContent(new RegExp(`^${esperado}$`))
    }
  })

  it('una columna, y las tres en una fila desde el ancho chico', () => {
    renderFila()

    const clases = fila().className.split(/\s+/)
    expect(clases).toEqual(expect.arrayContaining(['grid', 'grid-cols-1', 'sm:grid-cols-3', 'gap-4']))
    expect(fila().tagName).toBe('DL')
    expect([...fila().children].map((parte) => parte.textContent)).toEqual([
      'NombresAna María',
      'Apellido paternoTorres',
      'Apellido maternoRuiz',
    ])
  })

  it('el margen que le pone la ficha se suma a la grilla', () => {
    renderFila({ className: 'mt-4' })

    expect(fila().className.split(/\s+/)).toEqual(expect.arrayContaining(['mt-4', 'grid', 'sm:grid-cols-3']))
  })

  it.each([
    ['con materno', {}],
    ['sin materno', { maternalLastName: null }],
  ] as const)('pasa axe %s', async (_caso, partes) => {
    const { container } = renderFila(partes)

    expect(await axe(container)).toHaveNoViolations()
  })
})
