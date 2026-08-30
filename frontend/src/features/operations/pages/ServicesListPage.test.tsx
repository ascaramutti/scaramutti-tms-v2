import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { ServicesListPage } from './ServicesListPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import type { UserRole } from '../../../api'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import type { ServicesCaptureSink } from '../../../test/mocks/handlers/operations'
import {
  fakeAssignedService,
  fakeDispatcherServiceSummary,
  fakeServiceSummary,
  serviceStatsError,
  serviceStatsOk,
  servicesCapture,
  servicesEmpty,
  servicesError,
  servicesErrorWithoutBody,
  servicesNetworkError,
  servicesOkThenErrorOnNextPage,
  servicesPage,
  servicesPagedByParam,
  servicesPagedByParamSlow,
  servicesSlow,
} from '../../../test/mocks/handlers/operations'

/** Sustituta del detalle que nombra el id de la ruta, para poder afirmar cuál abrió. */
function DetalleStub() {
  const { id } = useParams()
  return <div>Detalle del servicio {id}</div>
}

function renderServicios({ role = 'admin' as UserRole } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // La página vive dentro del layout autenticado: sesión sembrada en cache.
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, { ...fakeUser, role })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/cotizaciones/operaciones']}>
          <Routes>
            <Route path="/cotizaciones/operaciones" element={<ServicesListPage />} />
            {/* Sustituta del alta: la pantalla real se prueba en su propio archivo. */}
            <Route
              path="/cotizaciones/operaciones/servicios/nuevo"
              element={<div>Ruta del alta</div>}
            />
            {/* Sustituta del detalle, que nombra el id recibido: sin eso, un test
                de navegación no distingue "abrió el detalle correcto" de "abrió
                alguno". */}
            <Route path="/cotizaciones/operaciones/servicios/:id" element={<DetalleStub />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Sube del nodo de texto a su `<tr>` para acotar asserts a una fila. */
function rowOf(textNode: HTMLElement): HTMLElement {
  return textNode.closest('tr') as HTMLElement
}

describe('ServicesListPage', () => {
  // ----- Alta de un servicio -----
  it.each(['admin', 'sales', 'general_manager', 'operations_manager'] as const)(
    'ofrece registrar un servicio a %s',
    async (role) => {
      renderServicios({ role })
      expect(await screen.findByRole('button', { name: /nuevo servicio/i })).toBeInTheDocument()
    },
  )

  it('no le ofrece registrar al despacho, que no puede mandar el precio', async () => {
    server.use(servicesPage([fakeDispatcherServiceSummary()]))
    renderServicios({ role: 'dispatcher' })
    // Esperar la tabla: sin eso, la ausencia del botón se afirma sobre una pantalla
    // que todavía no terminó de renderizar y daría verde por vacía.
    expect(await screen.findByText('SRV-0042')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nuevo servicio/i })).not.toBeInTheDocument()
  })

  it('el botón lleva al alta', async () => {
    const user = userEvent.setup()
    renderServicios()
    await user.click(await screen.findByRole('button', { name: /nuevo servicio/i }))
    expect(await screen.findByText('Ruta del alta')).toBeInTheDocument()
  })

  // ----- Render de la tabla -----
  it('muestra el spinner de la tabla durante la carga inicial', async () => {
    // Los indicadores resuelven de una: si tardaran, sus SEIS spinners estarían
    // en pantalla y un conteo global daría verde con la tabla en cualquier
    // estado. El caso mide la tabla, así que acota a la tabla.
    server.use(serviceStatsOk(), servicesSlow([fakeServiceSummary()], 60))
    renderServicios()
    await waitFor(() => expect(screen.getByText('Pend. asignación')).toBeInTheDocument())
    await waitFor(() => expect(screen.queryAllByRole('status')).toHaveLength(1))
    // Esperar la fila evita dejar el fetch colgado al desmontar.
    expect(await screen.findByText('SRV-0042')).toBeInTheDocument()
  })

  it('renderiza las filas de la página', async () => {
    server.use(
      servicesPage([
        fakeServiceSummary(),
        fakeAssignedService(),
        fakeServiceSummary({ id: 44, code: 'SRV-0044' }),
      ]),
    )
    renderServicios()
    expect(await screen.findByText('SRV-0042')).toBeInTheDocument()
    expect(screen.getByText('SRV-0043')).toBeInTheDocument()
    expect(screen.getByText('SRV-0044')).toBeInTheDocument()
  })

  it('mapea los campos del servicio en su fila', async () => {
    server.use(servicesPage([fakeAssignedService()]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0043'))
    expect(within(fila).getByText('IPH S.A.C.')).toBeInTheDocument()
    expect(within(fila).getByText('20123456789')).toBeInTheDocument()
    expect(within(fila).getByText(/Piura → Lima — Callao/)).toBeInTheDocument()
    expect(within(fila).getByText('Juan Pérez')).toBeInTheDocument()
    expect(within(fila).getByText('ABC-123')).toBeInTheDocument()
    expect(within(fila).getByText('En ruta')).toBeInTheDocument()
    expect(within(fila).getByText('10/07/2026')).toBeInTheDocument()
  })

  it('muestra el ámbito del viaje en la celda de ruta', async () => {
    server.use(
      servicesPage([
        fakeServiceSummary({ tripScope: 'PROVINCIA' }),
        fakeServiceSummary({ id: 45, code: 'SRV-0045', tripScope: 'LOCAL' }),
      ]),
    )
    renderServicios()
    const provincia = rowOf(await screen.findByText('SRV-0042'))
    const local = rowOf(screen.getByText('SRV-0045'))
    expect(within(provincia).getByText('Provincia')).toBeInTheDocument()
    expect(within(local).getByText('Local')).toBeInTheDocument()
  })

  it('escribe el ámbito en minúsculas y lo muestra en mayúsculas por estilo', async () => {
    // El texto va en minúsculas en el DOM a propósito: un lector de pantalla
    // deletrea las mayúsculas escritas a mano ("P-R-O-V…"). Las mayúsculas las
    // pone el CSS, que no cambia lo que se anuncia.
    server.use(servicesPage([fakeServiceSummary({ tripScope: 'PROVINCIA' })]))
    renderServicios()
    const ambito = await screen.findByText('Provincia')
    expect(ambito.textContent).toBe('Provincia')
    expect(ambito.className).toContain('uppercase')
  })

  it('muestra origen y destino como dos datos separados', async () => {
    // El contrato los separa a propósito: un viaje puede empezar y terminar en
    // el mismo lugar sin que eso sea un dato repetido por error.
    server.use(servicesPage([fakeServiceSummary({ origin: 'Piura', destination: 'Piura' })]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0042'))
    expect(within(fila).getByText('Piura → Piura')).toBeInTheDocument()
  })

  it('rotula los seis estados del ciclo de vida', async () => {
    server.use(
      servicesPage([
        fakeServiceSummary({ id: 1, code: 'SRV-0001', status: 'PENDING_ASSIGNMENT' }),
        fakeServiceSummary({ id: 2, code: 'SRV-0002', status: 'PENDING_START' }),
        fakeServiceSummary({ id: 3, code: 'SRV-0003', status: 'IN_PROGRESS' }),
        fakeServiceSummary({ id: 4, code: 'SRV-0004', status: 'COMPLETED' }),
        fakeServiceSummary({ id: 5, code: 'SRV-0005', status: 'CANCELLED' }),
        fakeServiceSummary({ id: 6, code: 'SRV-0006', status: 'DELETED' }),
      ]),
    )
    renderServicios()
    await screen.findByText('SRV-0001')
    // Acotado a la tabla: "En ruta" es también el rótulo de un tile del strip,
    // y sin acotar el caso mediría el indicador en vez del badge.
    const tabla = within(screen.getByRole('table'))
    expect(tabla.getByText('Pendiente de asignación')).toBeInTheDocument()
    expect(tabla.getByText('Pendiente de inicio')).toBeInTheDocument()
    expect(tabla.getByText('En ruta')).toBeInTheDocument()
    expect(tabla.getByText('Completado')).toBeInTheDocument()
    expect(tabla.getByText('Cancelado')).toBeInTheDocument()
    expect(tabla.getByText('Eliminado')).toBeInTheDocument()
  })

  it('muestra "Sin asignar" cuando el viaje no tiene conductor ni tracto', async () => {
    server.use(servicesPage([fakeServiceSummary({ driver: null, tractor: null })]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0042'))
    expect(within(fila).getByText('Sin asignar')).toBeInTheDocument()
    expect(fila.textContent).not.toMatch(/null|undefined/)
  })

  it('muestra la fecha tentativa sin correrla de día', async () => {
    // Date-only en el borde del año: si pasara por `new Date(iso)` se
    // interpretaría como medianoche UTC y en Lima (UTC-5) daría 31/12/2025.
    // El literal es el punto del caso: no derivarlo del formateador.
    server.use(servicesPage([fakeServiceSummary({ tentativeDate: '2026-01-01' })]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0042'))
    expect(within(fila).getByText('01/01/2026')).toBeInTheDocument()
  })

  it('formatea el precio con su moneda', async () => {
    server.use(
      servicesPage([
        fakeServiceSummary({ price: 5800, currencyCode: 'PEN' }),
        fakeServiceSummary({ id: 45, code: 'SRV-0045', price: 1200.5, currencyCode: 'USD' }),
      ]),
    )
    renderServicios()
    const soles = rowOf(await screen.findByText('SRV-0042'))
    const dolares = rowOf(screen.getByText('SRV-0045'))
    expect(within(soles).getByText(/S\/\s*5,800\.00/)).toBeInTheDocument()
    // El símbolo del dólar depende del ICU del entorno ("US$" o "USD").
    expect(within(dolares).getByText(/^(US\$|USD)\s*1,200\.50$/)).toBeInTheDocument()
  })

  it('degrada el precio cuando falta la moneda', async () => {
    // El contrato marca `price` y `currencyCode` opcionales POR SEPARADO. Sin la
    // mitad `currencyCode` de la guarda, `formatCurrency` degrada al código de
    // moneda crudo y la celda imprimiría "undefined 5,800.00".
    const sinMoneda = fakeServiceSummary({ price: 5800 })
    delete sinMoneda.currencyCode
    server.use(servicesPage([sinMoneda]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0042'))
    expect(within(fila).getByText('—')).toBeInTheDocument()
    expect(fila.textContent).not.toMatch(/undefined/)
  })

  it('muestra el precio cero como dato y no como vacío', async () => {
    server.use(servicesPage([fakeServiceSummary({ price: 0, currencyCode: 'PEN' })]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0042'))
    expect(within(fila).getByText(/S\/\s*0\.00/)).toBeInTheDocument()
  })

  it('degrada el precio ausente sin romper la fila', async () => {
    // El contrato tipa `price` y `currencyCode` como opcionales para todos, no
    // solo para el despacho: una fila sin ellos se renderiza igual.
    server.use(servicesPage([fakeDispatcherServiceSummary()]))
    renderServicios({ role: 'admin' })
    const fila = rowOf(await screen.findByText('SRV-0042'))
    expect(within(fila).getByText('—')).toBeInTheDocument()
    expect(within(fila).getByText('IPH S.A.C.')).toBeInTheDocument()
  })

  it('muestra el estado vacío de base cuando no hay servicios ni filtros', async () => {
    server.use(servicesEmpty())
    renderServicios()
    expect(await screen.findByText('Aún no hay servicios')).toBeInTheDocument()
    expect(screen.getByText(/todavía no hay servicios registrados/i)).toBeInTheDocument()
  })

  it('muestra el estado vacío de filtros cuando hay filtros activos y cero resultados', async () => {
    server.use(servicesEmpty())
    renderServicios()
    await screen.findByText(/todavía no hay servicios registrados/i)
    await userEvent.selectOptions(screen.getByLabelText('Estado'), 'IN_PROGRESS')
    // El título también cambia: "no se encontraron" y "aún no hay" dicen cosas
    // distintas y solo una es cierta en cada caso.
    expect(await screen.findByText('No se encontraron servicios')).toBeInTheDocument()
    expect(screen.getByText(/prueba ajustar los filtros/i)).toBeInTheDocument()
  })

  it.each([
    ['la búsqueda', async () => userEvent.type(screen.getByLabelText('Buscar'), 'SRV')],
    ['el rango desde', async () => userEvent.type(screen.getByLabelText('Desde'), '2026-07-01')],
    ['el rango hasta', async () => userEvent.type(screen.getByLabelText('Hasta'), '2026-07-31')],
  ])('reconoce %s como filtro activo en el estado vacío', async (_nombre, aplicar) => {
    // Cada rama de `hasActiveFilters` decide cuál de los dos textos vacíos se
    // muestra: "no coincide con los filtros" y "todavía no hay servicios" son
    // mensajes distintos y solo uno es cierto.
    server.use(servicesEmpty())
    renderServicios()
    await screen.findByText(/todavía no hay servicios registrados/i)
    await aplicar()
    expect(
      await screen.findByText(/prueba ajustar los filtros/i),
    ).toBeInTheDocument()
  })

  // ----- Interacción y navegación -----
  it('la fila entera abre el detalle, sin un botón que haga lo mismo', async () => {
    // Antes este caso afirmaba lo contrario (la fila inerte), porque la pantalla
    // de detalle no existía y navegar a una ruta inexistente era peor que no
    // navegar. Ya existe, así que la fila navega; lo que se sigue fijando es que
    // la acción sea UNA, sin una columna de botón duplicando el mismo destino.
    server.use(servicesPage([fakeServiceSummary()]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0042'))
    expect(fila).toHaveAttribute('role', 'button')
    expect(within(fila).queryByRole('button')).not.toBeInTheDocument()
  })

  it('muestra el error del backend y permite reintentar', async () => {
    server.use(servicesError(500, { detail: 'El servidor falló al listar.' }))
    renderServicios()
    expect(await screen.findByText('El servidor falló al listar.')).toBeInTheDocument()
    server.use(servicesPage([fakeServiceSummary()]))
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(await screen.findByText('SRV-0042')).toBeInTheDocument()
  })

  // ----- Filtros y paginación -----
  it('no envía q con menos de 3 caracteres y muestra el mensaje guía', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Buscar'), 'SR')
    expect(await screen.findByText(/al menos una palabra de 3 caracteres/i)).toBeInTheDocument()
    await waitFor(() => expect(sink.calls?.length).toBeGreaterThan(0))
    expect(sink.calls?.every((params) => params.get('q') === null)).toBe(true)
  })

  it('envía q con 3 o más caracteres', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Buscar'), 'SRV')
    await waitFor(() => expect(sink.params?.get('q')).toBe('SRV'))
  })

  it('no busca cuando ninguna palabra alcanza el mínimo que el backend indexa', async () => {
    // "de la" tiene 5 caracteres y cero palabras útiles: con un gate por largo
    // total saldría a buscar para volver con un 400 evitable.
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Buscar'), 'de la')
    expect(await screen.findByText(/al menos una palabra de 3 caracteres/i)).toBeInTheDocument()
    await waitFor(() => expect(sink.calls?.length).toBeGreaterThan(0))
    expect(sink.calls?.every((params) => params.get('q') === null)).toBe(true)
  })

  it('busca cuando al menos una palabra alcanza el mínimo', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Buscar'), 'de piura')
    await waitFor(() => expect(sink.params?.get('q')).toBe('de piura'))
  })

  it('espera a que se deje de tipear antes de consultar', async () => {
    // Sin debounce sale una request por tecla: ocho consultas para escribir
    // "SERVICIO", siete de ellas ya obsoletas al llegar.
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Buscar'), 'SERVICIO')
    await waitFor(() => expect(sink.params?.get('q')).toBe('SERVICIO'))
    expect(sink.calls?.filter((params) => params.get('q')).length).toBe(1)
  })

  it('recorta los espacios del término antes de mandarlo', async () => {
    // Sin recortar, el backend tokenizaría los espacios de sobra y el término
    // dejaría de ser el que el usuario escribió.
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Buscar'), '  SRV  ')
    await waitFor(() => expect(sink.params?.get('q')).toBe('SRV'))
  })

  it('deja de enviar q al borrar la búsqueda', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    const buscar = screen.getByLabelText('Buscar')
    await userEvent.type(buscar, 'SRV')
    await waitFor(() => expect(sink.params?.get('q')).toBe('SRV'))
    await userEvent.clear(buscar)
    await waitFor(() => expect(sink.params?.get('q')).toBeNull())
  })

  it('filtra por estado', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.selectOptions(screen.getByLabelText('Estado'), 'IN_PROGRESS')
    await waitFor(() => expect(sink.params?.get('status')).toBe('IN_PROGRESS'))
  })

  it('ofrece filtrar por eliminados', async () => {
    // El contrato los esconde salvo que se pidan explícitamente: sin esta opción
    // un servicio eliminado sería inalcanzable desde la pantalla.
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.selectOptions(screen.getByLabelText('Estado'), 'DELETED')
    await waitFor(() => expect(sink.params?.get('status')).toBe('DELETED'))
  })

  it('el estado "Todos" omite el parámetro status', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    const estado = screen.getByLabelText('Estado')
    await userEvent.selectOptions(estado, 'IN_PROGRESS')
    await waitFor(() => expect(sink.params?.get('status')).toBe('IN_PROGRESS'))
    await userEvent.selectOptions(estado, '')
    await waitFor(() => expect(sink.params?.get('status')).toBeNull())
  })

  it('filtra por rango de fechas', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Desde'), '2026-07-01')
    await userEvent.type(screen.getByLabelText('Hasta'), '2026-07-31')
    await waitFor(() => {
      expect(sink.params?.get('dateFrom')).toBe('2026-07-01')
      expect(sink.params?.get('dateTo')).toBe('2026-07-31')
    })
  })

  it('filtra por un solo día con las dos fechas iguales', async () => {
    // El caso más frecuente de un tablero operativo: "qué se movió hoy". El
    // rango es válido cuando los extremos coinciden, así que las dos fechas
    // viajan y no aparece el aviso de rango invertido.
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Desde'), '2026-07-15')
    await userEvent.type(screen.getByLabelText('Hasta'), '2026-07-15')
    await waitFor(() => {
      expect(sink.params?.get('dateFrom')).toBe('2026-07-15')
      expect(sink.params?.get('dateTo')).toBe('2026-07-15')
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('filtra con un extremo del rango sin cargar', async () => {
    // Media fecha es un rango legítimo ("desde el 1 de julio en adelante"): el
    // extremo cargado viaja solo, sin esperar a que se complete el par.
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Desde'), '2026-07-01')
    await waitFor(() => expect(sink.params?.get('dateFrom')).toBe('2026-07-01'))
    expect(sink.params?.get('dateTo')).toBeNull()
  })

  it('avisa del rango invertido y no consulta con él', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Hasta'), '2026-07-01')
    await userEvent.type(screen.getByLabelText('Desde'), '2026-07-31')
    expect(await screen.findByRole('alert')).toHaveTextContent(/no puede ser posterior/i)
    // Ya con el rango invertido, NINGUNA de las dos fechas viaja. Pedir solo que
    // no viajen juntas deja pasar media fecha, que filtraría de verdad y
    // devolvería un resultado recortado sin que el usuario lo sepa.
    // Se mide la última consulta y no todas: con "hasta" cargada y "desde"
    // todavía vacía el rango es válido, así que ese tramo sí consulta.
    await waitFor(() => {
      expect(sink.params?.get('dateFrom')).toBeNull()
      expect(sink.params?.get('dateTo')).toBeNull()
    })
  })

  it('no topea las fechas a hoy', async () => {
    // El contrato admite fecha tentativa pasada (registro retroactivo) y futura:
    // capar el input a hoy escondería viajes que existen.
    server.use(servicesPage([fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    // Los dos extremos de la ventana que admite el contrato, no solo el de arriba.
    expect(screen.getByLabelText('Desde')).toHaveAttribute('min', '1900-01-01')
    expect(screen.getByLabelText('Desde')).toHaveAttribute('max', '2999-12-31')
    expect(screen.getByLabelText('Hasta')).toHaveAttribute('min', '1900-01-01')
    expect(screen.getByLabelText('Hasta')).toHaveAttribute('max', '2999-12-31')
    // Tope del término, espejo del `maxLength` del contrato.
    expect(screen.getByLabelText('Buscar')).toHaveAttribute('maxlength', '200')
  })

  it('reinicia a la primera página al cambiar un filtro', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()], { totalElements: 30, size: 10 }))
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.click(screen.getByRole('button', { name: /siguiente/i }))
    await waitFor(() => expect(sink.params?.get('page')).toBe('1'))
    await userEvent.selectOptions(screen.getByLabelText('Estado'), 'IN_PROGRESS')
    await waitFor(() => expect(sink.params?.get('page')).toBe('0'))
  })

  it('pide la página siguiente al avanzar', async () => {
    server.use(servicesPagedByParam(30, 10))
    renderServicios()
    expect(await screen.findByText('SRV-P0-1')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText('SRV-P1-1')).toBeInTheDocument()
  })

  it('no parpadea a spinner mientras carga la página siguiente', async () => {
    // `keepPreviousData`: al paginar, la tabla anterior sigue a la vista hasta que
    // llega la nueva. Sin él la query vuelve a estado de carga y la tabla
    // desaparece detrás del spinner en cada avance.
    server.use(servicesPagedByParamSlow(30, 10, 60))
    renderServicios()
    await screen.findByText('SRV-P0-1')
    await userEvent.click(screen.getByRole('button', { name: /siguiente/i }))
    // Mientras la página nueva viaja, las filas viejas siguen a la vista y la
    // tabla NO se reemplaza por el spinner.
    expect(screen.getByText('SRV-P0-1')).toBeInTheDocument()
    expect(screen.queryByRole('status', { name: 'Cargando' })).not.toBeInTheDocument()
    expect(await screen.findByText('SRV-P1-1')).toBeInTheDocument()
  })

  it('mantiene la tabla previa y avisa cuando falla el refetch al paginar', async () => {
    server.use(
      servicesOkThenErrorOnNextPage([fakeServiceSummary()], { totalElements: 30, size: 10 }),
    )
    renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText('Fallo al paginar')).toBeInTheDocument()
    // La fila previa sigue en pantalla: el error no vacía la tabla.
    expect(screen.getByText('SRV-0042')).toBeInTheDocument()
  })

  // ----- Permisos y rol -----
  it('el despachador no ve la columna de precio', async () => {
    server.use(servicesPage([fakeDispatcherServiceSummary()]))
    renderServicios({ role: 'dispatcher' })
    await screen.findByText('SRV-0042')
    expect(screen.queryByRole('columnheader', { name: 'Precio' })).not.toBeInTheDocument()
  })

  it.each(['admin', 'sales', 'general_manager', 'operations_manager'] as const)(
    'el rol %s ve la columna de precio',
    async (role) => {
      server.use(servicesPage([fakeServiceSummary()]))
      renderServicios({ role })
      await screen.findByText('SRV-0042')
      expect(screen.getByRole('columnheader', { name: 'Precio' })).toBeInTheDocument()
      expect(screen.getByText(/S\/\s*5,800\.00/)).toBeInTheDocument()
    },
  )

  it('el despachador no ve el precio aunque el backend lo mande', async () => {
    // La ocultación se decide por rol, no por la ausencia del campo: un mock mal
    // armado (o un cambio de forma del veto) no debe filtrar el importe.
    server.use(servicesPage([fakeServiceSummary({ price: 9999, currencyCode: 'PEN' })]))
    renderServicios({ role: 'dispatcher' })
    await screen.findByText('SRV-0042')
    expect(screen.queryByRole('columnheader', { name: 'Precio' })).not.toBeInTheDocument()
    expect(screen.queryByText(/9,999/)).not.toBeInTheDocument()
  })

  it('la tabla del despachador tiene una columna menos que la del resto', async () => {
    // Caza el caso de "oculté la celda pero no el th": la tabla quedaría corrida.
    server.use(servicesPage([fakeServiceSummary()]))
    const { unmount } = renderServicios({ role: 'admin' })
    await screen.findByText('SRV-0042')
    const columnasAdmin = screen.getAllByRole('columnheader').length
    unmount()

    server.use(servicesPage([fakeDispatcherServiceSummary()]))
    renderServicios({ role: 'dispatcher' })
    await screen.findByText('SRV-0042')
    expect(screen.getAllByRole('columnheader')).toHaveLength(columnasAdmin - 1)
  })

  it('muestra el 403 del backend con su detalle y no con un texto inventado', async () => {
    // La pantalla no es la autoridad de permisos: si el servidor rechaza, se
    // muestra lo que el servidor dice.
    server.use(servicesError(403, { detail: 'No tiene permisos para acceder a este recurso' }))
    renderServicios({ role: 'dispatcher' })
    expect(
      await screen.findByText('No tiene permisos para acceder a este recurso'),
    ).toBeInTheDocument()
  })

  // ----- Integración API y errores -----
  it('pide el tamaño de página acordado', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    expect(sink.params?.get('size')).toBe('10')
  })

  it('no manda parámetros vacíos', async () => {
    const sink: ServicesCaptureSink = {}
    server.use(servicesCapture(sink, [fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    for (const key of ['q', 'status', 'dateFrom', 'dateTo']) {
      expect(sink.params?.get(key)).toBeNull()
    }
  })

  it('muestra el detalle de un 400 por estado inexistente', async () => {
    server.use(servicesError(400, { detail: 'El estado GARBAGE no existe' }))
    renderServicios()
    expect(await screen.findByText('El estado GARBAGE no existe')).toBeInTheDocument()
  })

  it('muestra el detalle de un 400 por término inválido', async () => {
    // El front no replica el tope de 8 palabras ni el rechazo de caracteres de
    // control: los reporta el backend y el usuario tiene que leer el porqué.
    server.use(
      servicesError(400, {
        detail: 'El término de búsqueda no admite caracteres de control',
      }),
    )
    renderServicios()
    expect(
      await screen.findByText('El término de búsqueda no admite caracteres de control'),
    ).toBeInTheDocument()
  })

  it('usa el texto genérico solo cuando el error no trae detalle', async () => {
    server.use(
      servicesErrorWithoutBody(),
    )
    renderServicios()
    expect(await screen.findByText('No se pudieron cargar los servicios.')).toBeInTheDocument()
  })

  it('un fallo de red muestra el texto genérico y ofrece reintentar', async () => {
    server.use(servicesNetworkError())
    renderServicios()
    expect(await screen.findByText('No se pudieron cargar los servicios.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reintentar/i })).toBeInTheDocument()
  })

  // ----- Indicadores dentro de la página -----
  it('el strip falla y la tabla sigue mostrando los servicios', async () => {
    server.use(
      serviceStatsError(500, { detail: 'Fallo al calcular indicadores' }),
      servicesPage([fakeServiceSummary()]),
    )
    renderServicios()
    expect(await screen.findByText('SRV-0042')).toBeInTheDocument()
    expect(screen.getByText('Fallo al calcular indicadores')).toBeInTheDocument()
  })

  it('la tabla falla y el strip sigue mostrando los indicadores', async () => {
    server.use(serviceStatsOk(), servicesError(500, { detail: 'Fallo al listar' }))
    renderServicios()
    expect(await screen.findByText('Fallo al listar')).toBeInTheDocument()
    const completados = screen.getByText('Completados (semana)').parentElement as HTMLElement
    expect(within(completados).getByText('11')).toBeInTheDocument()
  })

  // ----- Navegación al detalle -----
  it('abre el detalle del viaje al hacer clic en su fila', async () => {
    server.use(servicesPage([fakeServiceSummary({ id: 42 })]))
    renderServicios()
    await userEvent.click(await screen.findByText('SRV-0042'))

    expect(await screen.findByText('Detalle del servicio 42')).toBeInTheDocument()
  })

  it('lleva al viaje de la fila en la que se hizo clic, no al primero', async () => {
    // Con una sola fila, un id fijo pasaría el test igual. Dos filas obligan a que
    // el id salga de la que se tocó.
    server.use(
      servicesPage([
        fakeServiceSummary({ id: 42, code: 'SRV-0042' }),
        fakeServiceSummary({ id: 91, code: 'SRV-0091' }),
      ]),
    )
    renderServicios()
    await userEvent.click(await screen.findByText('SRV-0091'))

    expect(await screen.findByText('Detalle del servicio 91')).toBeInTheDocument()
  })

  it('cada fila se anuncia por el viaje que abre, no por su contenido entero', async () => {
    // Sin rótulo, el nombre accesible de una fila clickeable es el texto de todas
    // sus celdas concatenado, que es lo que oye quien navega con lector de
    // pantalla.
    server.use(servicesPage([fakeServiceSummary({ id: 42 })]))
    renderServicios()
    const fila = rowOf(await screen.findByText('SRV-0042'))

    expect(fila).toHaveAccessibleName('Ver el servicio SRV-0042 de IPH S.A.C.')
  })

  it('también abre el detalle por teclado', async () => {
    server.use(servicesPage([fakeServiceSummary({ id: 42 })]))
    renderServicios()
    const row = rowOf(await screen.findByText('SRV-0042'))
    row.focus()
    await userEvent.keyboard('{Enter}')

    expect(await screen.findByText('Detalle del servicio 42')).toBeInTheDocument()
  })

  it('el despacho también entra al detalle: lo que no ve es el precio', async () => {
    server.use(servicesPage([fakeDispatcherServiceSummary({ id: 42 })]))
    renderServicios({ role: 'dispatcher' })
    await userEvent.click(await screen.findByText('SRV-0042'))

    expect(await screen.findByText('Detalle del servicio 42')).toBeInTheDocument()
  })

  // ----- Accesibilidad -----
  it('presenta la pantalla con un único h1', async () => {
    server.use(servicesPage([fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    const encabezados = screen.getAllByRole('heading', { level: 1 })
    expect(encabezados).toHaveLength(1)
    expect(encabezados[0]).toHaveAccessibleName('Servicios')
    // El texto del header es todo lo que la pantalla dice de sí misma: sin esta
    // línea, vaciarlo o cambiarlo por cualquier cosa no rompe nada.
    expect(screen.getByText(/control de viajes/i)).toBeInTheDocument()
  })

  it('declara por qué fecha está ordenada la tabla', async () => {
    // El orden es por fecha de registro, que NO es la fecha que la tabla muestra.
    // Va en el `caption` (para lectores de pantalla) y también a la vista, porque
    // el `caption` es sr-only.
    server.use(servicesPage([fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    expect(screen.getByRole('table')).toHaveAccessibleName(
      'Servicios, ordenados por fecha de registro (más recientes primero)',
    )
    expect(
      screen.getByText('Ordenados por fecha de registro, los más recientes primero.'),
    ).toBeInTheDocument()
  })

  it('expone los encabezados de columna', async () => {
    server.use(servicesPage([fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    const nombres = screen.getAllByRole('columnheader').map((th) => th.textContent)
    // El importe antes del estado y la fecha cerrando la fila, como en el
    // listado de cotizaciones.
    expect(nombres).toEqual([
      'Código',
      'Cliente',
      'Ruta',
      'Conductor / Unidad',
      'Precio',
      'Estado',
      'Fecha tentativa',
    ])
  })

  it('rotula todos los controles de filtro', async () => {
    server.use(servicesPage([fakeServiceSummary()]))
    renderServicios()
    await screen.findByText('SRV-0042')
    expect(screen.getByLabelText('Buscar')).toBeInTheDocument()
    expect(screen.getByLabelText('Estado')).toBeInTheDocument()
    // El texto le avisa al usuario que el listado por defecto esconde las bajas:
    // sin esta línea, volver a "Todos" a secas no rompe nada.
    const estados = within(screen.getByLabelText('Estado')).getAllByRole('option')
    expect(estados.map((option) => option.textContent)).toEqual([
      'Todos (sin eliminados)',
      'Pendiente de asignación',
      'Pendiente de inicio',
      'En ruta',
      'Completado',
      'Cancelado',
      'Eliminado',
    ])
    expect(screen.getByLabelText('Desde')).toBeInTheDocument()
    expect(screen.getByLabelText('Hasta')).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad con la tabla cargada', async () => {
    server.use(servicesPage([fakeAssignedService()]))
    const { container } = renderServicios()
    await screen.findByText('SRV-0043')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad con el rango de fechas inválido', async () => {
    // El estado con `aria-invalid`, `aria-describedby` y un `role="alert"` que
    // aparece y desaparece: es donde se rompen las referencias entre nodos.
    server.use(servicesPage([fakeServiceSummary()]))
    const { container } = renderServicios()
    await screen.findByText('SRV-0042')
    await userEvent.type(screen.getByLabelText('Hasta'), '2026-07-01')
    await userEvent.type(screen.getByLabelText('Desde'), '2026-07-31')
    await screen.findByRole('alert')
    expect(screen.getByLabelText('Desde')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Hasta')).toHaveAttribute('aria-invalid', 'true')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad con las dos consultas en error', async () => {
    server.use(
      servicesError(500, { detail: 'Fallo al listar' }),
      serviceStatsError(500, { detail: 'Fallo de indicadores' }),
    )
    const { container } = renderServicios()
    await screen.findByText('Fallo al listar')
    // Los dos botones de reintento conviven: sus nombres accesibles tienen que
    // distinguirse, y este es el único caso donde ambos existen a la vez.
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Reintentar cargar los indicadores' }),
    ).toBeInTheDocument()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad en el estado vacío', async () => {
    server.use(servicesEmpty())
    const { container } = renderServicios()
    await screen.findByText(/todavía no hay servicios registrados/i)
    expect(await axe(container)).toHaveNoViolations()
  })
})
