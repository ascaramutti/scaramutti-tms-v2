/**
 * Dispara la descarga de un blob como archivo con el nombre dado.
 *
 * Vive en `shared/` porque lo usan el PDF de cotizaciones y el CSV de reportes:
 * el mecanismo (crear un `<a download>`, clickearlo y liberar el object URL) no
 * tiene nada del dominio de ninguno de los dos.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    // Siempre liberar el object URL, aunque algún paso intermedio falle.
    URL.revokeObjectURL(url)
  }
}
