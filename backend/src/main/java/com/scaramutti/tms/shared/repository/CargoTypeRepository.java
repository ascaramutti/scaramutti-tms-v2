package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.cargotypes.service.cmd.ListCargoTypesQuery;
import com.scaramutti.tms.shared.entity.CargoType;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de tipos de carga. Aunque vive en `shared/repository/` por convencion
 * del proyecto, es cargo-type-specific (no generico) — acepta tipos del modulo
 * cargotypes (`ListCargoTypesQuery`) sin generar un Criteria intermedio.
 *
 * Mismo patron que ClientRepository: native query con ILIKE substring +
 * similarity() para ranking.
 */
@ApplicationScoped
public class CargoTypeRepository implements PanacheRepositoryBase<CargoType, Integer> {

    @Inject
    EntityManager entityManager;

    /**
     * Busca tipos de carga paginados aplicando filtros opcionales.
     *
     *  - q: si != null → ILIKE substring match (`%q%`) contra `name`. Recall
     *    completo (matchea cualquier posicion) — comportamiento esperado en
     *    un autocomplete. El GIN gin_trgm_ops `idx_cargo_types_name_trgm`
     *    acelera ILIKE con wildcards en ambos lados.
     *    El ORDER BY usa `similarity(name, q)` para que los matches mas
     *    "parecidos" aparezcan arriba.
     *    Diferencia con clients: solo busca en `name` (no hay equivalente al ruc).
     *    NOTA: si en el futuro se agrega un segundo campo searchable (ej. `code`),
     *    replicar el patron de ClientRepository: `WHERE (name ILIKE :qLike OR
     *    code ILIKE :qLike)` + `ORDER BY GREATEST(similarity(name,:qRank),
     *    similarity(code,:qRank)) DESC, name ASC`.
     *  - isActive: si != null → filtro exacto en is_active.
     *  - Sin q: ORDER BY name ASC (consistente con catalogos_ordering_policy).
     *
     * El `q` viene ya trimmed + uppercased desde el CargoTypeResourceMapper.
     */
    public List<CargoType> searchPaged(ListCargoTypesQuery listCargoTypesQuery) {
        String q = listCargoTypesQuery.q();
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(q, listCargoTypesQuery.isActive(), params);
        String orderBy = (q != null)
            ? "ORDER BY similarity(name, :qRank) DESC, name ASC"
            : "ORDER BY name ASC";

        String sql = "SELECT * FROM cargo_types " + where + " " + orderBy
            + " LIMIT :pageSize OFFSET :pageOffset";

        Query query = entityManager.createNativeQuery(sql, CargoType.class);
        params.forEach(query::setParameter);
        if (q != null) {
            // Param adicional para el similarity() del ORDER BY (no compartido con
            // countSearch, que no tiene ORDER BY).
            query.setParameter("qRank", q);
        }
        query.setParameter("pageSize", listCargoTypesQuery.size());
        query.setParameter("pageOffset", (long) listCargoTypesQuery.page() * listCargoTypesQuery.size());

        @SuppressWarnings("unchecked")
        List<CargoType> result = query.getResultList();
        return result;
    }

    /**
     * Misma WHERE que searchPaged. Helper privado `buildWhere` evita drift
     * entre search y count cuando se agregan nuevos filtros.
     */
    public long countSearch(ListCargoTypesQuery listCargoTypesQuery) {
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(listCargoTypesQuery.q(), listCargoTypesQuery.isActive(), params);
        String sql = "SELECT COUNT(*) FROM cargo_types " + where;

        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);

        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Construye la clausula WHERE con los filtros opcionales y rellena el
     * mapa de named params. Side-effect deliberado para que search y count
     * compartan exactamente el mismo predicado.
     */
    private String buildWhere(String q, Boolean isActive, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (q != null) {
            // ILIKE substring match (caso autocomplete). El GIN index
            // idx_cargo_types_name_trgm acelera ILIKE con patrones >= 3 chars.
            conditions.add("name ILIKE :qLike");
            params.put("qLike", "%" + q + "%");
        }
        if (isActive != null) {
            conditions.add("is_active = :isActive");
            params.put("isActive", isActive);
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }
}
