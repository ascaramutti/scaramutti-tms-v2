package com.scaramutti.tms.sharedcatalogs.worker.mapper;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Mapper de la capa Service: Worker (entity) a WorkerResponse. fullName no es un
 * campo de la entity sino el nombre compuesto que resuelve {@link Worker#fullName()}.
 */
@Mapper(config = SharedMapperConfig.class)
public interface WorkerServiceMapper {

    @Mapping(target = "fullName", expression = "java(worker.fullName())")
    WorkerResponse toWorkerResponse(Worker worker);

    List<WorkerResponse> toWorkerResponseList(List<Worker> workers);
}
