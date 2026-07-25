import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { WarehouseReportsPage } from './WarehouseReportsPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { ProductsCaptureSink } from '../../../test/mocks/handlers/warehouse'
import {
  fakeReport,
  fakeReportByPeriod,
  fakeReportByProduct,
  fakeReportBySupplier,
  fakeReportRow,
  warehouseReport,
  warehouseReportByCut,
  warehouseReportCapture,
  warehouseReportEmpty,
  warehouseReportError,
  warehouseReportOkThenErrorOnOtherCut,
  warehouseReportSlow,
} from '../../../test/mocks/handlers/warehouse'

function renderReportes() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, fakeUser)
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cotizaciones/almacen/reportes']}>
          <WarehouseReportsPage />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/**
 * Intercepta la descarga sin tocar el sistema de archivos: guarda los blobs que
 * se crearon, las urls liberadas y el `download` del ancla clickeada.
 */
function stubDownload() {
  const blobs: Blob[] = []
  const revoked: string[] = []
  const filenames: string[] = []
  // Se parchean los dos métodos, NO el objeto `URL` entero: MSW usa `new URL(…)`
  // para leer los query params de cada request y reemplazar el constructor
  // dejaría a los handlers sin poder resolver nada.
  vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
    blobs.push(blob as Blob)
    return `blob:fake-${blobs.length}`
  })
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url) => {
    revoked.push(url)
  })
  const clickSpy = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(function (this: HTMLAnchorElement) {
      filenames.push(this.download)
    })
  return { blobs, revoked, filenames, clickSpy }
}

const REPORT_TAB = 'Corte del reporte'

