import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { OpeningBalancesPage } from './OpeningBalancesPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import type { UserResponse } from '../../../api'
import { server } from '../../../test/mocks/server'
import type {
  BodyCaptureSink,
  ProductsCaptureSink,
} from '../../../test/mocks/handlers/warehouse'
import {
  fakeOpeningBalance,
  fakeProduct,
  openingBalanceCapture,
  openingBalanceError,
  openingBalanceSlow,
  openingBalancesCapture,
  openingBalancesEmpty,
  openingBalancesError,
  openingBalancesPage,
  openingBalancesPagedByParam,
  openingBalancesSequence,
  openingBalancesSlow,
  pageOfOpeningBalances,
  warehouseProductsCapture,
  warehouseProductsEmpty,
  warehouseProductsError,
} from '../../../test/mocks/handlers/warehouse'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const CORTE_INICIAL_PATH = '/cotizaciones/almacen/corte-inicial'

/** Por defecto admin: es el único rol que registra, y el form es casi toda la pantalla. */
function renderCorteInicial(role: UserResponse['role'] = 'admin') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, { ...fakeUser, role })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[CORTE_INICIAL_PATH]}>
          <Routes>
            <Route path={CORTE_INICIAL_PATH} element={<OpeningBalancesPage />} />
            <Route
              path="/cotizaciones/almacen/productos/:id"
              element={<div>KARDEX STUB</div>}
            />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Elige un producto en el combobox del formulario de alta. */
async function selectProduct(user: UserEvent, name = 'Filtro de aceite XYZ') {
  await user.type(screen.getByLabelText('Producto'), 'fil')
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(name))
}

/** Elige un producto en el combobox del FILTRO del listado (sin label visible). */
async function selectFilterProduct(user: UserEvent, name = 'Filtro de aceite XYZ') {
  await user.type(screen.getByLabelText('Filtrar por producto'), 'fil')
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByText(name))
}

function rowOf(textNode: HTMLElement): HTMLElement {
  return textNode.closest('tr') as HTMLElement
}

