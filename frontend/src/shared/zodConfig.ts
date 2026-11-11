import { z } from 'zod'

// La CSP no deja compilar código, y zod lo intenta igual: el intento queda como violación en la consola.
z.config({ jitless: true })
