package com.scaramutti.tms.workers.mapper;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Mapper de la capa Service: Worker (entity) a WorkerResponse. fullName no es un
 * campo de la entity sino el nombre compuesto que resuelve {@link Worker#fullName()}.
 *
 * <p>El cargo sale de la descripcion del rol y ya no de una columna de texto del
 * trabajador: una sola jerarquia, la de roles. El mapeo se declara a mano porque el
 * nombre del campo de origen ya no coincide con el del destino.
 */
@Mapper(config = SharedMapperConfig.class)
public interface WorkerServiceMapper {

    @Mapping(target = "fullName", expression = "java(worker.fullName())")
    @Mapping(target = "position", source = "role.description")
    WorkerResponse toWorkerResponse(Worker worker);

    List<WorkerResponse> toWorkerResponseList(List<Worker> workers);
}
