import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { axe } from 'vitest-axe'
import { ServiceTimeline } from './ServiceTimeline'
import type { ServiceEventType } from '../../../../api'
import { fakeServiceEvent } from '../../../../test/mocks/handlers/operations'
import { SERVICE_EVENT_PRESENTATION } from '../../status/serviceEventPresentation'

/** Sube del texto de la nota a su `<li>`, para acotar las aserciones a una entrada. */
function entryOf(note: string): HTMLElement {
  return screen.getByText(note, { exact: false }).closest('li') as HTMLElement
}

describe('ServiceTimeline', () => {
  it('muestra cada entrada con su nota, su autor y su fecha', () => {
    render(
      <ServiceTimeline
        events={[
          fakeServiceEvent({ id: 1, note: 'Servicio registrado' }),
          fakeServiceEvent({
            id: 2,
            eventType: 'STATUS_CHANGE',
            note: 'Cambio de estado a en ruta',
            createdBy: { id: 4, username: 'jvega', fullName: 'Julia Vega' },
          }),
        ]}
      />,
    )

    const registro = entryOf('Servicio registrado')
    // Con fecha Y hora, en horario de Lima: el fixture es 24/08 02:00 UTC, que en
    // Perú todavía es el 23 a las 21:00. La hora no es adorno: una bitácora con
    // varias entradas del mismo día sería ilegible sin ella, y el texto exacto es
    // lo que detecta tanto que se saque la zona como que se descarte la hora.
    expect(within(registro).getByText(/Carlos Scaramutti/).textContent).toBe(
      'Carlos Scaramutti · 23/08/2026, 21:00',
    )

    expect(within(entryOf('Cambio de estado a en ruta')).getByText(/Julia Vega/)).toBeInTheDocument()
  })

  it('etiqueta cada entrada según su tipo, sin leer el texto de la nota', () => {
    // La nota dice "Estado" en las dos, y los badges igual difieren: la etiqueta
    // sale de `eventType`, que es para lo que el contrato lo publica.
    render(
      <ServiceTimeline
        events={[
          fakeServiceEvent({ id: 1, eventType: 'ASSIGNMENT', note: 'Estado uno' }),
          fakeServiceEvent({ id: 2, eventType: 'FIELD_EDIT', note: 'Estado dos' }),
        ]}
      />,
    )

    expect(within(entryOf('Estado uno')).getByText('Recursos')).toBeInTheDocument()
    expect(within(entryOf('Estado dos')).getByText('Edición')).toBeInTheDocument()
  })

  it('sabe etiquetar los cinco tipos del contrato, con la etiqueta que les toca', () => {
    // La tabla es LITERAL a propósito. Recorrer el mapa de presentación y afirmar
    // contra él mismo pasa por construcción: lo único que detectaría es que el
    // componente deje de leer el mapa, no que el mapa diga otra cosa, y los cinco
    // textos se podrían cambiar por cualquier cosa sin que nada fallara.
    //
    // La exhaustividad no se pierde: el mapa está tipado `Record<ServiceEventType,
    // …>`, así que un tipo nuevo del contrato rompe la compilación igual, y el
    // primer `expect` de acá exige que esta tabla se actualice con él.
    const expected: [ServiceEventType, string][] = [
      ['CREATED', 'Registro'],
      ['ASSIGNMENT', 'Recursos'],
      ['STATUS_CHANGE', 'Estado'],
      ['FIELD_EDIT', 'Edición'],
      ['NOTE', 'Nota'],
    ]
    expect(expected).toHaveLength(Object.keys(SERVICE_EVENT_PRESENTATION).length)

    render(
      <ServiceTimeline
        events={expected.map(([eventType], index) =>
          fakeServiceEvent({ id: index + 1, eventType, note: `Nota ${eventType}` }),
        )}
      />,
    )

    for (const [eventType, label] of expected) {
      expect(within(entryOf(`Nota ${eventType}`)).getByText(label)).toBeInTheDocument()
    }
  })

  it('respeta los saltos INTERNOS de una nota heredada del sistema anterior', () => {
    // 9 de las 825 entradas migradas concatenan varios movimientos con saltos
    // reales adentro (en la base de dev las hay de 234 caracteres). Sin respetar
    // esos saltos se leen como un bloque corrido.
    const note = '[9/2/2026] ASIGNACIÓN: 330\n[11/2/2026] CAMBIO A COMPLETED'
    render(<ServiceTimeline events={[fakeServiceEvent({ eventType: 'NOTE', note })]} />)

    const paragraph = screen.getByText(/ASIGNACIÓN: 330/)
    expect(paragraph).toHaveClass('whitespace-pre-line')
    expect(paragraph.textContent).toBe(note)
  })

  it('recorta los saltos de los BORDES, que solo dibujan una línea en blanco', () => {
    // Es el caso de las otras 37 entradas migradas con saltos: los suyos están al
    // principio o al final. Los tres fixtures traen ADEMÁS un salto interno, así
    // que el texto esperado cambia si se saca el recorte Y cambia si se dejan de
    // respetar los internos: un fixture de una sola línea no distinguiría ninguna
    // de las dos cosas.
    render(
      <ServiceTimeline
        events={[
          fakeServiceEvent({ id: 1, note: '\n[9/2] ASIGNACIÓN\n[11/2] COMPLETED' }),
          fakeServiceEvent({ id: 2, note: '[9/2] SALIDA\n[11/2] LLEGADA\n' }),
          fakeServiceEvent({ id: 3, note: '\n\n[9/2] CARGA\n[11/2] DESCARGA\n\n' }),
        ]}
      />,
    )

    expect(screen.getByText(/ASIGNACIÓN/).textContent).toBe('[9/2] ASIGNACIÓN\n[11/2] COMPLETED')
    expect(screen.getByText(/SALIDA/).textContent).toBe('[9/2] SALIDA\n[11/2] LLEGADA')
    expect(screen.getByText(/CARGA/).textContent).toBe('[9/2] CARGA\n[11/2] DESCARGA')
  })

  it('avisa cuando el viaje no tiene movimientos, en vez de mostrar una lista vacía', () => {
    // No es un caso teórico: 80 de los 905 viajes migrados no tienen ninguna
    // entrada.
    render(<ServiceTimeline events={[]} />)

    expect(screen.getByText('Este viaje todavía no tiene movimientos registrados.')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('mantiene el orden en que las manda el servidor, sin reordenar por fecha', () => {
    // Las marcas de tiempo van DESCENDENTES a propósito, al revés del orden en
    // que llegan. Con las tres compartiendo el mismo instante, un `sort` por
    // `createdAt` metido en el componente sería un no-op y este caso lo dejaría
    // pasar: la regresión realista no es invertir la lista, es que alguien
    // "ordene por las dudas" lo que el servidor ya entregó ordenado.
    render(
      <ServiceTimeline
        events={[
          fakeServiceEvent({ id: 1, note: 'Primera', createdAt: '2026-08-24T03:00:00Z' }),
          fakeServiceEvent({ id: 2, note: 'Segunda', createdAt: '2026-08-24T02:00:00Z' }),
          fakeServiceEvent({ id: 3, note: 'Tercera', createdAt: '2026-08-24T01:00:00Z' }),
        ]}
      />,
    )

    const notes = screen.getAllByRole('listitem').map((item) => item.textContent)
    expect(notes[0]).toContain('Primera')
    expect(notes[1]).toContain('Segunda')
    expect(notes[2]).toContain('Tercera')
  })

  it('no tiene violaciones de accesibilidad', async () => {
    const { container } = render(
      <ServiceTimeline events={[fakeServiceEvent(), fakeServiceEvent({ id: 2 })]} />,
    )
    expect(await axe(container)).toHaveNoViolations()
  })
})
