package com.scaramutti.tms.warehouse.openingbalance.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.openingbalance.dto.WarehouseOpeningBalanceRequest;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.CreateWarehouseOpeningBalanceCommand;
import com.scaramutti.tms.warehouse.openingbalance.service.cmd.ListWarehouseOpeningBalancesQuery;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST. Normaliza {@code observations} (trim, "" → null),
 * mismo criterio que WarehouseProductResourceMapper.
 *
 * {@code nullValueMappingStrategy = RETURN_DEFAULT}: sin esto, MapStruct trata
 * el único parámetro de tipo objeto ({@code productId}, filtro OPCIONAL de
 * {@code toListWarehouseOpeningBalancesQuery}) como "la" fuente y genera un
 * early-return completo del método cuando llega null (sin filtro) — tirando
 * abajo page/size también. Mismo criterio que WarehouseProductResourceMapper.
 */
@Mapper(
    config = SharedMapperConfig.class,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehouseOpeningBalanceResourceMapper {

    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateWarehouseOpeningBalanceCommand toCreateWarehouseOpeningBalanceCommand(
        WarehouseOpeningBalanceRequest warehouseOpeningBalanceRequest
    );

    ListWarehouseOpeningBalancesQuery toListWarehouseOpeningBalancesQuery(Integer productId, int page, int size);
}
