import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { axe } from 'vitest-axe'
import { ServicesKpiStrip } from './ServicesKpiStrip'
import { fakeServiceStats } from '../../../test/mocks/handlers/operations'

function renderStrip(props: Partial<Parameters<typeof ServicesKpiStrip>[0]> = {}) {
  return render(
    <ServicesKpiStrip
      data={fakeServiceStats()}
      isLoading={false}
      isError={false}
      onRetry={() => {}}
      {...props}
    />,
  )
}

/** Sube del rótulo del tile a su contenedor, para acotar asserts a un indicador. */
function tileOf(label: string): HTMLElement {
  return screen.getByText(label).parentElement as HTMLElement
}

describe('ServicesKpiStrip', () => {
  it('renderiza los seis indicadores con sus rótulos', () => {
    renderStrip()
    expect(within(tileOf('Pend. asignación')).getByText('4')).toBeInTheDocument()
    expect(within(tileOf('Pend. inicio')).getByText('2')).toBeInTheDocument()
    expect(within(tileOf('En ruta')).getByText('7')).toBeInTheDocument()
    expect(within(tileOf('Completados (semana)')).getByText('11')).toBeInTheDocument()
    expect(within(tileOf('Conductores en ruta (principales)')).getByText('3')).toBeInTheDocument()
    expect(within(tileOf('Tractos en ruta (principales)')).getByText('1')).toBeInTheDocument()
  })

  it('muestra las fracciones de recursos como activos sobre padrón', () => {
    renderStrip()
    // El numerador y el denominador no se pueden confundir entre sí: el tile
    // dice "3 de 5", no "3" y "5" sueltos ni "35".
    const conductores = tileOf('Conductores en ruta (principales)')
    expect(within(conductores).getByText('3')).toBeInTheDocument()
    expect(within(conductores).getByText(/de 5/)).toBeInTheDocument()
  })

  it('muestra un recurso en cero sobre un padrón poblado', () => {
    renderStrip({ data: fakeServiceStats({ driversOnRoad: { active: 0, total: 12 } }) })
    const conductores = tileOf('Conductores en ruta (principales)')
    expect(within(conductores).getByText('0')).toBeInTheDocument()
    expect(within(conductores).getByText(/de 12/)).toBeInTheDocument()
  })

  it('muestra un padrón vacío como cero sobre cero y no como guion', () => {
    // `0 de 0` es un dato (no hay conductores de alta); el guion significa "no
    // se pudieron cargar los indicadores", que es otra cosa.
    renderStrip({ data: fakeServiceStats({ driversOnRoad: { active: 0, total: 0 } }) })
    const conductores = tileOf('Conductores en ruta (principales)')
    expect(within(conductores).getByText('0')).toBeInTheDocument()
    expect(within(conductores).getByText(/de 0/)).toBeInTheDocument()
  })

  it('un contador en cero se muestra como dato y no como vacío', () => {
    renderStrip({ data: fakeServiceStats({ inProgress: 0 }) })
    expect(within(tileOf('En ruta')).getByText('0')).toBeInTheDocument()
  })

  it('imprime la semana operativa con el martes de cierre', () => {
    // `weekCycle.end` es el MARTES inclusive, no el miércoles exclusivo con el
    // que se consulta: se imprime tal cual viene. Literales a propósito, para
    // que sumarle un día al cierre rompa el caso.
    renderStrip({
      data: fakeServiceStats({ weekCycle: { start: '2026-08-19', end: '2026-08-25' } }),
    })
    const etiqueta = screen.getByText(/semana operativa/i)
    expect(etiqueta).toHaveTextContent('19/08/2026')
    expect(etiqueta).toHaveTextContent('25/08/2026')
  })

  it('nombra los dos extremos de la semana', () => {
    renderStrip()
    expect(screen.getByText(/miércoles a martes/i)).toBeInTheDocument()
  })

  it('distingue el indicador del ciclo semanal de los globales', () => {
    // Los otros tres contadores son globales: rotularlos con una ventana de
    // tiempo mentiría sobre lo que cuentan.
    renderStrip()
    // Los `getByText` con literal exacto ya son la aserción: si un rótulo global
    // ganara una ventana de tiempo, revienta acá. Afirmar además sobre su propio
    // textContent no agregaría nada que pueda fallar.
    expect(screen.getByText('Completados (semana)')).toBeInTheDocument()
    expect(screen.getByText('Pend. asignación')).toBeInTheDocument()
    expect(screen.getByText('Pend. inicio')).toBeInTheDocument()
    expect(screen.getByText('En ruta')).toBeInTheDocument()
  })

  it('muestra el estado de carga mientras llegan los indicadores', () => {
    renderStrip({ data: undefined, isLoading: true })
    expect(screen.getAllByRole('status')).toHaveLength(6)
  })

  it('muestra un aviso degradado ante un error y permite reintentar', async () => {
    const onRetry = vi.fn()
    renderStrip({
      data: undefined,
      isError: true,
      errorMessage: 'Fallo al calcular indicadores',
      onRetry,
    })
    expect(screen.getByRole('alert')).toHaveTextContent('Fallo al calcular indicadores')
    // Sin datos, los seis tiles caen al guion.
    expect(screen.getAllByText('—')).toHaveLength(6)
    await userEvent.click(screen.getByRole('button', { name: /reintentar cargar los indicadores/i }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('no oculta la semana operativa detrás del aviso de error', () => {
    // Sin datos no hay semana que imprimir: la etiqueta desaparece en vez de
    // mostrar un rango inventado.
    renderStrip({ data: undefined, isError: true, errorMessage: 'Fallo' })
    expect(screen.queryByText(/semana operativa/i)).not.toBeInTheDocument()
  })

  it('los tiles no son interactivos', () => {
    // Decisión explícita: los contadores no filtran la tabla. Tres son globales
    // y "Completados" cuenta por fecha de fin real dentro del ciclo semanal,
    // mientras que el listado filtra por fecha tentativa: un tile que aplicara
    // su propio filtro mostraría un número arriba y otro abajo.
    renderStrip()
    expect(screen.queryAllByRole('button')).toHaveLength(0)
  })

  it('avisa que los indicadores no dependen de los filtros del listado', () => {
    renderStrip()
    // A la vista y en el rótulo accesible: los contadores no se mueven al
    // filtrar la tabla, y sin decirlo eso se lee como un error.
    expect(
      screen.getByText('Estos indicadores no dependen de los filtros del listado.'),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('group', { name: /no depende de los filtros/i }),
    ).toBeInTheDocument()
  })

  it('mantiene el aviso aunque los indicadores no hayan cargado', () => {
    // La advertencia no depende del dato: si desapareciera al fallar, el usuario
    // vería seis guiones sin saber que tampoco reaccionan a sus filtros.
    renderStrip({ data: undefined, isError: true, errorMessage: 'Fallo' })
    expect(
      screen.getByText('Estos indicadores no dependen de los filtros del listado.'),
    ).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad', async () => {
    const { container } = renderStrip()
    expect(await axe(container)).toHaveNoViolations()
  })
})
