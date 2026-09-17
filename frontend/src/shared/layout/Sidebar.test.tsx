import {
  CHANGE_PASSWORD_PATH,
  CLIENTS_BASE,
  OPERATIONS_BASE,
  QUOTATIONS_BASE,
  WAREHOUSE_BASE,
} from '../../shared/paths'
import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { Sidebar } from './Sidebar'
import { AuthProvider } from '../auth/AuthContext'
import { ThemeProvider } from '../ui/theme/ThemeContext'
import { tokenStorage } from '../auth/tokenStorage'
import { server } from '../../test/mocks/server'
import type { UserResponse, UserRole } from '../../api'

const API = 'http://localhost:8080/api/v1'

function buildUser(role: UserRole): UserResponse {
  return {
    id: 1,
    username: `user-${role}`,
    fullName: `Usuario ${role}`,
    position: 'Cargo de prueba',
    role,
    isActive: true,
  }
}

function renderSidebarAs(role: UserRole, initialPath = QUOTATIONS_BASE) {
  server.use(
    http.get(`${API}/auth/me`, () => HttpResponse.json(buildUser(role))),
  )
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={[initialPath]}>
            <Sidebar />
          </MemoryRouter>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

describe('Sidebar - filtrado por rol', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it('admin ve Cotizaciones + Clientes + Cambiar contraseña', async () => {
    renderSidebarAs('admin')
    await waitFor(() => {
      expect(screen.getByText(/usuario admin/i)).toBeInTheDocument()
    })
    // Cotizaciones ya está activa: es un link navegable (no el placeholder disabled).
    expect(screen.getByRole('link', { name: /cotizaciones/i })).toHaveAttribute(
      'href',
      QUOTATIONS_BASE,
    )
    expect(screen.getByText('Clientes')).toBeInTheDocument()
    expect(screen.getByText(/comercial/i)).toBeInTheDocument()
    // Operaciones: Servicios es navegable dentro de la SPA
    expect(screen.getByRole('link', { name: /^servicios$/i })).toHaveAttribute(
      'href',
      OPERATIONS_BASE,
    )
    expect(screen.getByRole('link', { name: /cambiar contraseña/i })).toHaveAttribute(
      'href',
      CHANGE_PASSWORD_PATH,
    )
    expect(screen.getByText(/administrar cuenta/i)).toBeInTheDocument()
  })

  /**
   * `sales` veía "Clientes" hasta que el maestro tuvo pantalla propia: el ítem
   * usaba la lista de cotizaciones, que lo incluye. Da clientes de alta al vuelo
   * desde el asistente, pero corregirlos no es suyo.
   *
   * La espera del nombre del usuario no es decorado: sin ella, la consulta del
   * ítem corre con la barra a medio montar y devuelve nulo por el motivo
   * equivocado, o sea que el caso pasaría aunque el ítem siguiera visible. Y la
   * afirmación de "Cotizaciones" distingue "se ocultó el ítem" de "no se dibujó
   * ninguno".
   */
  it('sales ya no ve Clientes, pero sigue viendo Cotizaciones', async () => {
    renderSidebarAs('sales')
    await waitFor(() => {
      expect(screen.getByText(/usuario sales/i)).toBeInTheDocument()
    })
    expect(screen.getByText('Cotizaciones')).toBeInTheDocument()
    expect(screen.queryByText('Clientes')).not.toBeInTheDocument()
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
  })

  it('dispatcher ve Operaciones + Cambiar contraseña (sección Comercial oculta)', async () => {
    renderSidebarAs('dispatcher')
    await waitFor(() => {
      expect(screen.getByText(/usuario dispatcher/i)).toBeInTheDocument()
    })
    expect(screen.queryByText('Cotizaciones')).not.toBeInTheDocument()
    // Operaciones es su único lugar de trabajo, y ahora vive acá adentro.
    expect(screen.getByRole('link', { name: /^servicios$/i })).toBeInTheDocument()
    expect(screen.queryByText('Clientes')).not.toBeInTheDocument()
    // La sección Comercial entera (con su <h2>) se oculta cuando queda sin items
    expect(screen.queryByText(/^comercial$/i)).not.toBeInTheDocument()
    // Cambiar contraseña es visible para TODOS los roles (sin allowedRoles)
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
    expect(screen.getByText(/administrar cuenta/i)).toBeInTheDocument()
  })

  it('general_manager ve Cotizaciones + Clientes + Cambiar contraseña', async () => {
    renderSidebarAs('general_manager')
    await waitFor(() => {
      expect(screen.getByText(/usuario general_manager/i)).toBeInTheDocument()
    })
    expect(screen.getByText('Cotizaciones')).toBeInTheDocument()
    expect(screen.getByText('Clientes')).toBeInTheDocument()
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
  })

  it('operations_manager ve Cotizaciones + Clientes + Cambiar contraseña', async () => {
    renderSidebarAs('operations_manager')
    await waitFor(() => {
      expect(screen.getByText(/usuario operations_manager/i)).toBeInTheDocument()
    })
    expect(screen.getByText('Cotizaciones')).toBeInTheDocument()
    expect(screen.getByText('Clientes')).toBeInTheDocument()
    expect(screen.getByText(/cambiar contraseña/i)).toBeInTheDocument()
  })
})