describe('WarehouseReportsPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  // ----- Render -----
  it('muestra el spinner mientras genera el reporte', async () => {
    server.use(warehouseReportSlow(fakeReport(), 40))
    renderReportes()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    expect(await screen.findByText('Tracto ABC-123')).toBeInTheDocument()
  })

  it('renderiza una fila por cada fila del reporte', async () => {
    renderReportes()
    expect(await screen.findByText('Tracto ABC-123')).toBeInTheDocument()
    expect(screen.getByText('Sin unidad asignada')).toBeInTheDocument()
  })

  it('muestra los cuatro cortes como tabs', async () => {
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    const tabs = within(screen.getByRole('tablist', { name: REPORT_TAB })).getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      'Por unidad de flota',
      'Por semana',
      'Por producto',
      'Compras por proveedor',
    ])
  })

  it('muestra el rango que devolvió el backend, no el estado local', async () => {
    // Con `keepPreviousData` la tabla previa sobrevive a un cambio de rango en
    // vuelo: el encabezado tiene que describir los datos en pantalla.
    server.use(warehouseReport({ dateFrom: '2026-01-05', dateTo: '2026-01-20' }))
    renderReportes()
    expect(await screen.findByText('Del 05/01/2026 al 20/01/2026')).toBeInTheDocument()
  })

  it('muestra el estado vacío cuando no hubo movimientos en el rango', async () => {
    server.use(warehouseReportEmpty())
    renderReportes()
    expect(await screen.findByText('Sin movimientos en el rango')).toBeInTheDocument()
    expect(screen.queryByText('Tracto ABC-123')).not.toBeInTheDocument()
  })

  // ----- Los 4 cortes -----
  it('rotula el conteo según el corte', async () => {
    server.use(warehouseReportByCut({ BY_PRODUCT: fakeReportByProduct() }))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('tab', { name: 'Por producto' }))
    // En BY_PRODUCT el backend cuenta unidades retiradas, no movimientos.
    expect(await screen.findByText(/24 unidades retiradas/)).toBeInTheDocument()
  })

  it('muestra el lunes de la semana formateado en el corte por semana', async () => {
    server.use(warehouseReportByCut({ BY_PERIOD: fakeReportByPeriod() }))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('tab', { name: 'Por semana' }))
    expect(await screen.findByText('01/06/2026')).toBeInTheDocument()
    expect(screen.getByText('08/06/2026')).toBeInTheDocument()
  })

  it('respeta el orden en que llegan las filas, sin reordenar localmente', async () => {
    // Filas deliberadamente NO ordenadas por monto: el orden es del backend.
    server.use(
      warehouseReport({
        rows: [
          fakeReportRow({ label: 'Menor', amountPEN: 10 }),
          fakeReportRow({ label: 'Mayor', amountPEN: 900 }),
        ],
      }),
    )
    renderReportes()
    await screen.findByText('Menor')
    const labels = screen.getAllByRole('listitem').map((item) => item.textContent)
    expect(labels[0]).toContain('Menor')
    expect(labels[1]).toContain('Mayor')
  })

  it('rotula los montos como compras en el corte por proveedor', async () => {
    server.use(warehouseReportByCut({ BY_SUPPLIER: fakeReportBySupplier() }))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('tab', { name: 'Compras por proveedor' }))
    expect(await screen.findByText('Comprado en soles')).toBeInTheDocument()
    expect(screen.queryByText(/Retirado en soles/)).not.toBeInTheDocument()
  })

  it('reconsulta con el corte nuevo al cambiar de tab', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('tab', { name: 'Por producto' }))
    await waitFor(() => expect(sink.params?.get('cut')).toBe('BY_PRODUCT'))
  })

  it('conserva el rango al cambiar de corte', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    const firstFrom = sink.calls?.[0].get('dateFrom')
    await user.click(screen.getByRole('tab', { name: 'Por semana' }))
    await waitFor(() => expect(sink.calls?.length).toBe(2))
    expect(sink.calls?.[1].get('dateFrom')).toBe(firstFrom)
  })

  it('navega entre cortes con las flechas del teclado y el foco sigue a la selección', async () => {
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    screen.getByRole('tab', { name: 'Por unidad de flota' }).focus()

    await user.keyboard('{ArrowRight}')
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Por semana' })).toHaveAttribute(
        'aria-selected',
        'true',
      ),
    )
    // Si el foco no se moviera, la segunda flecha calcularía desde el índice
    // viejo y los dos últimos cortes serían inalcanzables por teclado.
    expect(screen.getByRole('tab', { name: 'Por semana' })).toHaveFocus()

    await user.keyboard('{ArrowRight}')
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Por producto' })).toHaveAttribute(
        'aria-selected',
        'true',
      ),
    )
    expect(screen.getByRole('tab', { name: 'Por producto' })).toHaveFocus()
  })

  it('la flecha izquierda desde el primer corte da la vuelta al último', async () => {
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    screen.getByRole('tab', { name: 'Por unidad de flota' }).focus()
    await user.keyboard('{ArrowLeft}')
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Compras por proveedor' })).toHaveFocus(),
    )
  })

  it('Home y End saltan al primer y al último corte', async () => {
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    screen.getByRole('tab', { name: 'Por unidad de flota' }).focus()
    await user.keyboard('{End}')
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Compras por proveedor' })).toHaveFocus(),
    )
    await user.keyboard('{Home}')
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Por unidad de flota' })).toHaveFocus(),
    )
  })

  // ----- Bi-moneda (RN-WH7) -----
  it('muestra los totales separados por moneda', async () => {
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    // Acotado a las tarjetas de totales: los mismos montos aparecen en las filas.
    // Regex por el espacio duro que mete `Intl` entre el símbolo y el número.
    const totals = within(screen.getByRole('group', { name: 'Totales del reporte' }))
    expect(totals.getByText(/^S\/.1,234\.50$/)).toBeInTheDocument()
    // El símbolo del dólar depende del ICU del entorno ("US$" o "USD"): se
    // asevera el monto, que es lo que fija la separación por moneda.
    expect(totals.getByText(/^(US\$|USD).320\.00$/)).toBeInTheDocument()
  })

  it('no muestra ningún total unificado ni tipo de cambio', async () => {
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    expect(screen.queryByText(/tipo de cambio|equivalente|total general/i)).not.toBeInTheDocument()
    // 1234.50 + 320 = 1554.50: si apareciera, alguien convirtió monedas.
    expect(screen.queryByText(/1,554\.50/)).not.toBeInTheDocument()
  })

  it('muestra los totales del backend aunque no coincidan con la suma de las filas', async () => {
    server.use(
      warehouseReport({
        rows: [fakeReportRow({ amountPEN: 10 })],
        totalPEN: 999,
        totalUSD: 0,
        totalCount: 42,
      }),
    )
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    // El front no recalcula agregados: muestra el dato del sistema.
    const totals = within(screen.getByRole('group', { name: 'Totales del reporte' }))
    expect(totals.getByText(/^S\/.999\.00$/)).toBeInTheDocument()
    expect(totals.getByText('42')).toBeInTheDocument()
  })

  it('muestra el monto de una fila aunque sea negativo', async () => {
    // Un ajuste o una nota de crédito da negativo: la fila tiene valor que
    // mostrar y no puede caer al "S/ 0.00" de las filas sin monto.
    server.use(
      warehouseReport({ rows: [fakeReportRow({ label: 'Ajuste', amountPEN: -50, amountUSD: 0 })] }),
    )
    renderReportes()
    await screen.findByText('Ajuste')
    expect(screen.getByText(/-S\/.50\.00|S\/.-50\.00/)).toBeInTheDocument()
  })

  // ----- Rango de fechas -----
  it('consulta siempre con los tres parámetros obligatorios', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    for (const call of sink.calls ?? []) {
      expect(call.get('cut')).toBeTruthy()
      expect(call.get('dateFrom')).toBeTruthy()
      expect(call.get('dateTo')).toBeTruthy()
    }
  })

  it('aplica el rango elegido en la consulta', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.clear(screen.getByLabelText('Desde'))
    await user.type(screen.getByLabelText('Desde'), '2026-03-01')
    await waitFor(() => expect(sink.params?.get('dateFrom')).toBe('2026-03-01'))
  })

  it('avisa del rango invertido y no dispara la consulta', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    const callsBefore = sink.calls?.length ?? 0
    await user.clear(screen.getByLabelText('Hasta'))
    await user.type(screen.getByLabelText('Hasta'), '2020-01-01')
    expect(
      await screen.findByText('La fecha "desde" no puede ser posterior a la fecha "hasta".'),
    ).toBeInTheDocument()
    expect(sink.calls?.length).toBe(callsBefore)
  })

  it('no consulta mientras el rango está incompleto', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    const callsBefore = sink.calls?.length ?? 0
    await user.clear(screen.getByLabelText('Hasta'))
    await waitFor(() => expect(sink.calls?.length).toBe(callsBefore))
  })

  it('avisa cuando falta una fecha, con un mensaje distinto al del rango invertido', async () => {
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.clear(screen.getByLabelText('Hasta'))
    // Sin este aviso, borrar una fecha deja en pantalla el reporte del rango
    // anterior mientras los inputs muestran otro, y nada lo delata.
    expect(
      await screen.findByText('Completa ambas fechas para generar el reporte.'),
    ).toBeInTheDocument()
  })

  it('bloquea la exportación mientras el rango no aplica', async () => {
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    expect(screen.getByRole('button', { name: /Exportar CSV/ })).toBeEnabled()

    await user.clear(screen.getByLabelText('Hasta'))
    // El reporte en pantalla es del rango anterior: exportarlo daría un archivo
    // que no corresponde a lo que muestran los filtros.
    await waitFor(() => expect(screen.getByRole('button', { name: /Exportar CSV/ })).toBeDisabled())

    await user.type(screen.getByLabelText('Hasta'), '2020-01-01')
    expect(screen.getByRole('button', { name: /Exportar CSV/ })).toBeDisabled()
  })

  it('el botón "Mes actual" repone el rango del mes en curso', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.clear(screen.getByLabelText('Desde'))
    await user.type(screen.getByLabelText('Desde'), '2020-01-01')
    await user.click(screen.getByRole('button', { name: /Mes actual/ }))
    await waitFor(() => expect(sink.params?.get('dateFrom')).toMatch(/^\d{4}-\d{2}-01$/))
  })

  // ----- Export CSV -----
  it('descarga el CSV con el nombre del corte y el rango', async () => {
    const download = stubDownload()
    server.use(warehouseReport({ dateFrom: '2026-06-01', dateTo: '2026-06-30' }))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('button', { name: /Exportar CSV/ }))
    expect(download.filenames).toEqual(['almacen-reporte-por-unidad-2026-06-01_2026-06-30.csv'])
    expect(download.blobs[0].type).toBe('text/csv;charset=utf-8')
  })

  it('exporta el contenido del reporte en pantalla', async () => {
    const download = stubDownload()
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('button', { name: /Exportar CSV/ }))
    const text = await download.blobs[0].text()
    expect(text).toContain('Tracto ABC-123')
    expect(text).toContain('TOTAL,,5,1234.5,320')
  })

  it('exporta el corte visible, no el inicial', async () => {
    const download = stubDownload()
    server.use(warehouseReportByCut({ BY_SUPPLIER: fakeReportBySupplier() }))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('tab', { name: 'Compras por proveedor' }))
    await screen.findByText('Repuestos del Sur S.A.C.')
    await user.click(screen.getByRole('button', { name: /Exportar CSV/ }))
    expect(download.filenames[0]).toContain('por-proveedor')
    await expect(download.blobs[0].text()).resolves.toContain('Facturas')
  })

  it('la exportación no golpea el backend', async () => {
    stubDownload()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseReportCapture(sink))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    const callsBefore = sink.calls?.length ?? 0
    await user.click(screen.getByRole('button', { name: /Exportar CSV/ }))
    expect(sink.calls?.length).toBe(callsBefore)
  })

  it('libera el object URL después de descargar', async () => {
    const download = stubDownload()
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('button', { name: /Exportar CSV/ }))
    expect(download.revoked).toEqual(['blob:fake-1'])
  })

  it('deshabilita la exportación mientras carga y cuando no hay filas', async () => {
    server.use(warehouseReportEmpty())
    renderReportes()
    expect(screen.getByRole('button', { name: /Exportar CSV/ })).toBeDisabled()
    await screen.findByText('Sin movimientos en el rango')
    expect(screen.getByRole('button', { name: /Exportar CSV/ })).toBeDisabled()
  })

  // ----- Errores y degradación -----
  it('muestra el detail del backend y permite reintentar', async () => {
    server.use(warehouseReportError(500, { detail: 'El servidor falló al generar el reporte.' }))
    const user = userEvent.setup()
    renderReportes()
    expect(await screen.findByText('El servidor falló al generar el reporte.')).toBeInTheDocument()
    server.use(warehouseReport())
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Tracto ABC-123')).toBeInTheDocument()
  })

  it('muestra el 400 del backend si un rango inválido llega al servidor', async () => {
    server.use(
      warehouseReportError(400, { detail: 'dateFrom no puede ser posterior a dateTo' }),
    )
    renderReportes()
    expect(await screen.findByText('dateFrom no puede ser posterior a dateTo')).toBeInTheDocument()
  })

  it('cae al mensaje de respaldo cuando el error no trae detalle', async () => {
    server.use(warehouseReportError(500, { detail: undefined }))
    renderReportes()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('no deja el reporte del corte anterior cuando falla el corte nuevo', async () => {
    server.use(warehouseReportOkThenErrorOnOtherCut(fakeReport()))
    const user = userEvent.setup()
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    await user.click(screen.getByRole('tab', { name: 'Por producto' }))
    expect(await screen.findByText('Fallo al generar el reporte')).toBeInTheDocument()
    // Retener las filas del corte anterior las haría pasar por el corte nuevo:
    // el panel ya dice "Por producto". Ante el error, el panel queda vacío.
    expect(screen.queryByText('Tracto ABC-123')).not.toBeInTheDocument()
  })

  it('deja los controles usables cuando el reporte falla', async () => {
    server.use(warehouseReportError(500))
    renderReportes()
    await screen.findByRole('alert')
    expect(screen.getByRole('tab', { name: 'Por producto' })).toBeEnabled()
    expect(screen.getByLabelText('Desde')).toBeEnabled()
  })

  // ----- A11y -----
  it('presenta la pantalla con un único h1', async () => {
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('asocia el panel de resultados con el tab activo', async () => {
    renderReportes()
    await screen.findByText('Tracto ABC-123')
    const panel = screen.getByRole('tabpanel')
    expect(panel).toHaveAttribute('aria-labelledby', 'report-tab-BY_UNIT')
  })

  it('no tiene violaciones de accesibilidad con datos', async () => {
    const { container } = renderReportes()
    await screen.findByText('Tracto ABC-123')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad en el estado vacío', async () => {
    server.use(warehouseReportEmpty())
    const { container } = renderReportes()
    await screen.findByText('Sin movimientos en el rango')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad en el estado de error', async () => {
    server.use(warehouseReportError(500))
    const { container } = renderReportes()
    await screen.findByRole('alert')
    expect(await axe(container)).toHaveNoViolations()
  })
})
