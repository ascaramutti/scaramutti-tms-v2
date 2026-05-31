import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { Plus, Search, X } from 'lucide-react'
import { cn } from '../utils/cn'
import { Spinner } from './Spinner'

export interface ComboboxOption {
  id: number
  label: string
  sublabel?: string
}

interface ComboboxProps {
  id: string
  label?: ReactNode
  placeholder?: string
  /** Opciones YA filtradas por el backend (este componente NO filtra localmente). */
  options: ComboboxOption[]
  selected: ComboboxOption | null
  /** El consumer debouncea el texto y dispara la búsqueda async. */
  onQueryChange: (query: string) => void
  onSelect: (option: ComboboxOption) => void
  onClear: () => void
  onBlur?: () => void
  loading?: boolean
  /** Mínimo de caracteres antes de abrir el dropdown / buscar. Default 0. */
  minChars?: number
  minCharsHint?: string
  emptyText?: string
  error?: string
  createLabel?: string
  onCreateClick?: (query: string) => void
  disabled?: boolean
}

/**
 * Combobox de búsqueda async genérico. No filtra internamente: recibe las
 * opciones ya filtradas por el backend y notifica el texto vía `onQueryChange`
 * (respeta el `q` minLength del servidor). Teclado (↑/↓/Enter/Escape),
 * click-outside y a11y (combobox/listbox/option).
 */
export function Combobox({
  id,
  label,
  placeholder = 'Buscar…',
  options,
  selected,
  onQueryChange,
  onSelect,
  onClear,
  onBlur,
  loading,
  minChars = 0,
  minCharsHint,
  emptyText = 'No se encontraron resultados.',
  error,
  createLabel,
  onCreateClick,
  disabled,
}: ComboboxProps) {
  const [query, setQuery] = useState('')
  const [isOpen, setIsOpen] = useState(false)
  const [highlighted, setHighlighted] = useState(-1)
  const containerRef = useRef<HTMLDivElement>(null)
  const listboxId = useId()
  const hintId = useId()
  const errorId = useId()

  const meetsMin = query.trim().length >= minChars
  const showHint = query.trim().length > 0 && !meetsMin && !!minCharsHint
  const hasCreate = !!createLabel && !!onCreateClick && query.trim().length > 0
  const open = isOpen && !selected && meetsMin
  const itemCount = options.length + (hasCreate ? 1 : 0)

  const handleClickOutside = useCallback((event: MouseEvent) => {
    if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
      setIsOpen(false)
    }
  }, [])
  useEffect(() => {
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [handleClickOutside])

  function handleQuery(value: string) {
    setQuery(value)
    onQueryChange(value)
    setIsOpen(value.trim().length >= minChars)
    setHighlighted(0)
  }

  function select(option: ComboboxOption) {
    onSelect(option)
    setQuery('')
    setIsOpen(false)
    setHighlighted(-1)
  }

  // Al quitar la selección, resetea el texto tipeado para que el input no reaparezca
  // con la búsqueda vieja (cubre el caso de creación al vuelo, que no pasa por select()).
  function clearSelection() {
    setQuery('')
    setIsOpen(false)
    setHighlighted(-1)
    onClear()
  }

  function handleKeyDown(event: KeyboardEvent) {
    if (!open) {
      if ((event.key === 'ArrowDown' || event.key === 'Enter') && meetsMin) {
        setIsOpen(true)
        setHighlighted(0)
        event.preventDefault()
      }
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setHighlighted((prev) => Math.min(prev + 1, itemCount - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setHighlighted((prev) => Math.max(prev - 1, 0))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      if (highlighted >= 0 && highlighted < options.length) {
        select(options[highlighted])
      } else if (hasCreate && highlighted === options.length && onCreateClick) {
        onCreateClick(query)
        setIsOpen(false)
      }
    } else if (event.key === 'Escape') {
      event.preventDefault()
      setIsOpen(false)
      setHighlighted(-1)
    }
  }

  const describedBy = cn(showHint && hintId, error && errorId) || undefined

  return (
    <div>
      {label && (
        <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-slate-700">
          {label}
        </label>
      )}
      <div ref={containerRef} className="relative">
        {selected ? (
          <div
            className={cn(
              'flex items-center justify-between rounded-lg border bg-slate-50 px-3.5 py-2.5',
              error ? 'border-red-300' : 'border-slate-300',
            )}
          >
            <div>
              <p className="text-sm font-medium text-slate-900">{selected.label}</p>
              {selected.sublabel && <p className="text-xs text-slate-500">{selected.sublabel}</p>}
            </div>
            {!disabled && (
              <button
                type="button"
                onClick={clearSelection}
                aria-label="Quitar selección"
                className="text-slate-400 hover:text-slate-600"
              >
                <X className="h-4 w-4" aria-hidden="true" />
              </button>
            )}
          </div>
        ) : (
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
              aria-hidden="true"
            />
            <input
              id={id}
              type="text"
              role="combobox"
              aria-expanded={open}
              aria-controls={listboxId}
              aria-invalid={!!error}
              aria-describedby={describedBy}
              autoComplete="off"
              disabled={disabled}
              value={query}
              placeholder={placeholder}
              onChange={(event) => handleQuery(event.target.value)}
              onFocus={() => {
                if (meetsMin) setIsOpen(true)
              }}
              onBlur={onBlur}
              onKeyDown={handleKeyDown}
              className={cn(
                'w-full rounded-lg border bg-white py-2.5 pl-9 pr-9 text-sm text-slate-900 placeholder:text-slate-400 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500',
                error ? 'border-red-300' : 'border-slate-300',
              )}
            />
            {loading && (
              <Spinner
                size={16}
                label="Buscando"
                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400"
              />
            )}
          </div>
        )}

        {open && (
          <ul
            id={listboxId}
            role="listbox"
            className="absolute z-10 mt-1 max-h-60 w-full overflow-y-auto rounded-lg border border-slate-200 bg-white shadow-lg"
          >
            {options.map((option, index) => (
              <li key={option.id} role="option" aria-selected={index === highlighted}>
                <button
                  type="button"
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => select(option)}
                  onMouseEnter={() => setHighlighted(index)}
                  className={cn(
                    'w-full px-3.5 py-2.5 text-left text-sm hover:bg-slate-50',
                    index === highlighted && 'bg-slate-100',
                  )}
                >
                  <p className="font-medium text-slate-900">{option.label}</p>
                  {option.sublabel && <p className="text-xs text-slate-500">{option.sublabel}</p>}
                </button>
              </li>
            ))}
            {options.length === 0 && (
              <li className="px-3.5 py-3 text-sm text-slate-500">{emptyText}</li>
            )}
            {hasCreate && onCreateClick && (
              <li role="option" aria-selected={highlighted === options.length}>
                <button
                  type="button"
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => {
                    onCreateClick(query)
                    setIsOpen(false)
                  }}
                  onMouseEnter={() => setHighlighted(options.length)}
                  className={cn(
                    'flex w-full items-center gap-1.5 border-t border-slate-100 px-3.5 py-2.5 text-left text-sm font-medium text-blue-700 hover:bg-slate-50',
                    highlighted === options.length && 'bg-slate-100',
                  )}
                >
                  <Plus className="h-4 w-4" aria-hidden="true" />
                  {createLabel}
                </button>
              </li>
            )}
          </ul>
        )}
      </div>
      {showHint && (
        <p id={hintId} className="mt-1 text-xs text-slate-500">
          {minCharsHint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="mt-1.5 text-sm text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}
