interface SpinnerProps {
  /** Tamaño en pixeles. Default 18. */
  size?: number
  /** Etiqueta accesible para lectores de pantalla. Default "Cargando". */
  label?: string
}

// Spinner CSS-only. Usa la animacion `tms-spin` declarada en src/index.css.
export function Spinner({ size = 18, label = 'Cargando' }: SpinnerProps) {
  return (
    <span
      role="status"
      aria-live="polite"
      aria-label={label}
      style={{
        display: 'inline-block',
        width: size,
        height: size,
        border: `2px solid currentColor`,
        borderTopColor: 'transparent',
        borderRadius: '50%',
        animation: 'tms-spin 0.6s linear infinite',
        verticalAlign: 'middle',
      }}
    />
  )
}
