package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.workers.WorkersError;
import com.scaramutti.tms.workers.dto.WorkerDetailResponse;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.workers.mapper.WorkerServiceMapper;
import com.scaramutti.tms.workers.service.cmd.ListWorkersQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Listado de trabajadores (GET /workers), catalogo compartido {@code public.workers}
 * (de lectura: el alta y la edicion llegan con las historias siguientes). Sin
 * {@code @Transactional} (misma
 * convencion que los listados de catalogo de conductores y unidades). El filtro y el orden los resuelve
 * {@link WorkerRepository#search}; el shaping al response vive en el mapper.
 */
@ApplicationScoped
public class WorkerService {

    @Inject WorkerRepository workerRepository;
    @Inject WorkerDocumentSearchVisibility workerDocumentSearchVisibility;
    @Inject WorkerServiceMapper workerServiceMapper;

    public List<WorkerResponse> listWorkers(ListWorkersQuery query) {
        return workerServiceMapper.toWorkerResponseList(
            workerRepository.search(query.q(), query.isActive(),
                workerDocumentSearchVisibility.includeDocumentNumber())
        );
    }

    /**
     * El detalle de un trabajador, o WRK-001 si no existe.
     *
     * <p>NO filtra por activo: un trabajador desactivado se abre igual, que es justamente
     * como se lo vuelve a activar. Filtrar aca obligaria a hacerlo por SQL, que es lo que
     * este modulo viene a eliminar.
     */
    public WorkerDetailResponse getWorker(Integer workerId) {
        return workerRepository.findDetailById(workerId)
            .map(workerServiceMapper::toWorkerDetailResponse)
            .orElseThrow(WorkersError.NOT_FOUND::toException);
    }
}
