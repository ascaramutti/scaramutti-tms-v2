package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.clients.service.cmd.ListClientsQuery;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.entity.Client_;
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
 * Repositorio de clientes. Aunque vive en `shared/repository/` por convencion
 * del proyecto, es client-specific (no generico) — por eso acepta tipos del
 * modulo clients (`ListClientsQuery`) sin generar un Criteria intermedio.
 */
@ApplicationScoped
public class ClientRepository implements PanacheRepositoryBase<Client, Integer> {

    @Inject
    EntityManager entityManager;

    public boolean existsByRuc(String ruc) {
        return count(Client_.RUC + " = ?1", ruc) > 0;
    }

    public boolean existsByName(String name) {
        return count(Client_.NAME + " = ?1", name) > 0;
    }

    /**
     * Busca clientes paginados aplicando filtros opcionales.
     *
     *  - q: si != null → ILIKE substring match (`%q%`) contra `name` y `ruc`.
     *    Recall completo (matchea cualquier posicion) — comportamiento esperado
     *    en un autocomplete (q=FERRE encuentra FERREYROS, q=DHL encuentra
     *    DHL EXPRESS). Los GIN gin_trgm_ops aceleran ILIKE con wildcards.
     *
     *    El ORDER BY usa `similarity(name,q)` / `similarity(ruc,q)` para que
     *    los matches mas "parecidos" aparezcan arriba (un FERREYROS exacto
     *    sobre un FERRECOM_OTHER). Asi combinamos recall (ILIKE) con
     *    relevancia (trgm ranking).
     *
     *    Trade-off: se pierde la tolerancia a typos del operador `%` puro
     *    (q="ACEM" ya no matchea ACME). Aceptado: usuarios escriben razones
     *    sociales que conocen, no buscan con errores ortograficos.
     *
     *  - isActive: si != null → filtro exacto en is_active.
     *  - Sin q: ORDER BY name ASC (consistente con catalogos_ordering_policy).
     *
     * Implementacion con native query porque `similarity()` no existe en JPQL.
     * Hibernate hidrata Client por nombre de columna (las @Column de la entity
     * matchean los nombres SQL).
     *
     * El `q` viene ya trimmed + uppercased desde el ClientResourceMapper —
     * el repo no normaliza.
     */
    public List<Client> searchPaged(ListClientsQuery listClientsQuery) {
        String q = listClientsQuery.q();
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(q, listClientsQuery.isActive(), params);
        String orderBy = (q != null)
            ? "ORDER BY GREATEST(similarity(name, :qRank), similarity(ruc, :qRank)) DESC, name ASC"
            : "ORDER BY name ASC";

        String sql = "SELECT * FROM clients " + where + " " + orderBy
            + " LIMIT :pageSize OFFSET :pageOffset";

        Query query = entityManager.createNativeQuery(sql, Client.class);
        params.forEach(query::setParameter);
        if (q != null) {
            // Param adicional para el similarity() del ORDER BY (no compartido con
            // countSearch, que no tiene ORDER BY).
            query.setParameter("qRank", q);
        }
        query.setParameter("pageSize", listClientsQuery.size());
        query.setParameter("pageOffset", (long) listClientsQuery.page() * listClientsQuery.size());

        @SuppressWarnings("unchecked")
        List<Client> result = query.getResultList();
        return result;
    }

    /**
     * Misma WHERE que searchPaged. Helper privado `buildWhere` evita drift
     * entre search y count cuando se agregan nuevos filtros.
     */
    public long countSearch(ListClientsQuery listClientsQuery) {
        Map<String, Object> params = new HashMap<>();
        String where = buildWhere(listClientsQuery.q(), listClientsQuery.isActive(), params);
        String sql = "SELECT COUNT(*) FROM clients " + where;

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
            // ILIKE con wildcards en ambos lados = substring match (caso autocomplete).
            // Los GIN indexes (idx_clients_name_trgm, idx_clients_ruc_trgm) aceleran
            // ILIKE cuando el operando contiene patrones >= 3 chars.
            // Se escapan los metacaracteres de LIKE (% _ \) del input para que un
            // q="100%" o "A_B" busque esos literales y no sobre-matchee (ESCAPE '\').
            // El similarity() del ORDER BY usa :qRank sin escapar — es fuzzy ranking,
            // no patron LIKE, los wildcards no le aplican.
            conditions.add("(name ILIKE :qLike ESCAPE '\\' OR ruc ILIKE :qLike ESCAPE '\\')");
            params.put("qLike", "%" + escapeLikeWildcards(q) + "%");
        }
        if (isActive != null) {
            conditions.add("is_active = :isActive");
            params.put("isActive", isActive);
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    /**
     * Escapa los metacaracteres de LIKE/ILIKE ({@code \ % _}) para que el input
     * de busqueda se trate como literal. El backslash primero (es el char de
     * escape, no debe duplicarse despues). Usado con {@code ESCAPE '\'} en el SQL.
     * (Mismo helper que {@code QuotationRepository.escapeLikeWildcards} — si
     * aparece un 3er repo con ILIKE, mover a un util compartido.)
     */
    private static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
