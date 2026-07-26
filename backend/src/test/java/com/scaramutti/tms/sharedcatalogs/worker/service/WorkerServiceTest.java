package com.scaramutti.tms.sharedcatalogs.worker.service;

import com.scaramutti.tms.sharedcatalogs.worker.mapper.WorkerServiceMapper;
import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.sharedcatalogs.worker.service.cmd.ListWorkersQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service de trabajadores. Cubre la delegacion del filtro al repo y el
 * mapeo entidad a response (fullName compuesto por la entity). El SQL real (multi-palabra,
 * isActive, orden) lo cubren los integration tests (WorkersResourceTest).
 */
@ExtendWith(MockitoExtension.class)
class WorkerServiceTest {

    @Mock WorkerRepository workerRepository;
    @InjectMocks WorkerService workerService;

    // El mapper es un colaborador REAL (impl generada por MapStruct), no un mock:
    // estos tests cubren justamente el shaping entidad a response.
    @BeforeEach
    void wireRealMapper() {
        workerService.workerServiceMapper = Mappers.getMapper(WorkerServiceMapper.class);
    }

    private Worker worker(int id, String first, String last, String position, boolean isActive) {
        Worker w = new Worker();
        w.id = id;
        w.firstName = first;
        w.lastName = last;
        w.position = position;
        w.isActive = isActive;
        return w;
    }

    @Test
    void listWorkers_delegatesFilterToRepositoryAndMapsFullName() {
        when(workerRepository.search("juan", true))
            .thenReturn(List.of(worker(8, "Juan", "Perez", "Mecánico", true)));

        List<WorkerResponse> result = workerService.listWorkers(new ListWorkersQuery("juan", true));

        verify(workerRepository).search("juan", true);
        assertEquals(1, result.size());
        WorkerResponse r = result.get(0);
        assertEquals(8, r.id());
        assertEquals("Juan Perez", r.fullName());
        assertEquals("Mecánico", r.position());
        assertEquals(true, r.isActive());
    }

    @Test
    void listWorkers_emptyRepositoryResult_returnsEmptyList() {
        when(workerRepository.search(null, null)).thenReturn(List.of());

        List<WorkerResponse> result = workerService.listWorkers(new ListWorkersQuery(null, null));

        assertEquals(0, result.size());
    }
}
