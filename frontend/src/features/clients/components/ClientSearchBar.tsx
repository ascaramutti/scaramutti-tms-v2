import { Search } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import { Card } from '../../../shared/ui/Card'
import { fieldClasses } from '../../../shared/ui/fieldClasses'
import { CLIENT_SEARCH_MAX_LENGTH, CLIENT_SEARCH_MIN_LENGTH } from '../hooks/useClientsSearch'

interface ClientSearchBarProps {
  value: string
  onChange: (next: string) => void
}

const inputClasses = cn('w-full', fieldClasses({ density: 'compact' }))

/**
 * Buscador del maestro de clientes.
 *
 * La pista del mínimo se muestra TAMBIÉN con el campo vacío, a diferencia de la
 * barra de filtros de cotizaciones, que solo la muestra cuando ya se escribió
 * algo. Allá el buscador es uno de seis filtros y la tabla abajo ya tiene datos;
 * acá es la única instrucción de una pantalla que sin ella está en blanco.
 */
export function ClientSearchBar({ value, onChange }: ClientSearchBarProps) {
  const showHint = value.trim().length < CLIENT_SEARCH_MIN_LENGTH

  return (
    <Card padding="md">
      <label htmlFor="q" className="mb-1.5 block text-sm font-medium text-fg-body">
        Buscar cliente
      </label>
      <div className="relative">
        <Search
          className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-subtle"
          aria-hidden="true"
        />
        <input
          id="q"
          type="search"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          maxLength={CLIENT_SEARCH_MAX_LENGTH}
          placeholder="Razón social o RUC"
          aria-describedby={showHint ? 'q-hint' : undefined}
          className={cn(inputClasses, 'pl-9 pr-3 placeholder:text-fg-subtle')}
        />
      </div>
      {showHint && (
        <p id="q-hint" className="mt-1 text-xs text-fg-muted">
          Ingresa al menos {CLIENT_SEARCH_MIN_LENGTH} caracteres para buscar.
        </p>
      )}
    </Card>
  )
}
