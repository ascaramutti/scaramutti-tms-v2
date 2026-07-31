package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de la vista unificada de flota (GET /fleet-units). Vive en
 * {@code shared/repository/} por convencion del proyecto (mismo criterio que
 * {@link WarehouseKardexRepository}). Une en codigo los tres subtipos DISYUNTOS
 * ({@code public.tractors}, {@code public.trailers}, {@code public.escort_vehicles})
 * con un {@code UNION ALL}; el supertipo fisico {@code fleet_units} llega en la fase
 * flota/RRHH.
 *
 * <p>Las carretas ({@code trailers}) NO tienen columnas {@code brand}/{@code model}, asi
 * que su rama emite {@code NULL::varchar}. El filtro {@code kind} incluye solo la(s) rama(s)
 * pedida(s) (omitido = las tres); {@code isActive} filtra por subtipo. Orden {@code kind},
 * {@code plate} ASC (el frontend reordena para presentacion). Read-only.
 *
 * <p>La disponibilidad sale del catalogo {@code public.resource_statuses}, unido DENTRO de
 * cada rama: una sola consulta para todo el listado, sin una query por unidad. Las escoltas
 * no participan de la asignacion de viajes y su rama emite el estado en {@code NULL}, aunque
 * la tabla tenga la columna.
 */
@ApplicationScoped
public class FleetUnitRepository {

    @Inject
    EntityManager entityManager;

    public List<FleetUnitRow> search(FleetUnitKind kind, Boolean isActive) {
        Map<String, Object> params = new LinkedHashMap<>();
        String activeFilter = "";
        if (isActive != null) {
            activeFilter = " WHERE unit.is_active = :isActive";
            params.put("isActive", isActive);
        }
        // Alias uniforme (unit) en las tres ramas: el filtro de activas se arma una vez y el
        // JOIN del catalogo de estados no vuelve ambiguo el is_active.
        String statusJoin = " JOIN public.resource_statuses status ON status.id = unit.status_id";

        List<String> branches = new ArrayList<>();
        if (kind == null || kind == FleetUnitKind.TRACTOR) {
            branches.add("SELECT 'TRACTOR' AS kind, unit.id, unit.plate, unit.brand, unit.model, "
                + "status.name AS status_name, unit.is_active "
                + "FROM public.tractors unit" + statusJoin + activeFilter);
        }
        if (kind == null || kind == FleetUnitKind.TRAILER) {
            branches.add("SELECT 'TRAILER' AS kind, unit.id, unit.plate, NULL::varchar AS brand, "
                + "NULL::varchar AS model, status.name AS status_name, unit.is_active "
                + "FROM public.trailers unit" + statusJoin + activeFilter);
        }
        if (kind == null || kind == FleetUnitKind.ESCORT) {
            branches.add("SELECT 'ESCORT' AS kind, unit.id, unit.plate, unit.brand, unit.model, "
                + "NULL::varchar AS status_name, unit.is_active "
                + "FROM public.escort_vehicles unit" + activeFilter);
        }

        String sql = "SELECT * FROM ( " + String.join(" UNION ALL ", branches)
            + " ) fleet ORDER BY kind ASC, plate ASC";

        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        params.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream()
            .map(t -> new FleetUnitRow(
                (String) t.get(0),
                ((Number) t.get(1)).intValue(),
                (String) t.get(2),
                (String) t.get(3),
                (String) t.get(4),
                (String) t.get(5),
                (Boolean) t.get(6)))
            .toList();
    }

    /**
     * Proyeccion de una fila de la union; {@code kind} viaja como String (literal de cada rama)
     * y {@code statusName} como el nombre crudo del catalogo (null en las escoltas). Los dos
     * los traduce a enum de dominio el mapper.
     */
    public record FleetUnitRow(
        String kind,
        Integer id,
        String plate,
        String brand,
        String model,
        String statusName,
        Boolean isActive
    ) {}
}
