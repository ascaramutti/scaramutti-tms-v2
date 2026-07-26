package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import com.scaramutti.tms.warehouse.product.service.cmd.ListWarehouseProductsQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de productos. Vive en {@code shared/repository/} por convencion
 * del proyecto (mismo criterio que SupplierRepository/ProductCategoryRepository).
 *
 * Unicidad respaldada en BD:
 *  - identidad compuesta CI {@code uq_products_identity} (V002): lower(name) +
 *    lower(COALESCE(brand,'')) + lower(COALESCE(part_number,'')).
 *  - SKU {@code uq_products_code_ci} (V005): lower(code).
 * Los {@code existsBy...} cubren el happy path; la race la traduce el catch de
 * {@code persistOrTranslateDuplicate} en el service.
 */
@ApplicationScoped
public class ProductRepository implements PanacheRepositoryBase<Product, Integer> {

    @Inject
    EntityManager entityManager;

    /**
     * Identidad compuesta case-insensitive, espejo de {@code uq_products_identity}.
     * Normaliza los opcionales null→"" en Java para alinear con el COALESCE del
     * indice (dos genericos sin marca/parte cuentan como el mismo "sin valor").
     */
    public boolean existsByIdentityIgnoreCase(String name, String brand, String partNumber) {
        String brandKey = brand == null ? "" : brand;
        String partNumberKey = partNumber == null ? "" : partNumber;
        return count(
            "lower(name) = lower(?1) "
            + "and lower(coalesce(brand, '')) = lower(?2) "
            + "and lower(coalesce(partNumber, '')) = lower(?3)",
            name, brandKey, partNumberKey
        ) > 0;
    }

    /**
     * Identidad compuesta case-insensitive EXCLUYENDO el propio id (PUT): mismo
     * WHERE que {@link #existsByIdentityIgnoreCase} + {@code and id <> ?4}. El
     * de creacion no excluye id porque en un alta el producto todavia no existe;
     * en una edicion sin cambio de identidad, no excluir daria un 409 espurio
     * contra si mismo.
     */
    public boolean existsByIdentityIgnoreCaseExcludingId(String name, String brand, String partNumber, Integer id) {
        String brandKey = brand == null ? "" : brand;
        String partNumberKey = partNumber == null ? "" : partNumber;
        return count(
            "lower(name) = lower(?1) "
            + "and lower(coalesce(brand, '')) = lower(?2) "
            + "and lower(coalesce(partNumber, '')) = lower(?3) "
            + "and id <> ?4",
            name, brandKey, partNumberKey, id
        ) > 0;
    }

    /**
     * Advisory lock por-transaccion que serializa la generacion del SKU
     * correlativo (mismo mecanismo que {@code QuotationRepository.acquireYearLock},
     * aca con clave fija porque el contador de productos no se particiona).
     *
     * CRITICAL: debe llamarse DENTRO de una tx activa (el lock es de tx).
     */
    public void acquireProductCodeLock() {
        entityManager.createNativeQuery(
            "SELECT pg_advisory_xact_lock(hashtext('almacen.products.code')::bigint)"
        ).getSingleResult();
    }

    /**
     * Lock exclusivo por-fila del producto ({@code SELECT ... FOR UPDATE}) para SERIALIZAR el
     * check-and-insert de stock: el stock es una VIEW derivada (no lockeable), pero todo
     * movimiento del producto referencia la fila real {@code almacen.products(id)}. Al tomar el
     * lock al inicio de la tx, dos transacciones sobre el MISMO producto se serializan (la 2da
     * bloquea hasta el COMMIT de la 1ra), así que al releer el stock ya ve el movimiento
     * committeado: el retiro no puede pasar el chequeo de stock por una race (RN-WH2, no
     * best-effort). Productos distintos no se estorban. Además del alta de retiros, lo reusan
     * las guardas de stock de la edición y la anulación de entradas.
     *
     * <p>Correcto bajo READ COMMITTED (el default de Postgres/Quarkus): el INSERT del movimiento
     * no modifica la fila lockeada, pero al liberarse el lock la 2da tx re-snapshotea en su
     * siguiente statement y lee el stock ya committeado. Bajo REPEATABLE READ NO alcanzaría (el
     * snapshot viejo persistiría): el módulo asume READ COMMITTED.
     *
     * <p>CRITICAL: debe llamarse DENTRO de una tx activa (el lock es de tx).
     */
    public void lockProductRow(Integer productId) {
        entityManager.createNativeQuery("SELECT id FROM almacen.products WHERE id = :id FOR UPDATE")
            .setParameter("id", productId)
            .getSingleResult();
    }

    /**
     * MAX del sufijo numerico de los codes autogenerados ({@code PRO-NNNN}).
     * El filtro {@code ~ '^PRO-[0-9]{1,9}$'} excluye los SKU provistos por el
     * usuario con otro formato (un cast de "PRO-abc" reventaria) y acota a 9
     * digitos: asi el {@code CAST(... AS INTEGER)} nunca desborda int32 (max
     * 999.999.999 < 2.147.483.647), aun si alguien ingresa manualmente un code
     * "PRO-" larguisimo (el @Column length=30 lo permitiria). Esos codes >9
     * digitos quedan fuera de la secuencia autogenerada, que es lo correcto.
     * {@code SUBSTRING(code FROM 5)} salta el prefijo "PRO-". Si no hay ninguno,
     * devuelve 0.
     */
    public int maxProductCodeNumber() {
        Object result = entityManager.createNativeQuery(
            "SELECT COALESCE(MAX(CAST(SUBSTRING(code FROM 5) AS INTEGER)), 0) "
            + "FROM almacen.products WHERE code ~ '^PRO-[0-9]{1,9}$'"
        ).getSingleResult();
        return ((Number) result).intValue();
    }

