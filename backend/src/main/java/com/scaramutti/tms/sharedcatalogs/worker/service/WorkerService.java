package com.scaramutti.tms.sharedcatalogs.worker.service;

import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.sharedcatalogs.worker.mapper.WorkerServiceMapper;
import com.scaramutti.tms.sharedcatalogs.worker.service.cmd.ListWorkersQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Listado de trabajadores (GET /workers), catalogo compartido {@code public.workers}
 * (read-only desde v2; el ABM sigue en v1). Read-only, sin {@code @Transactional} (misma
 * convencion que los otros listados del modulo). El filtro y el orden los resuelve
 * {@link WorkerRepository#search}; el shaping al response vive en el mapper.
 */
@ApplicationScoped
public class WorkerService {

    @Inject WorkerRepository workerRepository;
    @Inject WorkerServiceMapper workerServiceMapper;

    public List<WorkerResponse> listWorkers(ListWorkersQuery query) {
        return workerServiceMapper.toWorkerResponseList(
            workerRepository.search(query.q(), query.isActive())
        );
    }
}
