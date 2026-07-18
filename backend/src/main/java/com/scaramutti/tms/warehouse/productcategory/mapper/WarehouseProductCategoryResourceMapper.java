package com.scaramutti.tms.warehouse.productcategory.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.productcategory.dto.WarehouseProductCategoryRequest;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.CreateWarehouseProductCategoryCommand;
import com.scaramutti.tms.warehouse.productcategory.service.cmd.ListWarehouseProductCategoriesQuery;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST: traduce parametros/body HTTP a Queries/Commands del service.
 *
 * name/description SIN uppercase (a diferencia de cargo-types): los seeds de
 * categorias (Repuestos, Lubricantes...) son Title Case y el contrato no pide
 * normalizar casing, solo unicidad case-insensitive (ver
 * ProductCategoryRepository.existsByNameIgnoreCase + V003).
 */
@Mapper(
    config = SharedMapperConfig.class,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehouseProductCategoryResourceMapper {

    ListWarehouseProductCategoriesQuery toListWarehouseProductCategoriesQuery(Boolean isActive);

    @Mapping(target = "name",        source = "name",        qualifiedByName = "trimToNull")
    @Mapping(target = "description", source = "description", qualifiedByName = "trimToNull")
    CreateWarehouseProductCategoryCommand toCreateWarehouseProductCategoryCommand(WarehouseProductCategoryRequest warehouseProductCategoryRequest);
}
