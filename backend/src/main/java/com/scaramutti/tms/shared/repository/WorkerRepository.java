package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class WorkerRepository implements PanacheRepositoryBase<Worker, Integer> {

    /**
     * Listado (sin paginar) de {@code public.workers} para el combobox "quien recibe" del
     * retiro (GET /workers). {@code q} es multi-palabra (RN-WH14, molde suppliers/products):
     * cada palabra debe matchear en {@code first_name} O {@code last_name}; nulo = sin filtro.
     * {@code isActive} nulo = ambos. Orden natural {@code first_name, last_name} ASC (el
     * frontend reordena para presentacion, politica de catalogos). Query nativa para poder
     * usar {@link MultiWordSearch}; devuelve entidades gestionadas.
     */
    public List<Worker> search(String q, Boolean isActive) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>();
        if (q != null) {
            conditions.addAll(MultiWordSearch.conditions(q, List.of("first_name", "last_name"), "qtok", params));
        }
        if (isActive != null) {
            conditions.add("is_active = :isActive");
            params.put("isActive", isActive);
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + " ";
        String sql = "SELECT * FROM public.workers " + where + "ORDER BY first_name ASC, last_name ASC";

        Query query = getEntityManager().createNativeQuery(sql, Worker.class);
        params.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<Worker> result = query.getResultList();
        return result;
    }
}
