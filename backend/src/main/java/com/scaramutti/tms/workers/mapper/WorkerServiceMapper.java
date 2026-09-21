package com.scaramutti.tms.workers.mapper;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.repository.WorkerRepository.DocumentTypeRow;
import com.scaramutti.tms.shared.repository.WorkerRepository.DriverProfileRow;
import com.scaramutti.tms.shared.repository.WorkerRepository.RoleRow;
import com.scaramutti.tms.shared.repository.WorkerRepository.UserRefRow;
import com.scaramutti.tms.shared.repository.WorkerRepository.WorkerDetailRow;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import com.scaramutti.tms.workers.dto.DocumentTypeResponse;
import com.scaramutti.tms.workers.dto.RoleResponse;
import com.scaramutti.tms.workers.dto.WorkerDetailResponse;
import com.scaramutti.tms.workers.dto.WorkerDriverProfileResponse;
import com.scaramutti.tms.workers.dto.WorkerUserRef;
import com.scaramutti.tms.workers.model.DriverProfileMode;
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

    /**
     * El detalle, desde la fila que devuelve la consulta.
     *
     * <p>Los cuatro sub-objetos se declaran a mano aunque el generador los deduciria: con la
     * politica de destino sin origen en error, declararlos hace que el compilador verifique
     * cada uno por separado y que el error senale el record culpable en vez de la raiz.
     */
    WorkerDetailResponse toWorkerDetailResponse(WorkerDetailRow workerDetailRow);

    DocumentTypeResponse toDocumentTypeResponse(DocumentTypeRow documentTypeRow);

    RoleResponse toRoleResponse(RoleRow roleRow);

    WorkerUserRef toWorkerUserRef(UserRefRow userRefRow);

    @Mapping(target = "status", source = "statusName")
    WorkerDriverProfileResponse toWorkerDriverProfileResponse(DriverProfileRow driverProfileRow);

    /** El texto de la columna al enum del modulo; un valor fuera del dominio revienta. */
    default DriverProfileMode toDriverProfileMode(String driverProfile) {
        return DriverProfileMode.fromColumn(driverProfile);
    }

    /** El nombre del catalogo de disponibilidad al enum de la API, igual que en conductores. */
    default FleetResourceStatus toFleetResourceStatus(String catalogName) {
        return FleetResourceStatus.fromCatalogName(catalogName);
    }
}
