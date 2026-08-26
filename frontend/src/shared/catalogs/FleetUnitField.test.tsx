import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { FleetUnitField } from './FleetUnitField'
import { sharedCatalogKeys } from './queryKeys'
import { server } from '../../test/mocks/server'
import type { ProductsCaptureSink } from '../../test/mocks/handlers/warehouse'
import {
  fakeFleetUnit,
  fleetUnitsByKind,
  fleetUnitsError,
  fleetUnitsList,
} from '../../test/mocks/handlers/shared-catalogs'

/**
 * Los tres subtipos con placas que empiezan distinto: si el campo ofreciera el
 * subtipo equivocado, la etiqueta cambia. Con placas parecidas, una aserción por
 * coincidencia parcial no distinguiría cuál se listó.
 */
const TRACTOR = fakeFleetUnit({ kind: 'TRACTOR', id: 5, plate: 'ABC-123' })
const TRAILER = fakeFleetUnit({
  kind: 'TRAILER',
  id: 9,
  plate: 'XY-9876',
  brand: 'Randon',
  model: 'SR',
})
const ESCORT = fakeFleetUnit({
  kind: 'ESCORT',
  id: 3,
  plate: 'ES-100',
  brand: 'Toyota',
  model: 'Hilux',
})
const FLEET = [TRACTOR, TRAILER, ESCORT]

