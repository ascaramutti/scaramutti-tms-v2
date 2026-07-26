package com.scaramutti.tms.warehouse.product.service;

import com.scaramutti.tms.shared.repository.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Generador del SKU correlativo de producto en formato {@code PRO-NNNN}
 * (RN-WH10). Mismo algoritmo que {@code QuotationCodeGeneratorService}, sin
 * partición por año (el contador de productos es global):
 *  1. Adquirir advisory lock de la tx que serializa la generación.
 *  2. Leer MAX(sufijo numérico) de los codes {@code PRO-%} ya existentes.
 *  3. Devolver {@code format("PRO-%04d", max + 1)}.
 *  4. El lock se libera al commit/rollback de la tx.
 *
 * CRITICAL: debe llamarse DENTRO de una transacción activa (el advisory lock
 * es de transacción, no de sesión). El {@code WarehouseProductService} lo
 * garantiza con {@code @Transactional}.
 */
@ApplicationScoped
public class WarehouseProductCodeGeneratorService {

    @Inject
    ProductRepository productRepository;

    /** Devuelve el siguiente SKU correlativo disponible. */
    public String nextCode() {
        productRepository.acquireProductCodeLock();
        int max = productRepository.maxProductCodeNumber();
        return String.format("PRO-%04d", max + 1);
    }
}
