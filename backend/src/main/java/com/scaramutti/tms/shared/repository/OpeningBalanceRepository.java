package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.OpeningBalance;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.ListWarehouseOpeningBalancesQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.List;

/**
 * Repositorio de aperturas de inventario. Vive en {@code shared/repository/}
 * por convención del proyecto (mismo criterio que ProductRepository).
 *
 * Unicidad respaldada en BD: {@code product_id} UNIQUE (V002, inline). El
 * {@code existsByProductId} cubre el happy path; la race la traduce el catch
 * de {@code persistOrTranslateDuplicate} en el service.
 */
@ApplicationScoped
public class OpeningBalanceRepository implements PanacheRepositoryBase<OpeningBalance, Integer> {

    @Inject
    EntityManager entityManager;

    /** Pre-check happy path del UNIQUE {@code product_id} (WH-009). */
    public boolean existsByProductId(Integer productId) {
        return count("productId = ?1", productId) > 0;
    }

    /**
     * Producto con movimientos de ENTRADA o SALIDA ya registrados (WH-011): una
     * apertura posterior falsearía el saldo corrido. Se consulta la VIEW
     * {@code stock_movements} (no las tablas base) porque ya filtra
     * {@code status='ACTIVE'}: un movimiento ANULADO (CANCELLED) no bloquea la
     * apertura, consistente con cómo el kardex excluye los anulados. APERTURA se
     * excluye explícitamente del IN (la cubre WH-009, no esta validación).
     */
    public boolean existsActiveMovementsForProduct(Integer productId) {
        Query nativeQuery = entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM almacen.stock_movements "
                + "WHERE product_id = :productId AND movement_type IN ('ENTRADA', 'SALIDA')"
        ).setParameter("productId", productId);
        return ((Number) nativeQuery.getSingleResult()).longValue() > 0;
    }

    // ---------- Listado paginado (GET /warehouse/opening-balances) ------------

    /** Página de aperturas que matchean el filtro, ordenada por registeredAt DESC (más reciente primero). */
    public List<OpeningBalance> searchPaged(ListWarehouseOpeningBalancesQuery query) {
        StringBuilder jpql = new StringBuilder("from OpeningBalance ob ");
        appendFilter(jpql, query.productId());
        jpql.append("order by ob.registeredAt desc");

        var typedQuery = entityManager.createQuery(jpql.toString(), OpeningBalance.class);
        if (query.productId() != null) {
            typedQuery.setParameter("productId", query.productId());
        }
        return typedQuery
            .setFirstResult(query.page() * query.size())
            .setMaxResults(query.size())
            .getResultList();
    }

    /** Total de aperturas que matchean el filtro. Reusa el MISMO WHERE que searchPaged. */
    public long countSearch(ListWarehouseOpeningBalancesQuery query) {
        StringBuilder jpql = new StringBuilder("select count(ob) from OpeningBalance ob ");
        appendFilter(jpql, query.productId());

        var typedQuery = entityManager.createQuery(jpql.toString(), Long.class);
        if (query.productId() != null) {
            typedQuery.setParameter("productId", query.productId());
        }
        return typedQuery.getSingleResult();
    }

    private void appendFilter(StringBuilder jpql, Integer productId) {
        if (productId != null) {
            jpql.append("where ob.productId = :productId ");
        }
    }
}
