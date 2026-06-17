import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Guard de regresión RN-03 / ADR-003: `internalNote` NUNCA debe llegar a un canal cara-al-cliente.
 * Hoy el único canal cara-al-cliente del front es el PDF (lo genera el backend; el front solo
 * manda el `id`). Este test documenta el invariante del lado front:
 *  - la barra de acciones y el flujo de PDF del front no referencian `internalNote`;
 *  - no hay `dangerouslySetInnerHTML` en `src` (la defensa XSS es escape-on-output de JSX).
 * El cierre estructural real (excluir el campo del documento) vive en el backend.
 */

// vitest corre con cwd = raíz del frontend (no hay `root` custom en la config).
const SRC_ROOT = join(process.cwd(), 'src')
const FEATURE_ROOT = join(SRC_ROOT, 'features', 'quotations')

function read(relativeToFeature: string): string {
  return readFileSync(join(FEATURE_ROOT, relativeToFeature), 'utf8')
}

/** Todos los archivos .ts/.tsx bajo `dir` (recursivo). */
function collectSourceFiles(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      out.push(...collectSourceFiles(full))
    } else if (full.endsWith('.ts') || full.endsWith('.tsx')) {
      out.push(full)
    }
  }
  return out
}

describe('RN-03 guard — internalNote nunca en canal cara-al-cliente', () => {
  it('el flujo de PDF del front no referencia internalNote', () => {
    expect(read('utils/quotationPdf.ts')).not.toContain('internalNote')
    expect(read('hooks/useQuotationPdf.ts')).not.toContain('internalNote')
  })

  it('la barra de acciones del detalle no referencia internalNote', () => {
    expect(read('components/QuotationDetailActions.tsx')).not.toContain('internalNote')
  })

  it('no hay uso de dangerouslySetInnerHTML en todo src (defensa XSS = escape-on-output)', () => {
    // Detecta el USO real (prop JSX `dangerouslySetInnerHTML={...}` o `:`), no las menciones
    // en comentarios/docstrings (los componentes de notas documentan la regla). Este propio
    // archivo se excluye: contiene el patrón como detector, no como uso.
    const USAGE = /dangerouslySetInnerHTML\s*[=:]/
    const offenders = collectSourceFiles(SRC_ROOT)
      .filter((file) => !file.endsWith('internalNote-guard.test.ts'))
      .filter((file) => USAGE.test(readFileSync(file, 'utf8')))
    expect(offenders).toEqual([])
  })
})
