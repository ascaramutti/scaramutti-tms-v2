import { useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import type { ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from '../utils/cn'

interface ModalProps {
  isOpen: boolean
  onClose: () => void
  title: string
  children: ReactNode
  size?: 'sm' | 'md' | 'lg'
}

const SIZES: Record<NonNullable<ModalProps['size']>, string> = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
}

const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

/**
 * Dialog modal reutilizable. A11y: `role="dialog"` + `aria-modal`, etiquetado
 * por el título, foco inicial al primer campo, focus-trap con Tab, cierre por
 * Escape y backdrop, y restauración del foco al elemento que lo abrió.
 *
 * Se monta con un portal en `document.body`, no donde se lo invoca: los modales
 * de creación al vuelo se abren DESDE un formulario, y anidado quedaría un
 * `<form>` dentro de otro, que es HTML inválido.
 *
 * El portal no alcanza para aislarlo: los eventos de React burbujean por el
 * árbol de componentes, no por el DOM, así que el submit del form de adentro
 * igual llegaría al de afuera. Por eso el panel también corta la propagación.
 */
export function Modal({ isOpen, onClose, title, children, size = 'md' }: ModalProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const previousFocus = useRef<HTMLElement | null>(null)
  // onClose suele ser un arrow inline del padre (ref nueva por render). Lo leemos
  // vía ref para que el efecto de foco/teclado dependa solo de `isOpen` y no se
  // re-ejecute en cada render del padre (lo que robaba el foco a media escritura).
  const onCloseRef = useRef(onClose)
  const titleId = useId()

  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  useEffect(() => {
    if (!isOpen) return
    previousFocus.current = document.activeElement as HTMLElement | null

    function handleKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || !panelRef.current) return
      const focusables = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusables.length === 0) return
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKey)
    // Foco inicial al primer campo (input/select/textarea), NO al botón de cerrar:
    // así un Espacio tipeado no activa ese botón ni cierra el modal por accidente.
    const panel = panelRef.current
    const firstField = panel?.querySelector<HTMLElement>('input, select, textarea')
    ;(firstField ?? panel?.querySelector<HTMLElement>(FOCUSABLE))?.focus()

    return () => {
      document.removeEventListener('keydown', handleKey)
      previousFocus.current?.focus()
    }
  }, [isOpen])

  if (!isOpen) return null

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="fixed inset-0 bg-overlay/60" onClick={onClose} aria-hidden="true" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onSubmit={(event) => event.stopPropagation()}
        // El filete NO es decorativo: en modo oscuro es lo único que separa la tarjeta del
        // velo. El velo apaga el fondo, que en claro basta para que la tarjeta blanca salte
        // (4.83), pero sobre un fondo ya oscuro no puede (1.25): ahí el límite lo pone este
        // borde (1.74). Los tres salen de componer el velo a la opacidad que este archivo
        // aplica y sobre el LIENZO, que es donde se dibuja, y los calcula la prueba del tema
        // leyendo esa opacidad de acá: mientras eran prosa estuvieron mal en tres archivos a
        // la vez, cada uno de una forma distinta.
        className={cn('relative w-full rounded-xl border border-border bg-surface shadow-xl', SIZES[size])}
      >
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <h2 id={titleId} className="text-lg font-semibold text-fg">
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Cerrar"
            className="text-fg-subtle hover:text-fg-body focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>
        <div className="px-6 py-4">{children}</div>
      </div>
    </div>,
    document.body,
  )
}