function renderField(props: Partial<Parameters<typeof FleetUnitField>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const view = render(
    <FleetUnitField
      id="fleet-unit"
      label="Unidad de flota"
      selected={null}
      onSelectedChange={vi.fn()}
      placeholder="Selecciona una unidad…"
      loadErrorText="No se pudieron cargar las unidades."
      {...props}
    />,
    { wrapper },
  )
  /**
   * Espera a que el catálogo TERMINE de cargar y a que el campo se haya vuelto a
   * dibujar con él.
   *
   * Sin esto, una aserción sobre el campo puede pasar en la ventana en la que
   * todavía no llegó ninguna unidad, y entonces no mide lo que dice medir.
   *
   * Se busca por la key de la flota y no por "la primera query activa": el día que
   * el caso monte algo con otra query, buscar por posición esperaría a la
   * equivocada en silencio, y así en cambio no encuentra nada y falla.
   *
   * El `setTimeout(0)` no es una espera de cortesía: react-query avisa a sus
   * observers con un timer, así que el estado de la query pasa a `success` un turno
   * ANTES de que React redibuje. Sin vaciarlo, la aserción puede leer el DOM
   * anterior a la llegada del catálogo.
   */
  const catalogLoaded = async () => {
    await waitFor(() => {
      const [fleetQuery] = queryClient
        .getQueryCache()
        .findAll({ queryKey: sharedCatalogKeys.all, type: 'active' })
      expect(fleetQuery?.state.status).toBe('success')
    })
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
  return { ...view, catalogLoaded }
}

describe('FleetUnitField', () => {
  it('sin subtipo ofrece las tres clases de unidad', async () => {
    server.use(fleetUnitsByKind(FLEET))
    const user = userEvent.setup()
    renderField()

    await user.click(screen.getByLabelText('Unidad de flota'))
    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Tracto ABC-123')).toBeInTheDocument()
    expect(within(listbox).getByText('Carreta XY-9876')).toBeInTheDocument()
    expect(within(listbox).getByText('Escolta ES-100')).toBeInTheDocument()
  })

  it('con subtipo se lo pide al backend y ofrece solo esa clase', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET, sink))
    const user = userEvent.setup()
    renderField({ kind: 'TRACTOR' })

    await user.click(screen.getByLabelText('Unidad de flota'))
    await waitFor(() => expect(sink.params?.get('kind')).toBe('TRACTOR'))
    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Tracto ABC-123')).toBeInTheDocument()
    // Los dos negativos: sin ellos, un campo que ignorara el subtipo y trajera la
    // flota entera pasaría igual, porque el tracto está en las dos listas.
    expect(within(listbox).queryByText('Carreta XY-9876')).not.toBeInTheDocument()
    expect(within(listbox).queryByText('Escolta ES-100')).not.toBeInTheDocument()
  })

  it('muestra el texto de ayuda que le pasa el consumidor', async () => {
    server.use(fleetUnitsList(FLEET))
    // Un texto que ninguna pantalla usa: con el literal real de almacén, reponerlo
    // como valor por defecto del componente pasaría desapercibido.
    const { catalogLoaded } = renderField({ placeholder: 'Busca la unidad acá…' })

    expect(screen.getByLabelText('Unidad de flota')).toHaveAttribute(
      'placeholder',
      'Busca la unidad acá…',
    )
    // Se espera el catálogo aunque la aserción no lo necesite: si no, el pedido
    // queda en vuelo y resuelve después del desmontaje.
    await catalogLoaded()
  })

  it('nombra a las unidades cuando el catálogo viene vacío', async () => {
    server.use(fleetUnitsList([]))
    const user = userEvent.setup()
    renderField()

    await user.click(screen.getByLabelText('Unidad de flota'))
    // El `Combobox` tiene su propio vacío genérico ("No se encontraron
    // resultados."), así que perder este texto degrada en silencio.
    expect(await screen.findByText('No se encontraron unidades.')).toBeInTheDocument()
  })

  it('avisa con el mensaje del consumidor, y lo anuncia, cuando el catálogo no carga', async () => {
    server.use(fleetUnitsError(500))
    const user = userEvent.setup()
    renderField({ loadErrorText: 'No se pudo cargar la flota. Selecciona otra cosa.' })

    await user.click(screen.getByLabelText('Unidad de flota'))
    const alert = await screen.findByRole('alert')
    // Por `role`, no solo por texto: el aviso aparece DESPUÉS de que el usuario
    // llegó al campo, así que sin el rol un lector de pantalla no se entera, y el
    // atributo se puede perder sin que ninguna otra aserción lo note.
    expect(alert.textContent).toBe('No se pudo cargar la flota. Selecciona otra cosa.')
  })

  it('busca por marca y por modelo, no solo por la placa', async () => {
    server.use(fleetUnitsList(FLEET))
    const user = userEvent.setup()
    renderField()
    const input = screen.getByLabelText('Unidad de flota')

    // Cada término deja UNA sola unidad, y son tres unidades distintas: si el campo
    // buscara solo por la etiqueta ("Tracto ABC-123"), la marca y el modelo no
    // encontrarían nada. Las tres traen marca y modelo propios a propósito.
    //
    // La búsqueda por placa NO se mide acá, y no por olvido: la etiqueta ya contiene
    // la placa, así que ningún término puede distinguir una de la otra. Medido
    // sacando `unit.plate` de la lista de campos buscables: la suite queda entera en
    // verde, o sea que ese término es redundante en producción.
    await user.click(input)
    await user.type(input, 'Randon')
    let listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Carreta XY-9876')).toBeInTheDocument()
    expect(within(listbox).queryByText('Tracto ABC-123')).not.toBeInTheDocument()

    await user.clear(input)
    await user.type(input, 'Hilux')
    listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Escolta ES-100')).toBeInTheDocument()
    expect(within(listbox).queryByText('Carreta XY-9876')).not.toBeInTheDocument()

    await user.clear(input)
    await user.type(input, 'ABC')
    listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Tracto ABC-123')).toBeInTheDocument()
    expect(within(listbox).queryByText('Escolta ES-100')).not.toBeInTheDocument()
  })

  it('muestra la marca y el modelo como sublínea de cada unidad', async () => {
    server.use(fleetUnitsList(FLEET))
    const user = userEvent.setup()
    renderField()

    await user.click(screen.getByLabelText('Unidad de flota'))
    const listbox = await screen.findByRole('listbox')
    // Es lo que distingue dos unidades del mismo subtipo cuando la placa no alcanza.
    expect(await within(listbox).findByText('Randon SR')).toBeInTheDocument()
    expect(within(listbox).getByText('Toyota Hilux')).toBeInTheDocument()
  })

  it('devuelve la unidad elegida resolviéndola por el par (subtipo, id), no por el id', async () => {
    // Dos unidades de DISTINTO subtipo con el MISMO id: es el caso que justifica la
    // clave compuesta. Resolviendo por id suelto, elegir la carreta devolvería el
    // tracto, y el consumidor guardaría el recurso equivocado sin que nada avise.
    const tractor = fakeFleetUnit({ kind: 'TRACTOR', id: 5, plate: 'ABC-123' })
    const trailer = fakeFleetUnit({
      kind: 'TRAILER',
      id: 5,
      plate: 'XY-9876',
      brand: 'Randon',
      model: 'SR',
    })
    server.use(fleetUnitsList([tractor, trailer]))
    const onSelectedChange = vi.fn()
    const user = userEvent.setup()
    renderField({ onSelectedChange })

    await user.click(screen.getByLabelText('Unidad de flota'))
    const listbox = await screen.findByRole('listbox')
    await user.click(await within(listbox).findByText('Carreta XY-9876'))

    // El objeto ENTERO, no el id: el consumidor usa la marca y el modelo, y con solo
    // el id las dos unidades serían indistinguibles, que es justo lo que se prueba.
    expect(onSelectedChange).toHaveBeenCalledWith(trailer)
  })

  it('conserva la unidad ya elegida aunque el catálogo no la traiga', async () => {
    // El caso real: un retiro viejo cargado a un tracto que después se dio de baja.
    // El catálogo pide solo las vigentes, así que esa unidad NO viene, y aun así
    // tiene que seguir viéndose elegida hasta que el usuario la cambie.
    server.use(fleetUnitsList([TRAILER, ESCORT]))
    const { catalogLoaded } = renderField({
      selected: { kind: 'TRACTOR', id: 5, plate: 'ABC-123' },
    })

    // Se espera a que el catálogo cargue ANTES de mirar: mientras está en vuelo no
    // hay lista contra la cual descartar la selección, así que la aserción pasaría
    // igual con un campo que después la borra.
    await catalogLoaded()
    expect(screen.getByText('Tracto ABC-123')).toBeInTheDocument()
  })
})
