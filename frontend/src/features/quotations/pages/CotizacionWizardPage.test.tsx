import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CotizacionWizardPage } from './CotizacionWizardPage'
import { server } from '../../../test/mocks/server'
import { catalogsSlow, configOk, currenciesError } from '../../../test/mocks/handlers/catalogs'
import {
  clientsCapture,
  clientsSearch,
  createClientConflict,
  createClientOk,
  fakeClient,
} from '../../../test/mocks/handlers/clients'
import { cargoTypesSearch, createCargoTypeOk, fakeCargoType } from '../../../test/mocks/handlers/cargotypes'
import {
  createQuotationError,
  createQuotationSlow,
  createQuotationSuccess,
  getQuotationResponse,
} from '../../../test/mocks/handlers/quotations'
import type { QuotationRequest } from '../../../api'

/** Stub del detalle: muestra el id de la URL (para verificar la navegación post-guardado). */
function QuotationDetailStub() {
  const { id } = useParams()
  return <div>DETALLE COTIZACION {id}</div>
}

function renderWizard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/cotizaciones/nueva']}>
        <Routes>
          <Route path="/cotizaciones" element={<div>LISTADO COTIZACIONES</div>} />
          <Route path="/cotizaciones/nueva" element={<CotizacionWizardPage />} />
          <Route path="/cotizaciones/:id" element={<QuotationDetailStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Espera a que el form del step 1 esté montado (tras cargar catálogos). */
function waitForForm() {
  return screen.findByText('Tipo de cotización')
}

/** Busca y selecciona el cliente ACME (requiere `clientsSearch([fakeClient()])`). */
async function selectAcme(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Cliente'), 'acme')
  await user.click(await screen.findByText('ACME S.A.C.'))
}

describe('CotizacionWizardPage', () => {
  // ----- Render -----
  it('monta el wizard con el stepper de 5 pasos y el Step 1', async () => {
    renderWizard()
    await waitForForm()
    expect(screen.getByText('Información General')).toBeInTheDocument()
    expect(screen.getByText('Ítems')).toBeInTheDocument()
    expect(screen.getByText('Condiciones')).toBeInTheDocument()
    expect(screen.getByText('Resumen')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /transporte/i })).toBeInTheDocument()
  })

  it('precarga los defaults comerciales (moneda PEN, validez del config)', async () => {
    renderWizard()
    await waitForForm()
    expect((screen.getByLabelText('Moneda') as HTMLSelectElement).value).toBe('2') // PEN
    expect((screen.getByLabelText(/validez/i) as HTMLInputElement).value).toBe('15')
  })

  it('muestra el spinner mientras cargan los catálogos', async () => {
    server.use(...catalogsSlow(40))
    renderWizard()
    expect(screen.getByRole('status')).toBeInTheDocument()
    await waitForForm()
  })

  // ----- Tipo de cotización -----
  it('TRANSPORTE (default) muestra los campos de ruta', async () => {
    renderWizard()
    await waitForForm()
    expect(screen.getByLabelText('Origen')).toBeInTheDocument()
    expect(screen.getByLabelText('Destino')).toBeInTheDocument()
  })

  it('ALQUILER oculta los campos de ruta', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    expect(screen.queryByLabelText('Origen')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Destino')).not.toBeInTheDocument()
  })

  // ----- Combobox cliente -----
  it('no busca clientes con menos de 3 caracteres y muestra el hint', async () => {
    const user = userEvent.setup()
    const requests: string[] = []
    const onRequest = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', onRequest)
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'ac')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 450))
    expect(requests.some((url) => url.includes('/clients?') && url.includes('q='))).toBe(false)
    server.events.removeListener('request:start', onRequest)
  })

  it('busca clientes con 3 o más caracteres (param q)', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'acme')
    await screen.findByText('ACME S.A.C.')
    expect(sink.params?.get('q')).toBe('acme')
  })

  it('al seleccionar un cliente precarga el contacto', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ contactName: 'Juan Pérez', phone: '987654321' })]))
    renderWizard()
    await waitForForm()
    await selectAcme(user)
    expect((screen.getByLabelText(/persona de contacto/i) as HTMLInputElement).value).toBe('Juan Pérez')
    expect((screen.getByLabelText(/teléfono de contacto/i) as HTMLInputElement).value).toBe('987654321')
  })

  it('quitar el cliente limpia el contacto precargado', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ contactName: 'Juan Pérez', phone: '987654321' })]))
    renderWizard()
    await waitForForm()
    await selectAcme(user)
    expect((screen.getByLabelText(/persona de contacto/i) as HTMLInputElement).value).toBe('Juan Pérez')
    await user.click(screen.getByRole('button', { name: /quitar selección/i }))
    expect((screen.getByLabelText(/persona de contacto/i) as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText(/teléfono de contacto/i) as HTMLInputElement).value).toBe('')
  })

  it('al seleccionar un cliente muestra su RUC en un campo de solo lectura', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ ruc: '20123456789' })]))
    renderWizard()
    await waitForForm()
    await selectAcme(user)
    const ruc = screen.getByLabelText('RUC del cliente seleccionado') as HTMLInputElement
    expect(ruc.value).toBe('20123456789')
    expect(ruc).toHaveAttribute('readonly')
  })

  // ----- Crear cliente al vuelo -----
  it('el botón "Nuevo cliente" abre el modal', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'nuevo')
    await user.click(await screen.findByText('Nuevo cliente'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('crear un cliente válido lo selecciona y cierra el modal', async () => {
    const user = userEvent.setup()
    server.use(createClientOk(fakeClient({ id: 99, name: 'NUEVA SAC', ruc: '20111111111', contactName: 'Ana' })))
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'nueva')
    await user.click(await screen.findByText('Nuevo cliente'))
    await user.type(screen.getByLabelText('Razón social'), 'NUEVA SAC')
    await user.type(within(screen.getByRole('dialog')).getByLabelText('RUC'), '20111111111')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))
    expect(await screen.findByText('NUEVA SAC')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('RUC duplicado (409) muestra el Problem.detail en el modal', async () => {
    const user = userEvent.setup()
    server.use(createClientConflict('El RUC ya existe en otro cliente.'))
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'dup')
    await user.click(await screen.findByText('Nuevo cliente'))
    await user.type(screen.getByLabelText('Razón social'), 'DUP SAC')
    await user.type(within(screen.getByRole('dialog')).getByLabelText('RUC'), '20123456789')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))
    expect(await screen.findByText('El RUC ya existe en otro cliente.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  // ----- Validación Step 1 -----
  it('"Siguiente" sin cliente NO bloquea: avanza y marca el Step 1 con alerta', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    // No bloqueante: avanza al Step 2 igual.
    expect(await screen.findByText(/ítems de la cotización/i)).toBeInTheDocument()
    // El Step 1 quedó marcado: al volver por el stepper, el error sigue visible.
    await user.click(screen.getByRole('button', { name: /información general/i }))
    expect(await screen.findByText(/selecciona un cliente/i)).toBeInTheDocument()
  })

  it('teléfono de contacto con formato inválido muestra error', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText(/teléfono de contacto/i), '123')
    await user.tab()
    expect(await screen.findByText(/9 dígitos/i)).toBeInTheDocument()
  })

  it('origen es obligatorio en TRANSPORTE (se marca al intentar avanzar)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    await screen.findByText(/ítems de la cotización/i) // avanzó (no bloqueante)
    await user.click(screen.getByRole('button', { name: /información general/i }))
    expect(await screen.findByText(/origen es obligatorio/i)).toBeInTheDocument()
  })

  // ----- Navegación -----
  it('"Siguiente" con el Step 1 válido (alquiler) avanza al Step 2', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ contactName: 'Juan Pérez', phone: '987654321' })]))
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    await selectAcme(user)
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText(/ítems de la cotización/i)).toBeInTheDocument()
  })

  it('"Atrás" vuelve al Step 1 conservando los datos', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient()]))
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    await selectAcme(user)
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    await screen.findByText(/ítems de la cotización/i)
    await user.click(screen.getByRole('button', { name: /atrás/i }))
    expect(await screen.findByText('Tipo de cotización')).toBeInTheDocument()
    expect(screen.getByText('ACME S.A.C.')).toBeInTheDocument() // cliente conservado
  })

  // ----- Step 2: Ítems -----
  /** Navega al Step 2 (no bloqueante, no requiere completar el Step 1). */
  async function goToItems(user: ReturnType<typeof userEvent.setup>) {
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    await screen.findByText(/ítems de la cotización/i)
  }

  /** Agrega un ítem y elige un tipo de SERVICIO (revela carga + peso + dimensiones). */
  async function addServicioItem(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '1') // SCB · SERVICIO
  }

  it('agrega un ítem y muestra el form del ítem', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    expect(screen.getByText(/agrega al menos un ítem/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    expect(screen.getByText('Ítem 1')).toBeInTheDocument()
    expect(screen.getByLabelText('Tipo de servicio')).toBeInTheDocument()
  })

  it('los campos del ítem aparecen recién al elegir el tipo de servicio', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    // Sin tipo: solo el select + un hint; nada de cantidad/precio.
    expect(screen.getByText(/elige un tipo de servicio/i)).toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Precio unitario')).not.toBeInTheDocument()
    // Al elegir un tipo, aparecen los demás campos.
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '1')
    expect(screen.getByLabelText('Cantidad')).toBeInTheDocument()
    expect(screen.getByLabelText('Precio unitario')).toBeInTheDocument()
  })

  it('TRANSPORTE muestra Servicios + Complementarios + Integral y excluye ALQUILER', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    const select = screen.getByLabelText('Tipo de servicio')
    expect(within(select).getByRole('option', { name: 'Transporte de carga general' })).toBeInTheDocument() // SERVICIO
    expect(within(select).getByRole('option', { name: 'Escolta armada' })).toBeInTheDocument() // COMPLEMENTARIO
    expect(within(select).queryByRole('option', { name: /alquiler de camión/i })).not.toBeInTheDocument() // ALQUILER fuera
    // El Servicio Integral ya es seleccionable como ítem #1.
    const integral = within(select).getByRole('option', { name: /servicio integral/i })
    expect(integral).toBeInTheDocument()
    expect(integral).toBeEnabled()
  })

  it('ALQUILER muestra solo tipos de alquiler en el select', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    await screen.findByText(/ítems de la cotización/i)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    const select = screen.getByLabelText('Tipo de servicio')
    expect(within(select).getByRole('option', { name: 'Alquiler de camión' })).toBeInTheDocument()
    expect(within(select).queryByRole('option', { name: 'Transporte de carga general' })).not.toBeInTheDocument()
    expect(within(select).queryByRole('option', { name: /servicio integral/i })).not.toBeInTheDocument()
  })

  it('un ítem SERVICIO pide tipo de carga y peso', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await addServicioItem(user)
    // Al elegir un tipo SERVICIO se revelan carga + peso.
    expect(screen.getByLabelText('Tipo de carga')).toBeInTheDocument()
    expect(screen.getByLabelText('Peso (kg)')).toBeInTheDocument()
  })

  it('al elegir el tipo de servicio no muestra el error de peso prematuramente', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '1') // SERVICIO
    // El peso está vacío pero no se tocó → el error no debe aparecer todavía (onTouched).
    expect(screen.queryByText(/el peso debe ser mayor a 0/i)).not.toBeInTheDocument()
  })

  it('al elegir un tipo de carga precarga su peso y dimensiones estándar', async () => {
    const user = userEvent.setup()
    server.use(
      cargoTypesSearch([
        fakeCargoType({
          id: 7,
          name: 'EXCAVADORA 330',
          standardWeight: 33000,
          standardLength: 11,
          standardWidth: 3.2,
          standardHeight: 3.5,
        }),
      ]),
    )
    renderWizard()
    await goToItems(user)
    await addServicioItem(user)
    await user.type(screen.getByLabelText('Tipo de carga'), 'exca')
    await user.click(await screen.findByText('EXCAVADORA 330'))
    expect((screen.getByLabelText('Peso (kg)') as HTMLInputElement).value).toBe('33000')
    expect((screen.getByLabelText('Largo (m)') as HTMLInputElement).value).toBe('11')
  })

  it('al quitar el tipo de carga limpia el peso y las dimensiones', async () => {
    const user = userEvent.setup()
    server.use(
      cargoTypesSearch([fakeCargoType({ id: 7, name: 'EXCAVADORA 330', standardWeight: 33000, standardLength: 11 })]),
    )
    renderWizard()
    await goToItems(user)
    await addServicioItem(user)
    await user.type(screen.getByLabelText('Tipo de carga'), 'exca')
    await user.click(await screen.findByText('EXCAVADORA 330'))
    expect((screen.getByLabelText('Peso (kg)') as HTMLInputElement).value).toBe('33000')
    await user.click(screen.getByRole('button', { name: /quitar selección/i }))
    expect((screen.getByLabelText('Peso (kg)') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('Largo (m)') as HTMLInputElement).value).toBe('')
  })

  it('calcula el total del ítem con IGV', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    // Escolta (COMPLEMENTARIO): sin carga/peso, simplifica el caso.
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2')
    const qty = screen.getByLabelText('Cantidad')
    await user.clear(qty)
    await user.type(qty, '2')
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '100')
    // 2 × 100 × 1.18 = 236
    expect((screen.getByLabelText(/total del ítem 1/i) as HTMLInputElement).value).toMatch(/236/)
  })

  it('permite tipear el total y calcula el precio unitario a la inversa', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // COMPLEMENTARIO
    // Cantidad 1, IGV 18%: total 1180 (incl. IGV) → precio unitario = 1180 / 1.18 = 1000.
    await user.type(screen.getByLabelText(/total del ítem 1/i), '1180')
    await user.tab() // blur → commit del total
    expect((screen.getByLabelText('Precio unitario') as HTMLInputElement).value).toBe('1000')
  })

  it('respeta el máximo de ítems del config', async () => {
    const user = userEvent.setup()
    server.use(configOk({ maxRootItems: 1 }))
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    expect(screen.getByText(/máximo 1 ítems · 1\/1/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /agregar ítem/i })).toBeDisabled()
  })

  it('crea un tipo de carga al vuelo y lo selecciona', async () => {
    const user = userEvent.setup()
    server.use(createCargoTypeOk(fakeCargoType({ id: 50, name: 'CONTENEDOR 40' })))
    renderWizard()
    await goToItems(user)
    await addServicioItem(user)
    await user.type(screen.getByLabelText('Tipo de carga'), 'cont')
    await user.click(await screen.findByText('Nuevo tipo de carga'))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText('Nombre'), 'CONTENEDOR 40')
    await user.type(within(dialog).getByLabelText(/peso estándar/i), '5000')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))
    expect(await screen.findByText('CONTENEDOR 40')).toBeInTheDocument()
  })

  it('el modal de tipo de carga permite cargar descripción y dimensiones', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await addServicioItem(user)
    await user.type(screen.getByLabelText('Tipo de carga'), 'nuevo')
    await user.click(await screen.findByText('Nuevo tipo de carga'))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText('Nombre')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Descripción (opcional)')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/peso estándar/i)).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/largo estándar/i)).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/ancho estándar/i)).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/alto estándar/i)).toBeInTheDocument()
  })

  it('permite saltar entre pasos por el stepper (no bloqueante)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /stand-by/i }))
    expect(await screen.findByRole('heading', { name: /stand-by por ítem/i })).toBeInTheDocument()
  })

  it('vacía los ítems al cambiar el tipo de cotización', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    expect(screen.getByText('Ítem 1')).toBeInTheDocument()
    // Volver al Step 1 y cambiar TRANSPORTE → ALQUILER.
    await user.click(screen.getByRole('button', { name: /atrás/i }))
    await screen.findByText('Tipo de cotización')
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    // Avanzar de nuevo al Step 2: los ítems se vaciaron (sus tipos dependían del tipo).
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText(/agrega al menos un ítem/i)).toBeInTheDocument()
    expect(screen.queryByText('Ítem 1')).not.toBeInTheDocument()
  })

  it('al agregar un ítem colapsa el anterior y abre solo el nuevo', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    expect(screen.getByLabelText('Tipo de servicio')).toBeInTheDocument() // ítem 1 abierto
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    // 2 ítems en la lista (headers visibles), pero solo el nuevo abierto → 1 form visible.
    expect(screen.getByText('Ítem 1')).toBeInTheDocument()
    expect(screen.getByText('Ítem 2')).toBeInTheDocument()
    expect(screen.getAllByLabelText('Tipo de servicio')).toHaveLength(1)
  })

  it('al hacer click en un ítem colapsado lo expande (varios abiertos)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    expect(screen.getAllByLabelText('Tipo de servicio')).toHaveLength(1) // solo el ítem 2
    // El ítem 1 quedó colapsado: su header es el único toggle con aria-expanded=false.
    await user.click(screen.getAllByRole('button', { expanded: false })[0])
    expect(screen.getAllByLabelText('Tipo de servicio')).toHaveLength(2) // ambos abiertos
  })

  it('el indicador de estado se muestra solo cuando el ítem está colapsado', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    // Expandido (recién agregado) → sin indicador.
    expect(screen.queryByText('Faltan datos')).not.toBeInTheDocument()
    // Completar el ítem (COMPLEMENTARIO no pide carga/peso).
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2')
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '100')
    // Colapsar → muestra "Completo".
    await user.click(screen.getAllByRole('button', { expanded: true })[0])
    expect(await screen.findByText('Completo')).toBeInTheDocument()
  })

  it('el footer desglosa subtotal, IGV y total', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2')
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '1000')
    // Desglose en el footer: Subtotal + IGV (18%) (el ítem solo tiene "Total" e "IGV (%)").
    expect(screen.getByText('Subtotal')).toBeInTheDocument()
    expect(screen.getByText(/IGV \(18%\)/)).toBeInTheDocument()
  })

  it('"Cancelar" navega al listado', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(await screen.findByText('LISTADO COTIZACIONES')).toBeInTheDocument()
  })

  // ----- Catálogos -----
  it('toma la validez por defecto del config', async () => {
    server.use(configOk({ defaultValidityDays: 20 }))
    renderWizard()
    await waitForForm()
    expect((screen.getByLabelText(/validez/i) as HTMLInputElement).value).toBe('20')
  })

  it('si fallan los catálogos muestra error y permite reintentar', async () => {
    server.use(currenciesError(500))
    renderWizard()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reintentar/i })).toBeInTheDocument()
  })

  // ----- A11y -----
  it('los campos del Step 1 tienen labels accesibles', async () => {
    renderWizard()
    await waitForForm()
    expect(screen.getByLabelText('Cliente')).toBeInTheDocument()
    expect(screen.getByLabelText(/persona de contacto/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Moneda')).toBeInTheDocument()
    expect(screen.getByLabelText(/validez/i)).toBeInTheDocument()
  })

  // ----- Step 2: Servicio Integral -----
  /** Agrega el ítem #1 y lo convierte en Servicio Integral (id 4 = INT). */
  async function activarIntegral(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '4') // INT · INTEGRAL
  }

  /** within() del último componente agregado (mini-card `integral-child`). */
  function lastChild() {
    const cards = screen.getAllByTestId('integral-child')
    return within(cards[cards.length - 1])
  }

  /** Agrega un componente y le elige el tipo (id '1' = SERVICIO, '2' = COMPLEMENTARIO). */
  async function addComponente(user: ReturnType<typeof userEvent.setup>, tipoId: string) {
    await user.click(screen.getByRole('button', { name: /agregar componente/i }))
    await user.selectOptions(lastChild().getByLabelText(/tipo de servicio del componente/i), tipoId)
  }

  it('el Servicio Integral NO es seleccionable en el ítem #2', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // CES en el #1
    await user.click(screen.getByRole('button', { name: /agregar ítem/i })) // #2 (queda abierto)
    const integral = within(screen.getByLabelText('Tipo de servicio')).getByRole('option', {
      name: /servicio integral/i,
    })
    expect(integral).toBeDisabled()
  })

  it('elegir Servicio Integral en el ítem #1 activa el modo (banner + sección de componentes)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    expect(await screen.findByText(/modo integral activado/i)).toBeInTheDocument()
    expect(screen.getByText(/componentes del servicio integral/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /agregar componente/i })).toBeInTheDocument()
  })

  it('el ítem Integral no pide carga ni peso propios (los llevan los componentes)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    expect(screen.queryByLabelText('Tipo de carga')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Peso (kg)')).not.toBeInTheDocument()
  })

  it('agregar componentes incrementa el contador', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    expect(screen.getByText(/0 agregados/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /agregar componente/i }))
    expect(screen.getByText(/· 1 agregado/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /agregar componente/i }))
    expect(screen.getByText(/2 agregados/i)).toBeInTheDocument()
  })

  it('quita un componente', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await user.click(screen.getByRole('button', { name: /agregar componente/i }))
    await user.click(screen.getByRole('button', { name: /agregar componente/i }))
    expect(screen.getAllByTestId('integral-child')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: /eliminar componente 1/i }))
    expect(screen.getAllByTestId('integral-child')).toHaveLength(1)
  })

  it('un componente de transporte revela carga y peso; el complementario no', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '1') // SERVICIO
    expect(lastChild().getByLabelText('Tipo de carga')).toBeInTheDocument()
    expect(lastChild().getByLabelText(/peso del componente/i)).toBeInTheDocument()
    await addComponente(user, '2') // COMPLEMENTARIO
    expect(lastChild().queryByLabelText('Tipo de carga')).not.toBeInTheDocument()
    expect(lastChild().queryByLabelText(/peso del componente/i)).not.toBeInTheDocument()
  })

  it('el total de referencia del componente = precio ref × cantidad', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2') // COMPLEMENTARIO
    const child = lastChild()
    await user.type(child.getByLabelText(/precio de referencia del componente 1/i), '8500')
    expect((child.getByLabelText(/total del componente 1/i) as HTMLInputElement).value).toMatch(/8[.,]?500/)
  })

  it('el total del Integral usa el precio del padre (manual), no la suma de componentes', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2')
    await user.type(lastChild().getByLabelText(/precio de referencia del componente 1/i), '8500')
    const price = screen.getByLabelText('Precio unitario') // el del padre
    await user.clear(price)
    await user.type(price, '12000')
    // Total del padre = 12000 × 1 × 1.18 = 14160 (no 12000+8500).
    expect((screen.getByLabelText(/total del ítem 1/i) as HTMLInputElement).value).toMatch(/14[.,]?160/)
  })

  it('el footer suma solo el precio del padre Integral, no el de los componentes', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2')
    await user.type(lastChild().getByLabelText(/precio de referencia del componente 1/i), '8500')
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '12000')
    // Subtotal del footer = 12000 (el 8500 del componente NO suma).
    expect(screen.getByText('Subtotal').closest('div')).toHaveTextContent(/12[.,]?000/)
  })

  it('la guía pide mínimo 2 componentes en vivo (con 1 solo)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2') // 1 componente → reactivo, sin avanzar
    // "requiere mínimo 2…" es la guía (el contador dice "Mínimo 2 componentes · N agregado").
    expect(screen.getByText(/requiere mínimo 2 componentes/i)).toBeInTheDocument()
  })

  it('la guía pide un componente de transporte cuando faltan', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2') // COMPLEMENTARIO
    await addComponente(user, '2') // COMPLEMENTARIO (faltan transporte)
    expect(screen.getByText(/al menos un componente de transporte/i)).toBeInTheDocument()
  })

  it('la guía pide un componente complementario cuando faltan', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '1') // SERVICIO
    await addComponente(user, '1') // SERVICIO (falta COMPLEMENTARIO)
    expect(screen.getByText(/al menos un componente complementario/i)).toBeInTheDocument()
  })

  it('la guía se mantiene "mínimo 2" aunque el único componente sea de transporte', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '1') // SERVICIO (transporte), pero sigue siendo 1 solo
    // No debe desaparecer: aún falta llegar a 2 (el bug que reportó el usuario).
    expect(screen.getByText(/requiere mínimo 2 componentes/i)).toBeInTheDocument()
  })

  it('la guía se actualiza en vivo al corregir el tipo de un componente', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2') // COMPLEMENTARIO
    await addComponente(user, '2') // COMPLEMENTARIO → falta transporte
    expect(screen.getByText(/al menos un componente de transporte/i)).toBeInTheDocument()
    // Cambiar el componente 1 a transporte → 1 transporte + 1 complementario = válido → sin guía.
    await user.selectOptions(
      within(screen.getAllByTestId('integral-child')[0]).getByLabelText(/tipo de servicio del componente/i),
      '1', // SCB · SERVICIO
    )
    expect(screen.queryByText(/al menos un componente de transporte/i)).not.toBeInTheDocument()
  })

  it('el Integral incompleto no bloquea la navegación', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2') // incompleto (1 solo)
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByRole('heading', { name: /stand-by por ítem/i })).toBeInTheDocument()
  })

  it('el Integral completo marca el card como Completo al colapsarlo', async () => {
    const user = userEvent.setup()
    server.use(cargoTypesSearch([fakeCargoType({ id: 7, name: 'EXCAVADORA 330', standardWeight: 33000 })]))
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '1') // SERVICIO
    await user.type(lastChild().getByLabelText('Tipo de carga'), 'exca')
    await user.click(await screen.findByText('EXCAVADORA 330')) // precarga peso 33000
    await addComponente(user, '2') // COMPLEMENTARIO
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '12000')
    await user.click(screen.getAllByRole('button', { expanded: true })[0]) // colapsa el card
    expect(await screen.findByText('Completo')).toBeInTheDocument()
  })

  it('cambiar el tipo del ítem #1 fuera de Integral descarta el modo y los componentes', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '2')
    expect(screen.getByTestId('integral-child')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // CES (sale de Integral)
    expect(screen.queryByText(/modo integral activado/i)).not.toBeInTheDocument()
    expect(screen.queryByTestId('integral-child')).not.toBeInTheDocument()
  })

  // ----- Step 3: Stand-By -----
  /** Va al Step 2, agrega un ítem COMPLEMENTARIO (id 2), y salta al Step 3 por el stepper. */
  async function goToStandByWithItem(user: ReturnType<typeof userEvent.setup>) {
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // CES · COMPLEMENTARIO
    await user.click(screen.getByRole('button', { name: /stand-by/i }))
    await screen.findByRole('heading', { name: /stand-by por ítem/i })
  }

  it('el Step 3 muestra la pantalla de Stand-By', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /stand-by/i }))
    expect(await screen.findByRole('heading', { name: /stand-by por ítem/i })).toBeInTheDocument()
    expect(screen.getByText(/máximo un stand-by por ítem/i)).toBeInTheDocument()
  })

  it('sin ítems elegibles muestra el mensaje guía', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /stand-by/i }))
    expect(await screen.findByText(/no hay ítems que admitan stand-by/i)).toBeInTheDocument()
  })

  it('lista los ítems elegibles para agregar stand-by', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    const select = screen.getByLabelText(/agregar stand-by a un ítem/i)
    expect(within(select).getByRole('option', { name: /ítem 1 — escolta armada/i })).toBeInTheDocument()
  })

  it('agrega un stand-by a un ítem y muestra precio + incluye IGV', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    expect(screen.getByLabelText(/precio por día de ítem 1/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/el precio incluye igv/i)).toBeInTheDocument()
  })

  it('edita el precio por día del stand-by', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.type(price, '800')
    expect((price as HTMLInputElement).value).toBe('800')
  })

  it('quita un stand-by y el ítem vuelve a estar disponible', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    expect(screen.getByLabelText(/precio por día de ítem 1/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /quitar stand-by/i }))
    expect(screen.queryByLabelText(/precio por día de ítem 1/i)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/agregar stand-by a un ítem/i)).toBeInTheDocument()
  })

  it('el precio vacío marca error (validación reactiva del stand-by)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.tab()
    expect(await screen.findByText(/ingresa el precio por día/i)).toBeInTheDocument()
  })

  it('quitar y volver a agregar un stand-by al mismo ítem no deja error stale', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    // Provocar el error de precio vacío.
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.tab()
    expect(await screen.findByText(/ingresa el precio por día/i)).toBeInTheDocument()
    // Quitar y volver a agregar al MISMO ítem: el error de "vacío" no debe persistir (re-agrega
    // en 0; el de "> 0" queda latente hasta re-tocar).
    await user.click(screen.getByRole('button', { name: /quitar stand-by/i }))
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    expect(screen.queryByText(/ingresa el precio por día/i)).not.toBeInTheDocument()
  })

  it('re-tipar un ítem a Servicio Integral descarta su stand-by', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user) // ítem #1 COMPLEMENTARIO + stand-by
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.type(price, '500')
    // Volver al Step 2, re-tipar a Integral (descarta el stand-by) y de vuelta a complementario.
    await user.click(screen.getByRole('button', { name: /ítems/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '4') // INT
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // CES de nuevo
    await user.click(screen.getByRole('button', { name: /stand-by/i }))
    await screen.findByRole('heading', { name: /stand-by por ítem/i })
    // El stand-by se descartó al pasar por Integral: no reaparece la fila con el precio anterior.
    expect(screen.queryByLabelText(/precio por día de ítem 1/i)).not.toBeInTheDocument()
  })

  it('el Servicio Integral padre no admite stand-by; sus componentes sí', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '1') // SERVICIO
    await addComponente(user, '2') // COMPLEMENTARIO
    await user.click(screen.getByRole('button', { name: /stand-by/i }))
    await screen.findByRole('heading', { name: /stand-by por ítem/i })
    const select = screen.getByLabelText(/agregar stand-by a un ítem/i)
    expect(within(select).queryByRole('option', { name: /ítem 1 — servicio integral/i })).not.toBeInTheDocument()
    expect(within(select).getByRole('option', { name: /componente 1/i })).toBeInTheDocument()
    expect(within(select).getByRole('option', { name: /componente 2/i })).toBeInTheDocument()
  })

  it('el Step 3 sin stand-by se marca como completado al dejarlo', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user) // step 3, ítem elegible pero sin stand-by (opcional)
    await user.click(screen.getByRole('button', { name: /siguiente/i })) // deja el step 3
    expect(await screen.findByRole('button', { name: /stand-by \(completado\)/i })).toBeInTheDocument()
  })

  it('un stand-by con precio válido marca el Step 3 como completado', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.type(price, '800')
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByRole('button', { name: /stand-by \(completado\)/i })).toBeInTheDocument()
  })

  it('un stand-by con precio vacío marca el Step 3 con alerta', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.click(screen.getByRole('button', { name: /siguiente/i })) // deja el step 3 con precio vacío
    expect(await screen.findByRole('button', { name: /stand-by \(con alerta\)/i })).toBeInTheDocument()
  })

  it('un stand-by con precio 0 marca el Step 3 con alerta (debe ser mayor a 0)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    // El stand-by arranca en 0 (default), que NO es válido para un stand-by agregado.
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByRole('button', { name: /stand-by \(con alerta\)/i })).toBeInTheDocument()
  })

  it('el precio 0 del stand-by muestra error inline "mayor a 0"', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.type(price, '0')
    await user.tab()
    expect(await screen.findByText(/mayor a 0/i)).toBeInTheDocument()
  })

  // ----- Step 4: Resumen -----
  it('el Step 4 muestra el resumen final (vacío) con sus guías', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    expect(await screen.findByRole('heading', { name: /resumen final/i })).toBeInTheDocument()
    expect(screen.getByText(/sin cliente seleccionado/i)).toBeInTheDocument()
    expect(screen.getByText(/agrega ítems en el paso 2/i)).toBeInTheDocument()
  })

  it('el resumen muestra el cliente seleccionado y las condiciones', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ ruc: '20123456789' })]))
    renderWizard()
    await waitForForm()
    await selectAcme(user)
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    expect(screen.getByText('ACME S.A.C.')).toBeInTheDocument()
    expect(screen.getByText(/RUC 20123456789/i)).toBeInTheDocument()
    expect(screen.getByText(/15 días/i)).toBeInTheDocument() // validez por defecto
  })

  it('el resumen lista los ítems y el total general', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // CES · Escolta armada
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '1000')
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    expect(screen.getByText('Detalle de ítems')).toBeInTheDocument()
    expect(screen.getByText('Escolta armada')).toBeInTheDocument()
    // Total general = 1000 × 1.18 = 1180, con su monto en letras.
    expect(screen.getByText('Total general').closest('div')).toHaveTextContent(/1[.,]?180/)
    expect(screen.getByText(/mil ciento ochenta con 00\/100 soles/i)).toBeInTheDocument()
  })

  it('la columna IGV muestra el % en la cabecera y el monto por fila (no "18%" suelto)', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // Escolta, precio 1000
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '1000')
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    expect(screen.getByText(/IGV \(18%\)/)).toBeInTheDocument() // % en la cabecera
    expect(screen.queryByText('18%')).not.toBeInTheDocument() // ya no hay "18%" suelto por fila
  })

  it('un componente sin precio ref muestra "—" (no un precio de 0)', async () => {
    const user = userEvent.setup()
    server.use(cargoTypesSearch([fakeCargoType({ id: 7, name: 'EXCAVADORA 326', standardWeight: 25900 })]))
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    // Componente SERVICIO + cargo (lo que antes coercía el precio ref vacío a 0).
    await user.click(screen.getByRole('button', { name: /agregar componente/i }))
    const card1 = within(screen.getAllByTestId('integral-child')[0])
    await user.selectOptions(card1.getByLabelText(/tipo de servicio del componente/i), '1')
    await user.type(card1.getByLabelText('Tipo de carga'), 'exca')
    await user.click(await screen.findByText('EXCAVADORA 326'))
    // Total ref. sin precio referencial → "—", no "S/ 0.00".
    expect((card1.getByLabelText(/total del componente 1/i) as HTMLInputElement).value).toBe('—')
    // Completar el Integral y ver el Resumen.
    await user.click(screen.getByRole('button', { name: /agregar componente/i }))
    await user.selectOptions(
      within(screen.getAllByTestId('integral-child')[1]).getByLabelText(/tipo de servicio del componente/i),
      '2',
    )
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    const childRow = screen.getByText('Transporte de carga general').closest('tr')!
    expect(within(childRow).queryByText(/0[.,]00/)).not.toBeInTheDocument() // no hay "S/ 0.00"
    // El hijo de transporte muestra QUÉ transporta (tipo de carga), no solo el nombre del servicio.
    expect(within(childRow).getByText(/EXCAVADORA 326/)).toBeInTheDocument()
  })

  it('el resumen muestra la jerarquía del Servicio Integral', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await activarIntegral(user)
    await addComponente(user, '1') // SERVICIO
    await addComponente(user, '2') // COMPLEMENTARIO
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    expect(screen.getByText('Servicio Integral')).toBeInTheDocument() // padre
    expect(screen.getByText('Transporte de carga general')).toBeInTheDocument() // hijo SERVICIO
    expect(screen.getByText('Escolta armada')).toBeInTheDocument() // hijo COMPLEMENTARIO
    // Numeración jerárquica de presentación derivada en el Resumen: hijos "1.a", "1.b".
    expect(screen.getByText('1.a')).toBeInTheDocument()
    expect(screen.getByText('1.b')).toBeInTheDocument()
  })

  it('el resumen muestra la tabla de stand-by cuando hay', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToStandByWithItem(user)
    await user.selectOptions(screen.getByLabelText(/agregar stand-by a un ítem/i), '0')
    const price = screen.getByLabelText(/precio por día de ítem 1/i)
    await user.clear(price)
    await user.type(price, '500')
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    expect(screen.getByRole('heading', { name: 'Stand-By' })).toBeInTheDocument()
  })

  // ----- Guardar (POST /quotations) -----
  /** Llena un form VÁLIDO: cliente (precarga contacto) + ruta + un ítem complementario con precio. */
  async function fillValidQuotation(user: ReturnType<typeof userEvent.setup>) {
    server.use(clientsSearch([fakeClient({ contactName: 'Juan Pérez', phone: '987654321' })]))
    await waitForForm()
    await selectAcme(user)
    await user.type(screen.getByLabelText('Origen'), 'Lima')
    await user.type(screen.getByLabelText('Destino'), 'Arequipa')
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    await screen.findByText(/ítems de la cotización/i)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    await user.selectOptions(screen.getByLabelText('Tipo de servicio'), '2') // Escolta · COMPLEMENTARIO
    const price = screen.getByLabelText('Precio unitario')
    await user.clear(price)
    await user.type(price, '1000')
  }

  it('guarda la cotización y navega al detalle creado', async () => {
    const user = userEvent.setup()
    const sink: { body?: QuotationRequest } = {}
    server.use(createQuotationSuccess(sink, getQuotationResponse({ id: 42 })))
    renderWizard()
    await fillValidQuotation(user)
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cotización/i }))
    // Navega al detalle de la cotización recién creada.
    expect(await screen.findByText(/DETALLE COTIZACION 42/i)).toBeInTheDocument()
    // El payload llegó aplanado y normalizado.
    expect(sink.body).toMatchObject({
      quotationType: 'TRANSPORTE',
      contactName: 'Juan Pérez',
      origin: 'Lima',
      destination: 'Arequipa',
      items: [{ serviceTypeId: expect.any(Number), unitPrice: 1000 }],
    })
  })

  it('muestra el error del backend si el guardado falla (409 anti-duplicado)', async () => {
    const user = userEvent.setup()
    server.use(createQuotationError(409, { detail: 'Ya existe una cotización idéntica reciente.' }))
    renderWizard()
    await fillValidQuotation(user)
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cotización/i }))
    expect(await screen.findByText(/ya existe una cotización idéntica reciente/i)).toBeInTheDocument()
    // No navega: sigue en el Resumen.
    expect(screen.getByRole('heading', { name: /resumen final/i })).toBeInTheDocument()
  })

  it('con el form incompleto no envía: muestra alerta y no hace POST', async () => {
    const user = userEvent.setup()
    const sink: { body?: QuotationRequest } = {}
    server.use(createQuotationSuccess(sink))
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cotización/i }))
    expect(await screen.findByText(/faltan datos obligatorios/i)).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('deshabilita "Guardar" mientras el envío está en curso (anti doble-click)', async () => {
    const user = userEvent.setup()
    server.use(createQuotationSlow(60))
    renderWizard()
    await fillValidQuotation(user)
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cotización/i }))
    const button = await screen.findByRole('button', { name: /guardando/i })
    expect(button).toBeDisabled()
  })

  // ----- Step 4: Condiciones (US-007) -----
  it('el paso Condiciones muestra el catálogo con las activas pre-marcadas', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /condiciones/i }))
    expect(await screen.findByLabelText('Cond A')).toBeChecked()
    expect(screen.getByLabelText<HTMLInputElement>('Cond B')).toBeChecked()
  })

  it('crear desmarcando una condición → el POST manda solo las que quedaron', async () => {
    const user = userEvent.setup()
    const sink: { body?: QuotationRequest } = {}
    server.use(createQuotationSuccess(sink, getQuotationResponse({ id: 42 })))
    renderWizard()
    await fillValidQuotation(user)
    await user.click(screen.getByRole('button', { name: /condiciones/i }))
    await user.click(await screen.findByLabelText('Cond A')) // destilda id 1
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cotización/i }))
    await screen.findByText(/DETALLE COTIZACION 42/i)
    expect(sink.body?.conditionIds).toEqual([2])
  })

  it('crear sin tocar las condiciones → el POST manda todas las activas (RN-07)', async () => {
    const user = userEvent.setup()
    const sink: { body?: QuotationRequest } = {}
    server.use(createQuotationSuccess(sink, getQuotationResponse({ id: 7 })))
    renderWizard()
    await fillValidQuotation(user)
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cotización/i }))
    await screen.findByText(/DETALLE COTIZACION 7/i)
    expect(sink.body?.conditionIds).toEqual([1, 2])
  })

  it('crear destildando TODAS las condiciones → el POST manda conditionIds vacío (válido)', async () => {
    const user = userEvent.setup()
    const sink: { body?: QuotationRequest } = {}
    server.use(createQuotationSuccess(sink, getQuotationResponse({ id: 8 })))
    renderWizard()
    await fillValidQuotation(user)
    await user.click(screen.getByRole('button', { name: /condiciones/i }))
    await user.click(await screen.findByLabelText('Cond A'))
    await user.click(screen.getByLabelText('Cond B'))
    await user.click(screen.getByRole('button', { name: /resumen/i }))
    await screen.findByRole('heading', { name: /resumen final/i })
    await user.click(screen.getByRole('button', { name: /guardar cotización/i }))
    await screen.findByText(/DETALLE COTIZACION 8/i)
    expect(sink.body?.conditionIds).toEqual([])
  })
})
