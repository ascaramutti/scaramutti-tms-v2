package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Driver;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.util.List;

/**
 * Repositorio del catalogo de conductores (GET /drivers). Vive en {@code shared/repository/}
 * por convencion del proyecto: {@code public.drivers} es de v1 y no tiene modulo dueno en v2.
 * El id es {@code Integer} (la tabla de v1 es SERIAL, no BIGSERIAL).
 *
 * <p>El listado no devuelve entidades: el nombre sale de {@code public.workers} y la
 * disponibilidad de {@code public.resource_statuses}, asi que la consulta los une y proyecta
 * una fila plana. Un solo query para todo el listado. Orden natural por nombre ASC (el
 * frontend reordena para presentacion, politica de catalogos). Read-only.
 */
@ApplicationScoped
public class DriverRepository implements PanacheRepositoryBase<Driver, Integer> {

    public List<DriverRow> search(Boolean isActive) {
        // El nombre completo se arma en SQL (mismo criterio que Worker#fullName: nombre,
        // espacio, apellido) para poder ordenar por las columnas que lo forman.
        String sql = "SELECT d.id, trim(w.first_name || ' ' || w.last_name) AS full_name, "
            + "d.license_number, d.category, w.phone, status.name AS status_name, d.is_active "
            + "FROM public.drivers d "
            + "JOIN public.workers w ON w.id = d.worker_id "
            + "JOIN public.resource_statuses status ON status.id = d.status_id "
            + (isActive != null ? "WHERE d.is_active = :isActive " : "")
            + "ORDER BY w.first_name ASC, w.last_name ASC";

        Query query = getEntityManager().createNativeQuery(sql, Tuple.class);
        if (isActive != null) {
            query.setParameter("isActive", isActive);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream()
            .map(t -> new DriverRow(
                ((Number) t.get(0)).intValue(),
                (String) t.get(1),
                (String) t.get(2),
                (String) t.get(3),
                (String) t.get(4),
                (String) t.get(5),
                (Boolean) t.get(6)))
            .toList();
    }

    /**
     * Proyeccion de una fila del listado; {@code statusName} es el nombre crudo del catalogo,
     * que traduce a enum de dominio el mapper.
     */
    public record DriverRow(
        Integer id,
        String fullName,
        String licenseNumber,
        String licenseCategory,
        String phone,
        String statusName,
        Boolean isActive
    ) {}
}
