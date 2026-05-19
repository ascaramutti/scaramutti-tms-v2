package com.scaramutti.tms.cargotypes.service;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.mapper.CargoTypeServiceMapper;
import com.scaramutti.tms.cargotypes.service.cmd.ListCargoTypesQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class CargoTypeService {

    @Inject CargoTypeRepository cargoTypeRepository;
    @Inject CargoTypeServiceMapper cargoTypeServiceMapper;

    /**
     * Listado paginado con busqueda fuzzy opcional. Read-only, no requiere
     * @Transactional (Quarkus abre tx implicita si Hibernate la necesita).
     *
     * El Query viene ya normalizado desde el CargoTypeResourceMapper
     * (q trimmed + uppercased). El service solo orquesta entre repo y mapper.
     */
    public PageResponse<CargoTypeResponse> listCargoTypes(ListCargoTypesQuery listCargoTypesQuery) {
        List<CargoType> cargoTypes = cargoTypeRepository.searchPaged(listCargoTypesQuery);
        long totalElements = cargoTypeRepository.countSearch(listCargoTypesQuery);

        List<CargoTypeResponse> content = cargoTypeServiceMapper.toCargoTypeResponseList(cargoTypes);
        return PageResponse.of(content, listCargoTypesQuery.page(), listCargoTypesQuery.size(), totalElements);
    }
}
