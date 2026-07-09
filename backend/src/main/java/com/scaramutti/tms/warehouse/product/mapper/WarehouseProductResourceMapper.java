package com.scaramutti.tms.warehouse.product.mapper;

import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductRequest;
import com.scaramutti.tms.warehouse.product.service.cmd.CreateWarehouseProductCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mapper de la capa REST. Normaliza los campos de texto (trim, "" → null) y
 * aplica los defaults de los campos ausentes:
 *  - name/brand/partNumber: {@code trimToNull}. A diferencia de proveedores
 *    (razón social en mayúsculas), el nombre de producto NO se uppercasea: el
 *    índice {@code uq_products_identity} usa {@code lower(name)} y la búsqueda
 *    es trgm sobre el valor guardado tal cual.
 *  - attributes: null → {} (JSONB NOT NULL en BD).
 *  - minStock: null → 0 (default del contrato).
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.CDI,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehouseProductResourceMapper {

    @Mapping(target = "name",         source = "name",         qualifiedByName = "trimToNull")
    @Mapping(target = "brand",        source = "brand",        qualifiedByName = "trimToNull")
    @Mapping(target = "partNumber",   source = "partNumber",   qualifiedByName = "trimToNull")
    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    @Mapping(target = "attributes",
             expression = "java(defaultAttributes(warehouseProductRequest.attributes()))")
    @Mapping(target = "minStock",
             expression = "java(warehouseProductRequest.minStock() != null ? warehouseProductRequest.minStock() : java.math.BigDecimal.ZERO)")
    CreateWarehouseProductCommand toCreateWarehouseProductCommand(WarehouseProductRequest warehouseProductRequest);

    /** Copia defensiva y default {}: attributes es JSONB NOT NULL en BD. */
    default Map<String, String> defaultAttributes(Map<String, String> attributes) {
        return attributes != null ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();
    }
}
