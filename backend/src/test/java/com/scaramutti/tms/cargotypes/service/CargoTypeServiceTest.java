package com.scaramutti.tms.cargotypes.service;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.mapper.CargoTypeServiceMapper;
import com.scaramutti.tms.cargotypes.service.cmd.CreateCargoTypeCommand;
import com.scaramutti.tms.cargotypes.service.cmd.ListCargoTypesQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service. Calco del listClients de ClientServiceTest.
 * Aisla la logica del IO de BD usando mocks. Los integration tests cubren
 * el end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class CargoTypeServiceTest {

    @Mock CargoTypeRepository cargoTypeRepository;
    @Mock CargoTypeServiceMapper cargoTypeServiceMapper;
    @InjectMocks CargoTypeService cargoTypeService;

    @Test
    void listCargoTypes_passesQueryToRepoUnchanged() {
        // El service no transforma el Query — solo lo pasa al repo (search y count).
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery("EXCAVADORA", true, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
        verify(cargoTypeRepository).countSearch(listCargoTypesQuery);
        assertTrue(response.content().isEmpty());
    }

    @Test
    void listCargoTypes_withNullQAndNullIsActive_passesNullsToRepo() {
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, null, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
    }

    @Test
    void listCargoTypes_withIsActiveTrue_passesFilterInQuery() {
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, true, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
    }

    @Test
    void listCargoTypes_withIsActiveFalse_passesFilterInQuery() {
        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, false, 0, 20);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(0L);

        cargoTypeService.listCargoTypes(listCargoTypesQuery);

        verify(cargoTypeRepository).searchPaged(listCargoTypesQuery);
    }

    @Test
    void listCargoTypes_mapsRepoPageToPageResponse_correctMeta() {
        CargoType c1 = newCargoType(1, "A");
        CargoType c2 = newCargoType(2, "B");
        CargoType c3 = newCargoType(3, "C");

        ListCargoTypesQuery listCargoTypesQuery = new ListCargoTypesQuery(null, null, 1, 5);
        when(cargoTypeRepository.searchPaged(eq(listCargoTypesQuery))).thenReturn(List.of(c1, c2, c3));
        when(cargoTypeRepository.countSearch(eq(listCargoTypesQuery))).thenReturn(11L);

        List<CargoTypeResponse> mappedList = List.of(
            newResponse(1, "A"),
            newResponse(2, "B"),
            newResponse(3, "C")
        );
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(mappedList);

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(listCargoTypesQuery);

        assertEquals(1, response.page());
        assertEquals(5, response.size());
        assertEquals(11L, response.totalElements());
        assertEquals(3, response.totalPages());      // ceil(11/5) = 3
        assertEquals(3, response.numberOfElements());
        assertFalse(response.first());
        assertFalse(response.last());
        assertFalse(response.empty());
        assertEquals(3, response.content().size());
        verify(cargoTypeServiceMapper, times(1)).toCargoTypeResponseList(any());
    }

    @Test
    void listCargoTypes_emptyRepoResult_returnsEmptyPageWithFirstAndLastTrue() {
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(0L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery("NADA", null, 0, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(0L, response.totalElements());
        assertEquals(0, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
        assertTrue(response.empty());
    }

    @Test
    void listCargoTypes_firstPageOfMany_setsFirstTrueLastFalse() {
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(50L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 0, 20)
        );

        assertTrue(response.first());
        assertFalse(response.last());
        assertEquals(3, response.totalPages()); // ceil(50/20) = 3
    }

    @Test
    void listCargoTypes_lastPageOfMany_setsFirstFalseLastTrue() {
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(50L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 2, 20)
        );

        assertFalse(response.first());
        assertTrue(response.last());
    }

    @Test
    void listCargoTypes_singlePageResult_setsBothFirstAndLastTrue() {
        CargoType c = newCargoType(1, "ONLY");
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of(c));
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(1L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(
            List.of(newResponse(1, "ONLY"))
        );

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 0, 20)
        );

        assertTrue(response.first());
        assertTrue(response.last());
        assertEquals(1, response.numberOfElements());
        assertFalse(response.empty());
    }

    @Test
    void listCargoTypes_pageOverflow_emptyContentButLastTrue() {
        // page=99 con totalElements=2 → content vacio, totalPages=1, last=true.
        when(cargoTypeRepository.searchPaged(any(ListCargoTypesQuery.class))).thenReturn(List.of());
        when(cargoTypeRepository.countSearch(any(ListCargoTypesQuery.class))).thenReturn(2L);
        when(cargoTypeServiceMapper.toCargoTypeResponseList(any())).thenReturn(List.of());

        PageResponse<CargoTypeResponse> response = cargoTypeService.listCargoTypes(
            new ListCargoTypesQuery(null, null, 99, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(2L, response.totalElements());
        assertEquals(1, response.totalPages());
        assertFalse(response.first());
        assertTrue(response.last());
        assertTrue(response.empty());
    }

    // ---------- Helpers -------------------------------------------------------

    private CargoType newCargoType(int id, String name) {
        CargoType c = new CargoType();
        c.id = id;
        c.name = name;
        c.standardWeight = BigDecimal.ONE;
        c.isActive = true;
        return c;
    }

    private CargoTypeResponse newResponse(int id, String name) {
        return new CargoTypeResponse(id, name, null, BigDecimal.ONE, null, null, null, true);
    }

    // =========================================================================
    // createCargoType — validacion + duplicados + race condition
    // =========================================================================

    private CreateCargoTypeCommand sampleCreateCommand() {
        return new CreateCargoTypeCommand(
            "ZTEST_EXCAVADORA", "Desc opcional",
            new BigDecimal("30.50"), new BigDecimal("12.00"),
            new BigDecimal("3.00"),  new BigDecimal("3.20")
        );
    }

    @Test
    void createCargoType_withValidCommand_persistsEntityAndReturnsResponse() {
        CreateCargoTypeCommand command = sampleCreateCommand();
        CargoType mappedEntity = new CargoType();
        mappedEntity.name = command.name();
        CargoTypeResponse expectedResponse = new CargoTypeResponse(
            1, command.name(), command.description(),
            command.standardWeight(), command.standardLength(),
            command.standardWidth(), command.standardHeight(),
            true
        );

        when(cargoTypeRepository.existsByName(command.name())).thenReturn(false);
        when(cargoTypeServiceMapper.toCargoTypeEntity(command)).thenReturn(mappedEntity);
        when(cargoTypeServiceMapper.toCargoTypeResponse(mappedEntity)).thenReturn(expectedResponse);

        CargoTypeResponse actualResponse = cargoTypeService.createCargoType(command);

        assertSame(expectedResponse, actualResponse);
        verify(cargoTypeRepository).persist(mappedEntity);
        verify(cargoTypeRepository).flush();
    }

    @Test
    void createCargoType_whenNameDuplicate_throwsApiExceptionCGT001_andDoesNotPersist() {
        CreateCargoTypeCommand command = sampleCreateCommand();
        when(cargoTypeRepository.existsByName(command.name())).thenReturn(true);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> cargoTypeService.createCargoType(command)
        );
        assertEquals("CGT-001", ex.code());
        assertEquals(409, ex.status());

        verify(cargoTypeRepository, never()).persist(any(CargoType.class));
    }

    @Test
    void createCargoType_withNullName_throwsValidationFailedCOM001() {
        // Si por algun bug el mapper deja name=null, el guard post-trim del service lo atrapa.
        CreateCargoTypeCommand command = new CreateCargoTypeCommand(
            null, null, BigDecimal.ONE, null, null, null
        );

        ApiException ex = assertThrows(
            ApiException.class,
            () -> cargoTypeService.createCargoType(command)
        );
        assertEquals("COM-001", ex.code());
        assertEquals(400, ex.status());

        verify(cargoTypeRepository, never()).persist(any(CargoType.class));
    }

    @Test
    void createCargoType_withEmptyName_throwsValidationFailedCOM001() {
        // Caso clave: name pasa Bean Validation pero post-trim quedo "".
        CreateCargoTypeCommand command = new CreateCargoTypeCommand(
            "", null, BigDecimal.ONE, null, null, null
        );

        ApiException ex = assertThrows(
            ApiException.class,
            () -> cargoTypeService.createCargoType(command)
        );
        assertEquals("COM-001", ex.code());

        verify(cargoTypeRepository, never()).persist(any(CargoType.class));
    }

    @Test
    void createCargoType_whenFlushThrowsConstraintViolationOnName_translatesToCGT001() {
        // Race condition: validateNoDuplicates pasa (existsByName=false) pero el INSERT
        // simultaneo de otro request hace que el flush viole el UNIQUE de name.
        // El catch del service debe traducir la ConstraintViolationException a CGT-001.
        CreateCargoTypeCommand command = sampleCreateCommand();
        CargoType mappedEntity = new CargoType();

        when(cargoTypeRepository.existsByName(command.name())).thenReturn(false);
        when(cargoTypeServiceMapper.toCargoTypeEntity(command)).thenReturn(mappedEntity);

        org.hibernate.exception.ConstraintViolationException hibernateCve =
            new org.hibernate.exception.ConstraintViolationException(
                "Postgres UNIQUE violation",
                new java.sql.SQLException("violates unique constraint \"cargo_types_name_key\""),
                "cargo_types_name_key"
            );
        jakarta.persistence.PersistenceException jpaWrapper =
            new jakarta.persistence.PersistenceException(hibernateCve);
        doThrow(jpaWrapper).when(cargoTypeRepository).flush();

        ApiException ex = assertThrows(
            ApiException.class,
            () -> cargoTypeService.createCargoType(command)
        );
        assertEquals("CGT-001", ex.code());
    }

    @Test
    void createCargoType_invokesMapperToEntityAndPersist() {
        CreateCargoTypeCommand command = sampleCreateCommand();
        CargoType mappedEntity = new CargoType();
        CargoTypeResponse mappedResponse = new CargoTypeResponse(
            1, command.name(), null, BigDecimal.ONE, null, null, null, true
        );

        when(cargoTypeRepository.existsByName(command.name())).thenReturn(false);
        when(cargoTypeServiceMapper.toCargoTypeEntity(command)).thenReturn(mappedEntity);
        when(cargoTypeServiceMapper.toCargoTypeResponse(mappedEntity)).thenReturn(mappedResponse);

        cargoTypeService.createCargoType(command);

        verify(cargoTypeServiceMapper).toCargoTypeEntity(command);
        verify(cargoTypeRepository).persist(mappedEntity);
        verify(cargoTypeServiceMapper).toCargoTypeResponse(mappedEntity);
    }

    @Test
    void createCargoType_existsByName_calledOnceBeforePersist() {
        // Lock-in del orden: check de duplicados ANTES de persist.
        CreateCargoTypeCommand command = sampleCreateCommand();
        CargoType mappedEntity = new CargoType();

        when(cargoTypeRepository.existsByName(command.name())).thenReturn(false);
        when(cargoTypeServiceMapper.toCargoTypeEntity(command)).thenReturn(mappedEntity);

        cargoTypeService.createCargoType(command);

        InOrder order = inOrder(cargoTypeRepository);
        order.verify(cargoTypeRepository).existsByName(command.name());
        order.verify(cargoTypeRepository).persist(any(CargoType.class));
        order.verify(cargoTypeRepository).flush();
    }
}