    // ---------- Listado paginado (GET /warehouse/products) --------------------

    /**
     * Página de productos que matchean los filtros, hidratando la ENTITY
     * {@code Product} ({@code SELECT p.*}) — así {@code attributes} (JSONB) lo
     * mapea Hibernate sin parseo manual. Los datos de presentación que NO están
     * en la tabla (nombre de categoría, code/nombre de unidad, stock, lowStock,
     * createdBy) los batch-loadea el service, sin N+1 por page size.
     *
     * <p>{@code q} (multi-palabra, RN-WH14 via {@link MultiWordSearch}): cada
     * palabra debe matchear en ALGÚN campo (name/code/brand/partNumber) — AND de
     * ORs, ILIKE case-insensitive. El ranking usa {@code similarity(lower(...))}
     * sobre el {@code q} completo (recall y relevancia son cosas distintas, mismo
     * criterio que SupplierRepository). {@code lowOnly} lee {@code low_stock} de
     * la VIEW {@code product_stock} (definición canónica de RN-WH11, JOIN 1:1).
     */
    public List<Product> searchPaged(ListWarehouseProductsQuery query) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT p.* " + fromAndWhere(query, params) + " "
            + orderBy(query.q()) + " LIMIT :pageSize OFFSET :pageOffset";

        Query nativeQuery = entityManager.createNativeQuery(sql, Product.class);
        params.forEach(nativeQuery::setParameter);
        if (query.q() != null) {
            nativeQuery.setParameter("qRank", query.q());
        }
        nativeQuery.setParameter("pageSize", query.size());
        nativeQuery.setParameter("pageOffset", (long) query.page() * query.size());

        @SuppressWarnings("unchecked")
        List<Product> result = nativeQuery.getResultList();
        return result;
    }

    /** Total de productos que matchean los filtros. Reusa el MISMO FROM+WHERE que searchPaged. */
    public long countSearch(ListWarehouseProductsQuery query) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT COUNT(*) " + fromAndWhere(query, params);

        Query nativeQuery = entityManager.createNativeQuery(sql);
        params.forEach(nativeQuery::setParameter);
        return ((Number) nativeQuery.getSingleResult()).longValue();
    }

    /**
     * Stock actual y lowStock de una página de productos, leídos de la VIEW
     * {@code product_stock} (RN-WH1: nunca persistido; RN-WH11: lowStock definido
     * UNA vez en la VIEW). Indexado por productId. 1 query, sin N+1.
     */
    public Map<Integer, ProductStockView> findStockByProductIds(Collection<Integer> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Query nativeQuery = entityManager.createNativeQuery(
            "SELECT product_id, stock, low_stock FROM almacen.product_stock WHERE product_id IN :ids",
            Tuple.class
        ).setParameter("ids", productIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = nativeQuery.getResultList();
        Map<Integer, ProductStockView> byId = new LinkedHashMap<>();
        for (Tuple row : rows) {
            byId.put(
                ((Number) row.get(0)).intValue(),
                new ProductStockView((BigDecimal) row.get(1), (Boolean) row.get(2))
            );
        }
        return byId;
    }

    private String fromAndWhere(ListWarehouseProductsQuery query, Map<String, Object> params) {
        // El JOIN a la VIEW solo se agrega cuando lowOnly lo necesita (1:1 por producto).
        String from = "FROM almacen.products p "
            + (query.lowOnly() ? "JOIN almacen.product_stock ps ON ps.product_id = p.id " : "");

        List<String> conditions = new ArrayList<>();
        if (query.q() != null) {
            conditions.addAll(MultiWordSearch.conditions(
                query.q(), List.of("p.name", "p.code", "p.brand", "p.part_number"), "qTok", params));
        }
        if (query.categoryId() != null) {
            conditions.add("p.category_id = :categoryId");
            params.put("categoryId", query.categoryId());
        }
        if (query.isActive() != null) {
            conditions.add("p.is_active = :isActive");
            params.put("isActive", query.isActive());
        }
        if (query.lowOnly()) {
            conditions.add("ps.low_stock");
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return from + where;
    }

    private String orderBy(String q) {
        // similarity() case-insensitive (lower en ambos lados: name se guarda tal cual).
        // COALESCE de los nullables → 0 de similarity, no NULL, para un orden determinista.
        return (q != null)
            ? "ORDER BY GREATEST("
                + "similarity(lower(p.name), lower(:qRank)), "
                + "similarity(lower(COALESCE(p.code, '')), lower(:qRank)), "
                + "similarity(lower(COALESCE(p.brand, '')), lower(:qRank)), "
                + "similarity(lower(COALESCE(p.part_number, '')), lower(:qRank))) DESC, p.name ASC"
            : "ORDER BY p.name ASC";
    }

    /**
     * Stock actual y lowStock de UN producto, leído de la VIEW {@code product_stock}
     * (mismo criterio que {@link #findStockByProductIds}). Devuelve {@code null} si
     * la fila no existe (no debería, LEFT JOIN sobre todos los productos); el caller
     * ({@code WarehouseProductService.currentStockOf}) resuelve ese {@code null} con
     * su fallback defensivo, por eso acá no hace falta un {@code Optional}.
     */
    public ProductStockView findStockByProductId(Integer id) {
        return findStockByProductIds(java.util.Set.of(id)).get(id);
    }

    /** Proyección de la VIEW product_stock: stock actual + lowStock (RN-WH11), ambos derivados. */
    public record ProductStockView(BigDecimal stock, boolean lowStock) {}
}