describe('Sidebar - módulo Almacén', () => {
  beforeEach(() => {
    tokenStorage.clear()
  })

  it.each(['admin', 'general_manager', 'operations_manager', 'finance_manager', 'warehouse_keeper'] as const)(
    '%s ve la sección Almacén con Existencias navegable',
    async (role) => {
      renderSidebarAs(role)
      await waitFor(() => {
        expect(screen.getByText(`Usuario ${role}`)).toBeInTheDocument()
      })
      expect(screen.getByText(/^almacén$/i)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /existencias/i })).toHaveAttribute(
        'href',
        WAREHOUSE_BASE,
      )
    },
  )

  it.each(['sales', 'dispatcher'] as const)(
    '%s no ve la sección Almacén (se oculta entera, sin h2 huérfano)',
    async (role) => {
      renderSidebarAs(role)
      await waitFor(() => {
        expect(screen.getByText(`Usuario ${role}`)).toBeInTheDocument()
      })
      expect(screen.queryByText(/^almacén$/i)).not.toBeInTheDocument()
      expect(screen.queryByText('Existencias')).not.toBeInTheDocument()
    },
  )

  it('los roles de almacén no ven Cotizaciones ni Clientes', async () => {
    renderSidebarAs('finance_manager')
    await waitFor(() => {
      expect(screen.getByText(/usuario finance_manager/i)).toBeInTheDocument()
    })
    expect(screen.queryByText('Cotizaciones')).not.toBeInTheDocument()
    expect(screen.queryByText('Clientes')).not.toBeInTheDocument()
    expect(screen.queryByText(/^comercial$/i)).not.toBeInTheDocument()
  })

  it('reportes navega a su pantalla', async () => {
    renderSidebarAs('warehouse_keeper')
    const reportes = await screen.findByRole('link', { name: /reportes/i })
    expect(reportes).toHaveAttribute('href', `${WAREHOUSE_BASE}/reportes`)
  })

  it('entradas navega a su listado', async () => {
    renderSidebarAs('warehouse_keeper')
    const entradas = await screen.findByRole('link', { name: /entradas/i })
    expect(entradas).toHaveAttribute('href', `${WAREHOUSE_BASE}/entradas`)
  })

  it('retiros navega a su listado', async () => {
    renderSidebarAs('warehouse_keeper')
    const retiros = await screen.findByRole('link', { name: /retiros/i })
    expect(retiros).toHaveAttribute('href', `${WAREHOUSE_BASE}/retiros`)
  })

  it('en entradas se resalta Entradas y NO Existencias', async () => {
    renderSidebarAs('admin', `${WAREHOUSE_BASE}/entradas`)
    const entradas = await screen.findByRole('link', { name: /entradas/i })
    expect(entradas).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: /existencias/i })).not.toHaveAttribute('aria-current')
  })

  it('en almacén se resalta Existencias y NO Cotizaciones', async () => {
    // Cada módulo tiene su propia raíz, así que el prefijo de cotizaciones no
    // alcanza a almacén. Hasta la mudanza de 2026-09 sí lo hacía, y por eso el
    // ítem llevaba un matcher propio con una lista de exclusiones.
    renderSidebarAs('admin', WAREHOUSE_BASE)
    const existencias = await screen.findByRole('link', { name: /existencias/i })
    expect(existencias).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: /^cotizaciones$/i })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('el detalle de un producto sigue resaltando Existencias', async () => {
    renderSidebarAs('admin', `${WAREHOUSE_BASE}/productos/42`)
    const existencias = await screen.findByRole('link', { name: /existencias/i })
    expect(existencias).toHaveAttribute('aria-current', 'page')
  })

  it.each(['finance_manager', 'warehouse_keeper'] as const)(
    '%s no ve la sección Operaciones',
    async (role) => {
      renderSidebarAs(role)
      await waitFor(() => {
        expect(screen.getByText(`Usuario ${role}`)).toBeInTheDocument()
      })
      // Los roles de almacén trabajan solo en su módulo.
      // Por texto y no por rol: si el item perdiera su destino se renderiza
      // como <span> deshabilitado, y una búsqueda por rol de enlace lo daría
      // por ausente estando visible en pantalla.
      expect(screen.queryByText(/^servicios$/i)).not.toBeInTheDocument()
      // Sin items, la sección entera se oculta (no queda el <h2> huérfano).
      expect(screen.queryByText(/^operaciones$/i)).not.toBeInTheDocument()
    },
  )

  it.each([
    'admin',
    'sales',
    'general_manager',
    'operations_manager',
    'dispatcher',
  ] as const)('%s ve la sección Operaciones', async (role) => {
    renderSidebarAs(role)
    await waitFor(() => {
      expect(screen.getByText(`Usuario ${role}`)).toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: /^servicios$/i })).toHaveAttribute(
      'href',
      OPERATIONS_BASE,
    )
    expect(screen.getByText(/^operaciones$/i)).toBeInTheDocument()
  })

  it('los matchers respetan el borde de segmento en ambos módulos', async () => {
    // Una ruta que solo comparte texto con el prefijo no es el módulo.
    renderSidebarAs('admin', `${WAREHOUSE_BASE}/productosX`)
    await screen.findByRole('link', { name: /existencias/i })
    expect(screen.getByRole('link', { name: /existencias/i })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('link', { name: /^cotizaciones$/i })).not.toHaveAttribute(
      'aria-current',
    )
  })
})

