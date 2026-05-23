package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Quotation;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Repositorio de cotizaciones. Aunque vive en `shared/repository/`, es
 * quotation-specific. Expone helpers especificos del modulo Quotations.
 *
 * Metodos:
 *  - acquireYearLock: pg_advisory_xact_lock por anio para serializar la
 *    generacion del `code` dentro de la tx.
 *  - acquireAntiDuplicateLock: pg_advisory_xact_lock por (createdBy, clientId)
 *    para serializar el check anti-duplicado y cerrar la ventana TOCTOU.
 *    Usa la variante de dos int (firma distinta a acquireYearLock → no colisiona).
 *  - maxCodeNumberForYear: parsea el numero del code (parte despues del
 *    guion) y devuelve el maximo del anio. Devuelve 0 si no hay cotizaciones
 *    en ese anio (caso primer dia del anio).
 *  - findRecentByCreatedByAndClient: cotizaciones del mismo creador y cliente
 *    creadas en los ultimos N segundos. La compatibilidad de los `serviceTypeId`
 *    se valida en el service (requiere comparar items, no header).
 */
@ApplicationScoped
public class QuotationRepository implements PanacheRepositoryBase<Quotation, Long> {

    @Inject
    EntityManager entityManager;

    /**
     * Adquiere un advisory lock por anio dentro de la tx actual. Se libera
     * automaticamente al commit/rollback. Si otra tx esta dentro del lock,
     * esta llamada bloquea hasta que se libere.
     *
     * Uso: dentro de la tx del POST /quotations, llamar antes de calcular
     * el siguiente code para serializar la generacion entre requests.
     */
    public void acquireYearLock(int year) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:year)")
            .setParameter("year", (long) year)
            .getSingleResult();
    }

    /**
     * Adquiere un advisory lock por la combinacion (createdBy, clientId) dentro
     * de la tx actual. Usado para cerrar la ventana TOCTOU del anti-duplicado:
     * dos POST simultaneos del mismo usuario+cliente quedan serializados, asi
     * el segundo VE persistido al primero antes de hacer el check.
     *
     * Usa la variante {@code pg_advisory_xact_lock(int, int)}, que vive en un
     * namespace distinto al {@code (bigint)} usado por {@link #acquireYearLock(int)}.
     */
    public void acquireAntiDuplicateLock(Integer createdBy, Integer clientId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:k1, :k2)")
            .setParameter("k1", createdBy)
            .setParameter("k2", clientId)
            .getSingleResult();
    }

    /**
     * Devuelve el MAX(numero) parseado del code para un anio dado.
     * El code tiene formato `YYYY-NNNNN`; SUBSTRING(code, 6) extrae los
     * 5 caracteres del numero secuencial.
     *
     * Si no hay cotizaciones del anio, devuelve 0 (el caller suma 1 → primera
     * cotizacion del anio sera `YYYY-00001`).
     */
    public int maxCodeNumberForYear(int year) {
        Object result = entityManager.createNativeQuery(
            "SELECT COALESCE(MAX(CAST(SUBSTRING(code FROM 6) AS INTEGER)), 0) " +
            "FROM cotizaciones.quotations WHERE code LIKE :prefix"
        ).setParameter("prefix", year + "-%").getSingleResult();
        return ((Number) result).intValue();
    }

    /**
     * Detecta duplicados recientes para proteccion anti doble-click backend-side.
     * Devuelve cotizaciones del mismo creador, mismo cliente, creadas hace
     * menos de `secondsWindow` segundos. El service compara los serviceTypeIds
     * (que requieren join con items) sobre el resultado.
     */
    public List<Quotation> findRecentByCreatedByAndClient(Integer createdBy, Integer clientId, int secondsWindow) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(secondsWindow);
        return list("createdBy = ?1 AND clientId = ?2 AND createdAt >= ?3", createdBy, clientId, cutoff);
    }
}
