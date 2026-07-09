package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.entity.Supplier_;
import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.supplier.service.cmd.ListWarehouseSuppliersQuery;
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
 * Repositorio de proveedores. Vive en {@code shared/repository/} por
 * convencion del proyecto (mismo criterio que ClientRepository/CargoTypeRepository).
 *
 * RN-WH14 (busqueda multi-palabra): a diferencia de ClientRepository/
 * QuotationRepository (que hacen un solo ILIKE %q% de la cadena completa),
 * acá `q` se TOKENIZA por espacios y cada palabra debe matchear en ALGUN
 * campo (name O ruc) — un AND de ORs, no un OR de la cadena completa. Es una
 * decision explicita del dueno para evitar la deuda de busqueda multi-palabra
 * que quedo pendiente en cotizaciones.
 */
@ApplicationScoped
public class SupplierRepository implements PanacheRepositoryBase<Supplier, Integer> {

    @Inject
    EntityManager entityManager;

    public boolean existsByNameIgnoreCase(String name) {
        return count("lower(" + Supplier_.NAME + ") = lower(?1)", name) > 0;
    }

    public boolean existsByRucIgnoreCase(String ruc) {
        return count("lower(" + Supplier_.RUC + ") = lower(?1)", ruc) > 0;
    }

    /**
     * Busca proveedores paginados. `q` (si viene) tokenizado por espacios:
     * cada token agrega su propia condicion `(name ILIKE OR ruc ILIKE)` al
     * WHERE, todas unidas por AND — asi "repuestos diesel" matchea un
     * proveedor con "Repuestos" en el name y "diesel" en cualquier lado
     * (incluido el ruc), sin exigir que ambas palabras esten en el mismo campo.
     *
     * El ranking del ORDER BY usa similarity() sobre el `q` COMPLETO (sin
     * tokenizar) contra name/ruc — mismo criterio que ClientRepository: el
     * filtro (recall) y el ranking (relevancia) son cosas distintas.
     *
     * `q` llega ya trimmed+uppercased desde el ResourceMapper — el repo no
     * normaliza, solo tokeniza por espacios.
     */
    public List<Supplier> searchPaged(ListWarehouseSuppliersQuery listWarehouseSuppliersQuery) {
        String q = listWarehouseSuppliersQuery.q();
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(q, listWarehouseSuppliersQuery.isActive(), params);
        String orderBy = (q != null)
            ? "ORDER BY GREATEST(similarity(name, :qRank), similarity(COALESCE(ruc, ''), :qRank)) DESC, name ASC"
            : "ORDER BY name ASC";

        String sql = "SELECT * FROM almacen.suppliers " + where + " " + orderBy
            + " LIMIT :pageSize OFFSET :pageOffset";

        Query query = entityManager.createNativeQuery(sql, Supplier.class);
        params.forEach(query::setParameter);
        if (q != null) {
            query.setParameter("qRank", q);
        }
        query.setParameter("pageSize", listWarehouseSuppliersQuery.size());
        query.setParameter("pageOffset", (long) listWarehouseSuppliersQuery.page() * listWarehouseSuppliersQuery.size());

        @SuppressWarnings("unchecked")
        List<Supplier> result = query.getResultList();
        return result;
    }

    /** Misma WHERE que searchPaged — evita drift del predicado entre search y count. */
    public long countSearch(ListWarehouseSuppliersQuery listWarehouseSuppliersQuery) {
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(listWarehouseSuppliersQuery.q(), listWarehouseSuppliersQuery.isActive(), params);
        String sql = "SELECT COUNT(*) FROM almacen.suppliers " + where;

        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);

        return ((Number) query.getSingleResult()).longValue();
    }

    private String buildWhere(String q, Boolean isActive, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (q != null) {
            String[] tokens = tokenize(q);
            for (int i = 0; i < tokens.length; i++) {
                String paramName = "qTok" + i;
                conditions.add("(name ILIKE :" + paramName + " ESCAPE '\\' OR ruc ILIKE :" + paramName + " ESCAPE '\\')");
                params.put(paramName, "%" + StringUtils.escapeLikeWildcards(tokens[i]) + "%");
            }
        }
        if (isActive != null) {
            conditions.add("is_active = :isActive");
            params.put("isActive", isActive);
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    /**
     * Tokeniza `q` por espacios, descartando tokens vacios (espacios
     * multiples/al borde no generan condiciones vacias que rompan el AND).
     * Publico y estatico para poder testearlo sin levantar Postgres.
     */
    public static String[] tokenize(String q) {
        String[] rawTokens = q.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens.toArray(new String[0]);
    }
}
