package com.scaramutti.tms.warehouse.product.service.cmd;

import java.math.BigDecimal;
import java.util.Map;

/**
 * name/brand/partNumber/observations ya vienen trim()-eados y cadena vacía →
 * null (el ResourceMapper normaliza). {@code attributes} nunca es null (default
 * {} en el mapper) y {@code minStock} nunca es null (default 0). {@code code}
 * (SKU) NO viaja: lo autogenera el backend. {@code isActive} tampoco: el POST
 * siempre crea activo.
 */
public record CreateWarehouseProductCommand(
    String name,
    Integer categoryId,
    Integer unitOfMeasureId,
    String brand,
    String partNumber,
    Map<String, String> attributes,
    BigDecimal minStock,
    String observations
) {}
