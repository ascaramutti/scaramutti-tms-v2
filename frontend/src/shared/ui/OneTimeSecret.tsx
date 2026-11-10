import { useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Copy } from 'lucide-react'
import { Button } from './Button'

interface OneTimeSecretProps {
  label: string
  value: string | null
  /** Avisa al padre que la página se ocultó, para que saque el secreto de su estado. */
  onExpire: () => void
  /** Cada cuántos caracteres se separa a la vista; lo copiado va sin espacios. */
  groupSize?: number
  notice?: string
  expiredNotice?: string
}

const NOTICE = 'No se vuelve a mostrar. Cópiala ahora y entrégala a la persona en privado; se escribe sin espacios.'
const EXPIRED_NOTICE = 'Ya no se muestra. Si no la copiaste, restablécela desde el detalle de la cuenta.'

type CopyStatus = 'idle' | 'copied' | 'manual'

const STATUS_MESSAGE: Record<CopyStatus, string> = {
  idle: '',
  copied: 'Copiada al portapapeles.',
  manual: 'Cópiala a mano: ya está seleccionada (Ctrl+C).',
}

const COPIED_MS = 3000

export function OneTimeSecret({
  label,
  value,
  onExpire,
  groupSize = 4,
  notice = NOTICE,
  expiredNotice = EXPIRED_NOTICE,
}: OneTimeSecretProps) {
  const labelId = useId()
  const valueRef = useRef<HTMLSpanElement>(null)
  const onExpireRef = useRef(onExpire)
  const [expired, setExpired] = useState(false)
  const [status, setStatus] = useState<CopyStatus>('idle')
  const shown = expired ? null : value

  useEffect(() => {
    onExpireRef.current = onExpire
  }, [onExpire])

  useEffect(() => {
    if (shown === null) return
    // Fuera de un evento de React el borrado se pintaría en otra tarea, después de que la caché de
    // ida y vuelta congele la página con el secreto a la vista.
    function expire() {
      flushSync(() => {
        setExpired(true)
        onExpireRef.current()
      })
    }
    window.addEventListener('pagehide', expire)
    return () => window.removeEventListener('pagehide', expire)
  }, [shown])

  useEffect(() => {
    if (status !== 'copied') return
    const timer = setTimeout(() => setStatus('idle'), COPIED_MS)
    return () => clearTimeout(timer)
  }, [status])

  if (shown === null) {
    return <p className="text-sm text-fg-body">{expiredNotice}</p>
  }

  async function copy(secret: string) {
    if (await writeToClipboard(secret)) {
      setStatus('copied')
      return
    }
    selectContents(valueRef.current)
    setStatus(copySelection() ? 'copied' : 'manual')
  }

  return (
    <div className="space-y-3">
      <p id={labelId} className="text-sm font-medium text-fg">
        {label}
      </p>
      <div className="flex flex-wrap items-center gap-3">
        {/* Los grupos van en línea: en una caja flexible, la selección copiada llevaría saltos de línea. */}
        <span ref={valueRef} className="space-x-2 font-mono text-lg text-fg">
          {groupsOf(shown, groupSize).map((group, index) => (
            <span key={index}>{group}</span>
          ))}
        </span>
        <Button variant="secondary" aria-describedby={labelId} onClick={() => void copy(shown)}>
          <Copy className="mr-2 h-4 w-4" aria-hidden="true" />
          {status === 'copied' ? 'Copiada' : 'Copiar'}
        </Button>
      </div>
      <p className="text-sm text-fg-body">{notice}</p>
      <p role="status" className="text-sm text-fg-body">
        {STATUS_MESSAGE[status]}
      </p>
    </div>
  )
}

function groupsOf(text: string, size: number): string[] {
  const groups: string[] = []
  for (let start = 0; start < text.length; start += size) {
    groups.push(text.slice(start, start + size))
  }
  return groups
}

async function writeToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

function selectContents(node: Node | null) {
  const selection = window.getSelection()
  if (!node || !selection) return
  const range = document.createRange()
  range.selectNodeContents(node)
  selection.removeAllRanges()
  selection.addRange(range)
}

// Sin portapapeles (contexto no seguro) queda el comando viejo, que copia lo seleccionado.
function copySelection(): boolean {
  return typeof document.execCommand === 'function' && document.execCommand('copy')
}