describe('OpeningBalancesPage', () => {
  // ----- Render -----
  it('renderiza los campos del formulario de corte inicial', () => {
    renderCorteInicial()
    expect(screen.getByLabelText('Producto')).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad inicial')).toBeInTheDocument()
    expect(screen.getByLabelText(/observaciones/i)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /registrar corte inicial/i }),
    ).toBeInTheDocument()
  })

  // El 0 debe ser una decisión explícita del operador, no un default que se cuele.
  it('la cantidad arranca vacía, no precargada en 0', () => {
    renderCorteInicial()
    expect(screen.getByLabelText('Cantidad inicial')).toHaveValue(null)
  })

  it('explica que el corte inicial es único e irreversible por producto', () => {
    renderCorteInicial()
    expect(screen.getByText(/una sola vez por producto/i)).toBeInTheDocument()
    expect(screen.getByText(/puede ser 0/i)).toBeInTheDocument()
  })

  it('muestra el spinner del listado durante la carga inicial', async () => {
    server.use(openingBalancesSlow([fakeOpeningBalance()], 40))
    renderCorteInicial()
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    expect(await screen.findByText('Filtro de aceite XYZ')).toBeInTheDocument()
  })

  it('renderiza las aperturas ya registradas', async () => {
    renderCorteInicial()
    expect(await screen.findByText('Filtro de aceite XYZ')).toBeInTheDocument()
    expect(screen.getByText('Aceite 15W40')).toBeInTheDocument()
    expect(screen.getByText('Perno hexagonal 1/2')).toBeInTheDocument()
  })

  it('mapea los campos de la apertura en su fila', async () => {
    server.use(openingBalancesPage([fakeOpeningBalance()]))
    renderCorteInicial()
    const row = rowOf(await screen.findByText('Filtro de aceite XYZ'))
    expect(within(row).getByText('PRO-0001')).toBeInTheDocument()
    expect(within(row).getByText('6 UND')).toBeInTheDocument()
    expect(within(row).getByText('Conteo físico del arranque')).toBeInTheDocument()
    expect(within(row).getByText('Admin TMS')).toBeInTheDocument()
  })

  // Un `||` o un guard de falsy en el formateo mostraría vacío o un guion: el 0 es
  // un dato, no la ausencia de dato.
  it('muestra la apertura en 0 como "0 UND"', async () => {
    server.use(
      openingBalancesPage([
        fakeOpeningBalance({ id: 9, quantity: 0, observations: null }),
      ]),
    )
    renderCorteInicial()
    const row = rowOf(await screen.findByText('Filtro de aceite XYZ'))
    expect(within(row).getByText('0 UND')).toBeInTheDocument()
    expect(within(row).getByText('Sin observaciones')).toBeInTheDocument()
  })

  it('muestra el estado vacío sin deshabilitar el formulario', async () => {
    server.use(openingBalancesEmpty())
    renderCorteInicial()
    expect(await screen.findByText(/aún no hay cortes iniciales/i)).toBeInTheDocument()
    expect(screen.getByText(/registra el primero con el formulario de arriba/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad inicial')).toBeEnabled()
  })

  // A quien no ve el formulario no se le puede pedir que lo use: es el caso real de
  // consultar el listado antes de que el admin haya cargado nada.
  it('el estado vacío no menciona el formulario cuando el rol no puede registrar', async () => {
    server.use(openingBalancesEmpty())
    renderCorteInicial('warehouse_keeper')
    expect(await screen.findByText(/aún no hay cortes iniciales/i)).toBeInTheDocument()
    expect(screen.getByText(/los registra un administrador/i)).toBeInTheDocument()
    expect(screen.queryByText(/formulario de arriba/i)).not.toBeInTheDocument()
  })

  it('el estado vacío del listado filtrado tampoco menciona el formulario sin permiso', async () => {
    const user = userEvent.setup()
    server.use(openingBalancesEmpty())
    renderCorteInicial('warehouse_keeper')
    await screen.findByText(/aún no hay cortes iniciales/i)

    await selectFilterProduct(user)
    expect(await screen.findByText(/los registra un administrador/i)).toBeInTheDocument()
    expect(screen.queryByText(/formulario de arriba/i)).not.toBeInTheDocument()
  })

  it('muestra el error del listado sin tumbar el formulario', async () => {
    server.use(openingBalancesError(500, { detail: 'Fallo al listar' }))
    renderCorteInicial()
    expect(await screen.findByText('Fallo al listar')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /registrar corte inicial/i })).toBeEnabled()
  })

  // La apertura es inmutable: el contrato no tiene GET por id, PUT ni DELETE.
  it('no ofrece editar, anular ni abrir un detalle de la apertura', async () => {
    renderCorteInicial()
    await screen.findByText('Filtro de aceite XYZ')
    expect(screen.queryByRole('button', { name: /editar/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /anular/i })).not.toBeInTheDocument()
    expect(rowOf(screen.getByText('Filtro de aceite XYZ'))).not.toHaveAttribute('role', 'button')
  })

  it('pagina el listado', async () => {
    const user = userEvent.setup()
    server.use(openingBalancesPagedByParam(25, 10))
    renderCorteInicial()
    await screen.findByText('Producto de la página 0')
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText('Producto de la página 1')).toBeInTheDocument()
  })

  // ----- Rol: registrar es solo de admin -----
  it('un rol del módulo que no es admin ve el listado pero no el formulario', async () => {
    renderCorteInicial('warehouse_keeper')
    expect(await screen.findByText('Filtro de aceite XYZ')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /registrar corte inicial/i }),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad inicial')).not.toBeInTheDocument()
    // Un hueco sin explicación se leería como pantalla rota.
    expect(screen.getByText(/lo registra un administrador/i)).toBeInTheDocument()
    // El filtro del listado sigue disponible: consultar no se restringió.
    expect(screen.getByLabelText('Filtrar por producto')).toBeInTheDocument()
  })

  it('admin ve el formulario de registro', async () => {
    renderCorteInicial('admin')
    await screen.findByText('Filtro de aceite XYZ')
    expect(screen.getByRole('button', { name: /registrar corte inicial/i })).toBeInTheDocument()
    expect(screen.queryByText(/lo registra un administrador/i)).not.toBeInTheDocument()
  })

  // ----- Combobox de producto -----
  it('no busca productos con menos de 3 caracteres', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderCorteInicial()
    await user.type(screen.getByLabelText('Producto'), 'fi')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    expect(sink.calls).toEqual([])
  })

  it('busca productos activos con 3 o más caracteres', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(sink, [fakeProduct()]))
    renderCorteInicial()
    await user.type(screen.getByLabelText('Producto'), 'fil')
    await waitFor(() => expect(sink.params?.get('q')).toBe('fil'))
    expect(sink.params?.get('isActive')).toBe('true')
  })

  // El caso típico del corte inicial es incorporar un producto que ya existía
  // físicamente y nunca se cargó: darlo de alta sin salir del form es parte del
  // flujo, igual que en la entrada.
  it('ofrece dar de alta el producto al vuelo cuando la búsqueda no encuentra nada', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductsEmpty())
    renderCorteInicial()
    await user.type(screen.getByLabelText('Producto'), 'perno nuevo')
    expect(await screen.findByText(/no se encontraron productos/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /nuevo producto/i })).toBeInTheDocument()
  })

  it('el producto creado al vuelo queda elegido en el form', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductsEmpty())
    renderCorteInicial()
    await user.type(screen.getByLabelText('Producto'), 'perno nuevo')
    await user.click(await screen.findByRole('button', { name: /nuevo producto/i }))

    const dialog = await screen.findByRole('dialog')
    // El modal precarga el nombre con lo tipeado en el combobox.
    expect(within(dialog).getByLabelText(/nombre/i)).toHaveValue('perno nuevo')
  })

  it('avisa cuando la búsqueda de productos falla, sin romper el resto del form', async () => {
    const user = userEvent.setup()
    server.use(warehouseProductsError(500))
    renderCorteInicial()
    await user.type(screen.getByLabelText('Producto'), 'fil')
    expect(await screen.findByText(/no se pudieron buscar productos/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad inicial')).toBeEnabled()
  })

  // La unidad se anuncia como ayuda del campo cantidad (no en un texto suelto): así
  // el lector de pantalla la oye al enfocar, que es cuando importa no confundir
  // galones con unidades.
  it('anuncia la unidad del producto elegido como ayuda de la cantidad', async () => {
    const user = userEvent.setup()
    renderCorteInicial()
    await selectProduct(user)
    const hint = await screen.findByText(/Unidad: UND/)
    expect(screen.getByLabelText('Cantidad inicial')).toHaveAttribute(
      'aria-describedby',
      expect.stringContaining(hint.id),
    )
  })

  // ----- Validación por UI -----
  it('exige el producto cuando no se eligió', async () => {
    const user = userEvent.setup()
    const sink: BodyCaptureSink = {}
    server.use(openingBalanceCapture(sink))
    renderCorteInicial()
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    expect(await screen.findByText('Selecciona el producto')).toBeInTheDocument()
    expect(sink.calls).toEqual([])
  })

  it('exige la cantidad', async () => {
    const user = userEvent.setup()
    const sink: BodyCaptureSink = {}
    server.use(openingBalanceCapture(sink))
    renderCorteInicial()
    await selectProduct(user)
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    expect(await screen.findByText('Indica la cantidad')).toBeInTheDocument()
    expect(sink.calls).toEqual([])
  })

  // El 0 llega al backend como 0: deja constancia del conteo físico.
  it('acepta la cantidad en 0 y la envía al backend', async () => {
    const user = userEvent.setup()
    const sink: BodyCaptureSink = {}
    server.use(openingBalanceCapture(sink))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '0')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.quantity).toBe(0)
  })

  // `TextField` bloquea la tecla del signo en los inputs numéricos, así que tipear
  // "-2" deja un 2: el negativo no se puede escribir ni en el navegador ni acá. Lo
  // que se pinea es que lo que SÍ viaja es el número positivo, no un NaN ni un -2.
  // La regla en sí la pinea `opening-balance.schema.test.ts`, que le pasa un -1 al
  // schema directamente.
  it('no deja escribir una cantidad negativa', async () => {
    const user = userEvent.setup()
    const sink: BodyCaptureSink = {}
    server.use(openingBalanceCapture(sink))
    renderCorteInicial()
    await selectProduct(user)
    const quantity = screen.getByLabelText('Cantidad inicial')
    expect(quantity).toHaveAttribute('min', '0')

    await user.type(quantity, '-2')
    expect(quantity).toHaveValue(2)
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.quantity).toBe(2)
  })

  it('el error de un campo desaparece cuando se corrige', async () => {
    const user = userEvent.setup()
    renderCorteInicial()
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    expect(await screen.findByText('Selecciona el producto')).toBeInTheDocument()
    await selectProduct(user)
    await waitFor(() =>
      expect(screen.queryByText('Selecciona el producto')).not.toBeInTheDocument(),
    )
  })

  // ----- Integración con la API -----
  it('envía el payload del contrato al backend', async () => {
    const user = userEvent.setup()
    const sink: BodyCaptureSink = {}
    server.use(openingBalanceCapture(sink))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.type(screen.getByLabelText(/observaciones/i), 'Conteo físico del 01/06')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body).toEqual({
      productId: 1,
      quantity: 6,
      observations: 'Conteo físico del 01/06',
    })
  })

  it('envía null en observaciones vacías', async () => {
    const user = userEvent.setup()
    const sink: BodyCaptureSink = {}
    server.use(openingBalanceCapture(sink))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    await waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.observations).toBeNull()
  })

  it('al registrar avisa con toast y limpia el formulario para el siguiente producto', async () => {
    const user = userEvent.setup()
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.success).toHaveBeenCalledWith(
        expect.stringContaining('Filtro de aceite XYZ'),
      ),
    )
    expect(screen.getByLabelText('Cantidad inicial')).toHaveValue(null)
    expect(screen.getByLabelText('Producto')).toHaveValue('')
  })

  it('la apertura registrada aparece en el listado sin recargar la página', async () => {
    const user = userEvent.setup()
    server.use(
      openingBalancesSequence([
        pageOfOpeningBalances([]),
        pageOfOpeningBalances([fakeOpeningBalance({ id: 42 })]),
      ]),
    )
    renderCorteInicial()
    await screen.findByText(/aún no hay cortes iniciales/i)

    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    // El nombre del producto también está en el combobox mientras se elige, así que
    // se busca dentro de la tabla.
    const table = await screen.findByRole('table')
    expect(await within(table).findByText('Filtro de aceite XYZ')).toBeInTheDocument()
  })

  it('registrar invalida también las existencias', async () => {
    const user = userEvent.setup()
    const productsSink: ProductsCaptureSink = {}
    server.use(warehouseProductsCapture(productsSink, [fakeProduct()]))
    renderCorteInicial()
    await selectProduct(user)
    const callsBeforeSubmit = productsSink.calls?.length ?? 0

    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    // La apertura es el primer movimiento del kardex y fija el stock de arranque:
    // la rama de productos tiene que refrescarse.
    await waitFor(() =>
      expect(productsSink.calls?.length ?? 0).toBeGreaterThan(callsBeforeSubmit),
    )
  })

  // Un doble click daría un 409 WH-009 espurio sobre el corte que acaba de entrar.
  it('no permite un segundo envío mientras la mutación está en vuelo', async () => {
    const user = userEvent.setup()
    const sink: BodyCaptureSink = {}
    server.use(openingBalanceSlow(sink, 60))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')

    const submit = screen.getByRole('button', { name: /registrar corte inicial/i })
    await user.click(submit)
    expect(await screen.findByRole('button', { name: /registrando…/i })).toBeDisabled()
    await waitFor(() => expect(sink.calls).toHaveLength(1))
  })

  it('WH-009: muestra el detalle del backend cuando el producto ya tiene corte inicial', async () => {
    const user = userEvent.setup()
    const detail = "El producto 'Filtro de aceite XYZ' ya tiene corte inicial registrado"
    server.use(openingBalanceError(409, { code: 'WH-009', detail }))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    expect(await screen.findByText(detail)).toBeInTheDocument()
    // El form no se limpia: se corrige el producto y se reintenta.
    expect(screen.getByLabelText('Cantidad inicial')).toHaveValue(6)
  })

  it('WH-011: muestra el detalle del backend y ofrece ver el kardex del producto', async () => {
    const user = userEvent.setup()
    const detail =
      "El producto 'Filtro de aceite XYZ' ya tiene movimientos; el corte inicial solo aplica a productos sin historial"
    server.use(openingBalanceError(409, { code: 'WH-011', detail }))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    expect(await screen.findByText(detail)).toBeInTheDocument()
    const kardexLink = screen.getByRole('link', { name: /ver el kardex de/i })
    expect(kardexLink).toHaveAttribute('href', '/cotizaciones/almacen/productos/1')
  })

  it('WH-004: muestra el detalle del backend cuando el producto no existe o está inactivo', async () => {
    const user = userEvent.setup()
    const detail = 'El producto seleccionado no existe o está inactivo'
    server.use(openingBalanceError(400, { code: 'WH-004', detail }))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    expect(await screen.findByText(detail)).toBeInTheDocument()
    // Es un error de producto, no de cantidad: no ofrece el link al kardex (WH-011).
    expect(screen.queryByRole('link', { name: /ver el kardex de/i })).not.toBeInTheDocument()
  })

  it('cae al mensaje de respaldo cuando el error no trae detalle', async () => {
    const user = userEvent.setup()
    server.use(openingBalanceError(500, { detail: undefined }))
    renderCorteInicial()
    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    const { toast } = await import('sonner')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        'No se pudo registrar el corte inicial. Intenta de nuevo.',
      ),
    )
  })

  it('filtra el listado por producto sin tocar el formulario de alta', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(openingBalancesCapture(sink))
    renderCorteInicial()
    await screen.findByText('Filtro de aceite XYZ')

    await selectFilterProduct(user)
    await waitFor(() => expect(sink.params?.get('productId')).toBe('1'))
    // El combobox del alta es un control independiente: el filtro no lo completa.
    expect(screen.getByLabelText('Producto')).toHaveValue('')
  })

  // Si el filtro sobreviviera al alta, el registro recién hecho quedaría escondido
  // detrás del producto filtrado y parecería que no entró.
  it('al registrar libera el filtro para que la apertura nueva se vea', async () => {
    const user = userEvent.setup()
    const sink: ProductsCaptureSink = {}
    server.use(openingBalancesCapture(sink))
    renderCorteInicial()
    await screen.findByText('Filtro de aceite XYZ')

    await selectFilterProduct(user)
    await waitFor(() => expect(sink.params?.get('productId')).toBe('1'))

    await selectProduct(user)
    await user.type(screen.getByLabelText('Cantidad inicial'), '6')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))

    await waitFor(() => expect(sink.params?.get('productId')).toBeNull())
    expect(screen.getByLabelText('Filtrar por producto')).toHaveValue('')
  })

  // ----- Accesibilidad -----
  it('presenta la pantalla con un único h1', async () => {
    renderCorteInicial()
    await screen.findByText('Filtro de aceite XYZ')
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent('Corte inicial')
  })

  it('no tiene violaciones de accesibilidad con el listado cargado', async () => {
    const { container } = renderCorteInicial()
    await screen.findByText('Filtro de aceite XYZ')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no tiene violaciones de accesibilidad con errores de validación visibles', async () => {
    const user = userEvent.setup()
    const { container } = renderCorteInicial()
    await screen.findByText('Filtro de aceite XYZ')
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    await screen.findByText('Selecciona el producto')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('asocia el error de validación con su campo', async () => {
    const user = userEvent.setup()
    renderCorteInicial()
    await selectProduct(user)
    await user.click(screen.getByRole('button', { name: /registrar corte inicial/i }))
    await screen.findByText('Indica la cantidad')
    expect(screen.getByLabelText('Cantidad inicial')).toHaveAttribute('aria-invalid', 'true')
  })
})
