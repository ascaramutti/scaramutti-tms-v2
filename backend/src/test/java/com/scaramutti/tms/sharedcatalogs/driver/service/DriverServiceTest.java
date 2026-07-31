package com.scaramutti.tms.sharedcatalogs.driver.service;

import com.scaramutti.tms.shared.repository.DriverRepository;
import com.scaramutti.tms.shared.repository.DriverRepository.DriverRow;
import com.scaramutti.tms.sharedcatalogs.driver.dto.DriverResponse;
import com.scaramutti.tms.sharedcatalogs.driver.mapper.DriverServiceMapper;
import com.scaramutti.tms.sharedcatalogs.driver.service.cmd.ListDriversQuery;
import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service de conductores. Cubre la delegacion del filtro al repo y el mapeo de
 * la fila (disponibilidad del catalogo a enum de dominio; categoria y telefono opcionales).
 * El SQL real con sus joins lo cubren los integration tests (DriversResourceTest).
 */
@ExtendWith(MockitoExtension.class)
class DriverServiceTest {

    @Mock DriverRepository driverRepository;
    @InjectMocks DriverService driverService;

    // El mapper es un colaborador REAL (impl generada por MapStruct), no un mock:
    // estos tests cubren justamente el shaping fila a response (incluida la disponibilidad).
    @BeforeEach
    void wireRealMapper() {
        driverService.driverServiceMapper = Mappers.getMapper(DriverServiceMapper.class);
    }

    @Test
    void listDrivers_delegatesFilterAndMapsStatusToEnum() {
        when(driverRepository.search(true)).thenReturn(List.of(
            new DriverRow(7, "Juan Perez", "Q12345678", "A-IIIc", "987654321", "available", true)));

        List<DriverResponse> result = driverService.listDrivers(new ListDriversQuery(true));

        verify(driverRepository).search(true);
        assertEquals(1, result.size());
        DriverResponse r = result.get(0);
        assertEquals(7, r.id());
        assertEquals("Juan Perez", r.fullName());
        assertEquals("Q12345678", r.licenseNumber());
        assertEquals("A-IIIc", r.licenseCategory());
        assertEquals("987654321", r.phone());
        assertEquals(FleetResourceStatus.AVAILABLE, r.status());
        assertEquals(true, r.isActive());
    }

    @Test
    void listDrivers_rowWithoutCategoryAndPhoneMapsThemNull() {
        when(driverRepository.search(null)).thenReturn(List.of(
            new DriverRow(8, "Ana Quispe", "Q87654321", null, null, "maintenance", true)));

        DriverResponse r = driverService.listDrivers(new ListDriversQuery(null)).get(0);

        assertNull(r.licenseCategory());
        assertNull(r.phone());
        assertEquals(FleetResourceStatus.MAINTENANCE, r.status());
    }

    /**
     * Un estado que no esta en el dominio de la API (alguien agrego una fila al catalogo de v1)
     * revienta: servir al conductor sin estado lo haria pasar por "sin dato" en la asignacion.
     */
    @Test
    void listDrivers_statusOutsideTheApiDomain_fails() {
        when(driverRepository.search(null)).thenReturn(List.of(
            new DriverRow(9, "Luis Diaz", "Q11112222", null, null, "de_vacaciones", true)));

        assertThrows(IllegalStateException.class,
            () -> driverService.listDrivers(new ListDriversQuery(null)));
    }

    @Test
    void listDrivers_emptyRepositoryResult_returnsEmptyList() {
        when(driverRepository.search(false)).thenReturn(List.of());

        assertEquals(0, driverService.listDrivers(new ListDriversQuery(false)).size());
    }
}
