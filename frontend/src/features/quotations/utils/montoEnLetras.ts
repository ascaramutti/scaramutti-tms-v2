const UNIDADES = ['cero', 'uno', 'dos', 'tres', 'cuatro', 'cinco', 'seis', 'siete', 'ocho', 'nueve']
const DIEZ_QUINCE = ['diez', 'once', 'doce', 'trece', 'catorce', 'quince']
const DIECISEIS_DIECINUEVE = ['dieciséis', 'diecisiete', 'dieciocho', 'diecinueve']
const VEINTIDOS_VEINTINUEVE = [
  'veintidós',
  'veintitrés',
  'veinticuatro',
  'veinticinco',
  'veintiséis',
  'veintisiete',
  'veintiocho',
  'veintinueve',
]
const DECENAS = ['', '', 'veinte', 'treinta', 'cuarenta', 'cincuenta', 'sesenta', 'setenta', 'ochenta', 'noventa']
const CENTENAS = [
  '',
  'ciento',
  'doscientos',
  'trescientos',
  'cuatrocientos',
  'quinientos',
  'seiscientos',
  'setecientos',
  'ochocientos',
  'novecientos',
]

/** 0-99 a letras. `apocopar`: "uno" → "un" (antes de "mil"/"millones" o un sustantivo). */
function dosDigitos(n: number, apocopar: boolean): string {
  if (n === 0) return ''
  if (n < 10) return n === 1 ? (apocopar ? 'un' : 'uno') : UNIDADES[n]
  if (n < 16) return DIEZ_QUINCE[n - 10]
  if (n < 20) return DIECISEIS_DIECINUEVE[n - 16]
  if (n === 20) return 'veinte'
  if (n === 21) return apocopar ? 'veintiún' : 'veintiuno'
  if (n < 30) return VEINTIDOS_VEINTINUEVE[n - 22]
  const decena = DECENAS[Math.floor(n / 10)]
  const unidad = n % 10
  if (unidad === 0) return decena
  return `${decena} y ${unidad === 1 ? (apocopar ? 'un' : 'uno') : UNIDADES[unidad]}`
}

/** 0-999 a letras. */
function tresDigitos(n: number, apocopar: boolean): string {
  if (n === 0) return ''
  if (n === 100) return 'cien'
  const centena = CENTENAS[Math.floor(n / 100)]
  const resto = dosDigitos(n % 100, apocopar)
  return [centena, resto].filter(Boolean).join(' ')
}

/** 0-999.999 (miles + cientos) a letras. `apocopar`: "uno"→"un" al final del período. */
function periodoALetras(n: number, apocopar: boolean): string {
  const miles = Math.floor(n / 1000)
  const cientos = n % 1000
  const partes: string[] = []
  if (miles > 0) {
    partes.push(miles === 1 ? 'mil' : `${tresDigitos(miles, true)} mil`)
  }
  if (cientos > 0) {
    partes.push(tresDigitos(cientos, apocopar))
  }
  return partes.join(' ')
}

/** Parte entera a letras, por períodos (billón / millón / unidades), cada uno 0-999.999.
 * Cubre todo el rango de enteros seguros de JS (hasta ~9·10^15), holgado sobre los topes del
 * schema. En español (escala larga) 10^9 = "mil millones" y 10^12 = "un billón". */
function enteroALetras(n: number): string {
  if (n === 0) return 'cero'
  const billones = Math.floor(n / 1_000_000_000_000)
  const restoBillon = n % 1_000_000_000_000
  const millones = Math.floor(restoBillon / 1_000_000)
  const resto = restoBillon % 1_000_000
  const partes: string[] = []
  // Cada grupo se apocopa antes de su escala ("veintiún millones", "un billón").
  if (billones > 0) {
    partes.push(billones === 1 ? 'un billón' : `${periodoALetras(billones, true)} billones`)
  }
  if (millones > 0) {
    partes.push(millones === 1 ? 'un millón' : `${periodoALetras(millones, true)} millones`)
  }
  if (resto > 0) {
    partes.push(periodoALetras(resto, false))
  }
  return partes.join(' ')
}

/** Nombre de la moneda en plural (es-PE). */
function nombreMoneda(currencyCode: string): string {
  switch (currencyCode) {
    case 'USD':
      return 'dólares americanos'
    case 'PEN':
      return 'soles'
    default:
      return currencyCode
  }
}

/**
 * Monto en letras al estilo de los comprobantes peruanos:
 * `montoEnLetras(4770, 'USD')` → "Cuatro mil setecientos setenta con 00/100 dólares americanos".
 * Los centavos van como fracción /100. Soporta enteros hasta el rango seguro de JS
 * (~9·10^15; escala larga es-PE).
 */
export function montoEnLetras(amount: number, currencyCode: string): string {
  const safe = Number.isFinite(amount) && amount >= 0 ? amount : 0
  let entero = Math.floor(safe)
  let centavos = Math.round((safe - entero) * 100)
  if (centavos === 100) {
    entero += 1
    centavos = 0
  }
  const letras = enteroALetras(entero)
  const fraccion = String(centavos).padStart(2, '0')
  const frase = `${letras} con ${fraccion}/100 ${nombreMoneda(currencyCode)}`
  return frase.charAt(0).toUpperCase() + frase.slice(1)
}
