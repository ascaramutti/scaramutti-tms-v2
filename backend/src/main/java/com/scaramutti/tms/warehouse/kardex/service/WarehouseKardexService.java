package com.scaramutti.tms.warehouse.kardex.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.service.UserLookup;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository.EntradaReferenceView;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository.KardexMovementRow;
import com.scaramutti.tms.shared.repository.WarehouseKardexRepository.SalidaReferenceView;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.kardex.dto.WarehouseKardexMovementResponse;
import com.scaramutti.tms.warehouse.kardex.model.WarehouseKardexMovementType;
import com.scaramutti.tms.warehouse.kardex.service.cmd.GetWarehouseKardexQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kardex de un producto (GET /warehouse/products/{id}/kardex): lee la VIEW
 * {@code almacen.stock_movements} con el saldo corrido calculado server-side
 * sobre la historia completa (RN-WH13, CRITICO — ver {@link WarehouseKardexRepository}).
 * Read-only, sin {@code @Transactional} (misma convencion que
 * {@code WarehouseProductService.listProducts}).
 *
 * <p>5 queries fijas por pagina, ninguna N+1 por page size: (1) existencia del
 * producto, (2) pagina de movimientos con balance, (3) count, (4) referencias
 * de ENTRADA (facturas+proveedor), (5) referencias de SALIDA (trabajador+placa).
 * El batch de {@code registeredBy} lo resuelve {@link UserLookup#requireAllById}
 * en una 6ª query solo si la pagina no esta vacia.
 */
@ApplicationScoped
public class WarehouseKardexService {

    private static final String APERTURA_REFERENCE = "Apertura de inventario";

    @Inject ProductRepository productRepository;
    @Inject WarehouseKardexRepository warehouseKardexRepository;
    @Inject UserLookup userLookup;

    public PageResponse<WarehouseKardexMovementResponse> getKardex(GetWarehouseKardexQuery query) {
        productRepository.findByIdOptional(query.productId())
            .orElseThrow(WarehouseError.PRODUCT_NOT_FOUND::toException);

        List<KardexMovementRow> rows = warehouseKardexRepository.findPaged(query);
        long totalElements = warehouseKardexRepository.countMatching(query);

        if (rows.isEmpty()) {
            return PageResponse.of(List.of(), query.page(), query.size(), totalElements);
        }

        List<Integer> entradaIds = rows.stream()
            .filter(row -> "ENTRADA".equals(row.movementType()))
            .map(KardexMovementRow::sourceId)
            .toList();
        List<Integer> salidaIds = rows.stream()
            .filter(row -> "SALIDA".equals(row.movementType()))
            .map(KardexMovementRow::sourceId)
            .toList();

        Map<Integer, EntradaReferenceView> entradaReferences = warehouseKardexRepository.findEntradaReferences(entradaIds);
        Map<Integer, SalidaReferenceView> salidaReferences = warehouseKardexRepository.findSalidaReferences(salidaIds);
        Map<Integer, UserResponse> usersById = userLookup.requireAllById(
            rows.stream().map(KardexMovementRow::registeredBy).collect(Collectors.toSet()));

        List<WarehouseKardexMovementResponse> content = rows.stream()
            .map(row -> toResponse(row, entradaReferences, salidaReferences, usersById))
            .toList();

        return PageResponse.of(content, query.page(), query.size(), totalElements);
    }

    private WarehouseKardexMovementResponse toResponse(
        KardexMovementRow row,
        Map<Integer, EntradaReferenceView> entradaReferences,
        Map<Integer, SalidaReferenceView> salidaReferences,
        Map<Integer, UserResponse> usersById
    ) {
        WarehouseKardexMovementType movementType = WarehouseKardexMovementType.valueOf(row.movementType());
        return new WarehouseKardexMovementResponse(
            movementType,
            row.quantity(),
            row.balance(),
            row.movedAt(),
            row.sourceId(),
            reference(movementType, row.sourceId(), entradaReferences, salidaReferences),
            usersById.get(row.registeredBy())
        );
    }

    /**
     * Etiqueta legible es-PE por tipo de movimiento (el backend la compone,
     * el frontend NO arma texto de negocio):
     *  - APERTURA: constante, sin lookup (sourceId siempre null).
     *  - ENTRADA:  "Factura &lt;invoiceNumber&gt; · &lt;proveedor&gt;".
     *  - SALIDA:   "Retiro · recibe &lt;worker&gt;[ · &lt;placa&gt;]" — el segmento
     *              de placa se omite con gracia si el retiro no tuvo unidad
     *              (los 3 FK null, RN-WH2: la unidad es opcional).
     */
    private String reference(
        WarehouseKardexMovementType movementType, Integer sourceId,
        Map<Integer, EntradaReferenceView> entradaReferences,
        Map<Integer, SalidaReferenceView> salidaReferences
    ) {
        return switch (movementType) {
            case APERTURA -> APERTURA_REFERENCE;
            case ENTRADA -> {
                EntradaReferenceView ref = entradaReferences.get(sourceId);
                yield "Factura " + ref.invoiceNumber() + " · " + ref.supplierName();
            }
            case SALIDA -> {
                SalidaReferenceView ref = salidaReferences.get(sourceId);
                yield ref.plate() != null
                    ? "Retiro · recibe " + ref.workerFullName() + " · " + ref.plate()
                    : "Retiro · recibe " + ref.workerFullName();
            }
        };
    }
}
