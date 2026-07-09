package com.scaramutti.tms.warehouse.supplier.mapper;

import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierRequest;
import com.scaramutti.tms.warehouse.supplier.service.cmd.CreateWarehouseSupplierCommand;
import com.scaramutti.tms.warehouse.supplier.service.cmd.ListWarehouseSuppliersQuery;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST. Mismo criterio que ClientResourceMapper (proveedor
 * = entidad de negocio, razón social en mayúsculas, igual que clientes):
 *  - name: trim + uppercase, "" → null (se almacena en mayúsculas en BD).
 *  - contactName: trim, "" → null (nombre de persona, NO uppercase).
 *  - q (listing): trim + uppercase — similarity() del ranking es
 *    case-sensitive y name se almacena en mayúsculas.
 *  - ruc, phone: pasan tal cual — Bean Validation (@Pattern) ya garantiza
 *    el formato exacto.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.CDI,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehouseSupplierResourceMapper {

    @Mapping(target = "name",        source = "name",        qualifiedByName = "trimUpperOrNull")
    @Mapping(target = "contactName", source = "contactName", qualifiedByName = "trimToNull")
    CreateWarehouseSupplierCommand toCreateWarehouseSupplierCommand(WarehouseSupplierRequest warehouseSupplierRequest);

    @Mapping(target = "q", source = "q", qualifiedByName = "trimUpperOrNull")
    ListWarehouseSuppliersQuery toListWarehouseSuppliersQuery(String q, Boolean isActive, int page, int size);
}
