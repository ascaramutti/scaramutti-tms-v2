package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.shared.repository.QuotationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Generador del `code` de cotizacion en formato `YYYY-NNNNN`.
 *
 * Algoritmo (lineamiento #1):
 *  1. Determinar el anio actual (UTC).
 *  2. Adquirir advisory lock por anio dentro de la tx
 *     (`SELECT pg_advisory_xact_lock(year)`).
 *  3. Leer MAX(numero) parseando SUBSTRING(code) del anio actual.
 *  4. Devolver `format("%d-%05d", year, max + 1)`.
 *  5. El lock se libera automaticamente al commit/rollback de la tx.
 *
 * El reinicio anual del contador es automatico porque cada anio tiene su
 * propio dominio de codes (`2026-%`, `2027-%`, ...). Cuando llega 2027 y
 * MAX devuelve 0 (no hay cotizaciones del 2027 todavia), la primera sera
 * `2027-00001`.
 *
 * CRITICAL: este servicio debe llamarse DENTRO de una transaccion activa
 * (el advisory lock es de transaccion, no de sesion). El CreateQuotationService
 * lo garantiza con `@Transactional`.
 */
@ApplicationScoped
public class QuotationCodeGeneratorService {

    @Inject
    QuotationRepository quotationRepository;

    /**
     * Devuelve el siguiente code disponible para el anio en curso (UTC).
     */
    public String nextCode() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        return nextCodeForYear(year);
    }

    /**
     * Variante testeable: permite inyectar el anio. Usado en unit tests.
     */
    public String nextCodeForYear(int year) {
        quotationRepository.acquireYearLock(year);
        int max = quotationRepository.maxCodeNumberForYear(year);
        return String.format("%d-%05d", year, max + 1);
    }
}
