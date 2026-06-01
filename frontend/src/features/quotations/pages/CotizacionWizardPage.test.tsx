import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
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

function renderWizard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/cotizaciones/nueva']}>
        <Routes>
          <Route path="/cotizaciones" element={<div>LISTADO COTIZACIONES</div>} />
          <Route path="/cotizaciones/nueva" element={<CotizacionWizardPage />} />
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
  it('monta el wizard con el stepper de 4 pasos y el Step 1', async () => {
    renderWizard()
    await waitForForm()
    expect(screen.getByText('Información General')).toBeInTheDocument()
    expect(screen.getByText('Ítems')).toBeInTheDocument()
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

  it('TRANSPORTE muestra Servicios + Complementarios y excluye ALQUILER; el Integral va deshabilitado', async () => {
    const user = userEvent.setup()
    renderWizard()
    await goToItems(user)
    await user.click(screen.getByRole('button', { name: /agregar ítem/i }))
    const select = screen.getByLabelText('Tipo de servicio')
    expect(within(select).getByRole('option', { name: 'Transporte de carga general' })).toBeInTheDocument() // SERVICIO
    expect(within(select).getByRole('option', { name: 'Escolta armada' })).toBeInTheDocument() // COMPLEMENTARIO
    expect(within(select).queryByRole('option', { name: /alquiler de camión/i })).not.toBeInTheDocument() // ALQUILER fuera
    const integral = within(select).getByRole('option', { name: /servicio integral/i })
    expect(integral).toBeInTheDocument()
    expect(integral).toBeDisabled()
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
    expect(await screen.findByText(/costos de stand-by/i)).toBeInTheDocument()
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
})
