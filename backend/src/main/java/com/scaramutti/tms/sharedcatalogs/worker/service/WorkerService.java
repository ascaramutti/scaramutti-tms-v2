package com.scaramutti.tms.sharedcatalogs.worker.service;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.sharedcatalogs.worker.service.cmd.ListWorkersQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Listado de trabajadores (GET /workers), catalogo compartido {@code public.workers}
 * (read-only desde v2; el ABM sigue en v1). Read-only, sin {@code @Transactional} (misma
 * convencion que los otros listados del modulo). El filtro y el orden los resuelve
 * {@link WorkerRepository#search}; aca solo se mapea la entidad al response.
 */
@ApplicationScoped
public class WorkerService {

    @Inject WorkerRepository workerRepository;

    public List<WorkerResponse> listWorkers(ListWorkersQuery query) {
        return workerRepository.search(query.q(), query.isActive()).stream()
            .map(this::toWorkerResponse)
            .toList();
    }

    private WorkerResponse toWorkerResponse(Worker worker) {
        return new WorkerResponse(worker.id, worker.fullName(), worker.position, worker.isActive);
    }
}
