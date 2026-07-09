package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Product;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

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
}
