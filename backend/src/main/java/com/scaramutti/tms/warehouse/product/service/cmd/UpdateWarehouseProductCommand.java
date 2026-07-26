package com.scaramutti.tms.warehouse.product.service.cmd;

import java.math.BigDecimal;
import java.util.Map;

/**
 * name/brand/partNumber/observations ya vienen trim()-eados y cadena vacía →
 * null (el ResourceMapper normaliza). {@code attributes} nunca es null (default
 * {} en el mapper) y {@code minStock} nunca es null (default 0). {@code isActive}
 * tampoco: null → true (default del contrato; el PUT es un replace del objeto
 * completo). Sin {@code unitOfMeasureId}: inmutable tras crear (P-1).
 */
public record UpdateWarehouseProductCommand(
    String name,
    Integer categoryId,
    String brand,
    String partNumber,
    Map<String, String> attributes,
    BigDecimal minStock,
    String observations,
    Boolean isActive
) {}