describe('Sidebar - módulo Operaciones', () => {
  it('en operaciones se resalta Servicios y NO Cotizaciones', async () => {
    // Cada módulo tiene su propia raíz, así que el prefijo de cotizaciones no
    // alcanza a operaciones. Hasta la mudanza de 2026-09 sí lo hacía, y por eso
    // el matcher llevaba una lista de exclusiones.
    renderSidebarAs('admin', OPERATIONS_BASE)
    const servicios = await screen.findByRole('link', { name: /^servicios$/i })
    expect(servicios).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: /^cotizaciones$/i })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it.each([QUOTATIONS_BASE, `${QUOTATIONS_BASE}/12`])(
    'estando en %s, el item Cotizaciones queda marcado como la página actual',
    async (path) => {
      renderSidebarAs('admin', path)
      const cotizaciones = await screen.findByRole('link', { name: /^cotizaciones$/i })
      expect(cotizaciones).toHaveAttribute('aria-current', 'page')
    },
  )

  it('el detalle de un servicio sigue resaltando Servicios', async () => {
    renderSidebarAs('admin', `${OPERATIONS_BASE}/servicios/42`)
    const servicios = await screen.findByRole('link', { name: /^servicios$/i })
    expect(servicios).toHaveAttribute('aria-current', 'page')
  })

  it('una hermana de servicios no resalta Servicios', async () => {
    // El día que Reportes tenga pantalla, el prefijo pelado marcaría Servicios
    // estando en ella. Este caso fija que el matcher no lo hace.
    renderSidebarAs('admin', `${OPERATIONS_BASE}/reportes`)
    const servicios = await screen.findByRole('link', { name: /^servicios$/i })
    expect(servicios).not.toHaveAttribute('aria-current')
  })

  it('Reportes de operaciones está deshabilitado y el despachador no lo ve', async () => {
    // Sin pantalla todavía: se anuncia como deshabilitado, no como link. Y el
    // contrato deja al despachador afuera del reporte semanal (ve precios).
    renderSidebarAs('admin', OPERATIONS_BASE)
    const reportes = await screen.findByText('Reportes de operaciones')
    expect(reportes).toHaveAttribute('aria-disabled', 'true')
    // Lo que de verdad llega a un lector de pantalla es este texto: sobre un
    // <span> sin rol, `aria-disabled` no se anuncia. Se afirman los dos para
    // que el día que el item pase a ser focusable no se pierda el aviso.
    expect(reportes).toHaveTextContent(/próximamente/i)
    expect(
      screen.queryByRole('link', { name: /reportes de operaciones/i }),
    ).not.toBeInTheDocument()
  })

  it('el despachador ve Servicios pero no el reporte', async () => {
    renderSidebarAs('dispatcher', OPERATIONS_BASE)
    expect(await screen.findByRole('link', { name: /^servicios$/i })).toBeInTheDocument()
    expect(screen.queryByText('Reportes de operaciones')).not.toBeInTheDocument()
  })
})

describe('Sidebar - maestro de clientes', () => {
  /**
   * El ítem pasó de deshabilitado a navegable. Se afirma por `link` y no por
   * texto: hasta esta unidad era un `<span aria-disabled>` con el título
   * "Próximamente", que una consulta por texto encontraría igual.
   */
  it.each(['admin', 'general_manager', 'operations_manager'] as const)(
    '%s ve Clientes como enlace a la pantalla',
    async (role) => {
      renderSidebarAs(role)
      await waitFor(() => {
        expect(screen.getByText(new RegExp(`usuario ${role}`, 'i'))).toBeInTheDocument()
      })
      expect(screen.getByRole('link', { name: /^clientes$/i })).toHaveAttribute('href', CLIENTS_BASE)
    },
  )

  it.each(['finance_manager', 'warehouse_keeper'] as const)(
    '%s no ve Clientes',
    async (role) => {
      renderSidebarAs(role)
      await waitFor(() => {
        expect(screen.getByText(new RegExp(`usuario ${role}`, 'i'))).toBeInTheDocument()
      })
      expect(screen.queryByText('Clientes')).not.toBeInTheDocument()
    },
  )

  it('estando en la búsqueda, Clientes queda marcado como la página actual', async () => {
    renderSidebarAs('admin', CLIENTS_BASE)
    await waitFor(() => {
      expect(screen.getByText(/usuario admin/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: /^clientes$/i })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  /**
   * Y sigue marcado dentro del formulario de un cliente. El ítem no lleva un
   * matcher propio: el resaltado por omisión ya compara por prefijo de segmento.
   * Sin este caso, agregarle uno de igualdad exacta "por simetría con los otros"
   * pasa sin que nadie lo note, y el menú se apagaría al abrir un cliente.
   */
  it('estando en el formulario de un cliente, Clientes sigue marcado', async () => {
    renderSidebarAs('admin', `${CLIENTS_BASE}/7/editar`)
    await waitFor(() => {
      expect(screen.getByText(/usuario admin/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: /^clientes$/i })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  /**
   * El maestro de clientes dejó el grupo Comercial: es transversal a cotizaciones
   * y a operaciones, y ahí va a vivir también el maestro de usuarios. Se afirma
   * el grupo y no solo el ítem, porque mover el ítem de grupo no rompe ninguna
   * aserción de visibilidad.
   */
  it('Clientes vive en el grupo Administración y no en Comercial', async () => {
    renderSidebarAs('admin')
    await waitFor(() => {
      expect(screen.getByText(/usuario admin/i)).toBeInTheDocument()
    })

    const administracion = screen.getByText(/^administración$/i)
    const comercial = screen.getByText(/^comercial$/i)
    const clientes = screen.getByRole('link', { name: /^clientes$/i })

    // El ítem está después del encabezado de Administración, y ese encabezado
    // está después del de Comercial.
    expect(comercial.compareDocumentPosition(administracion) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
    expect(administracion.compareDocumentPosition(clientes) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
  })

  /** Un grupo sin ítems visibles no se dibuja, encabezado incluido. */
  it('sales no ve el grupo Administración, pero sí Comercial', async () => {
    renderSidebarAs('sales')
    await waitFor(() => {
      expect(screen.getByText(/usuario sales/i)).toBeInTheDocument()
    })

    expect(screen.queryByText(/^administración$/i)).not.toBeInTheDocument()
    expect(screen.getByText(/^comercial$/i)).toBeInTheDocument()
    // Y "Administrar cuenta", que es lo personal, sigue visible para todos.
    expect(screen.getByText(/administrar cuenta/i)).toBeInTheDocument()
  })
})
