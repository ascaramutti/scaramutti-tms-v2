package com.scaramutti.tms.clients.service;

import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.mapper.ClientServiceMapper;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.clients.service.cmd.ListClientsQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests del service. Aisla la logica de validacion/duplicados del IO de BD
 * usando mocks. Los integration tests cubren el end-to-end con BD real.
 */
@ExtendWith(MockitoExtension.class)
class ClientServiceTest {

    @Mock ClientRepository clientRepository;
    @Mock ClientServiceMapper clientServiceMapper;
    @InjectMocks ClientService clientService;

    private CreateClientCommand sampleCommand() {
        return new CreateClientCommand("ACME CORP", "20123456789", "987654321", "Juan Pérez");
    }

    @Test
    void createClient_withValidCommand_persistsEntityAndReturnsResponse() {
        CreateClientCommand command = sampleCommand();
        Client mappedEntity = new Client();
        mappedEntity.name = command.name();
        mappedEntity.ruc = command.ruc();
        ClientResponse expectedResponse = new ClientResponse(
            1, command.name(), command.ruc(), command.phone(), command.contactName(),
            true, OffsetDateTime.now()
        );

        when(clientRepository.existsByRuc(command.ruc())).thenReturn(false);
        when(clientRepository.existsByName(command.name())).thenReturn(false);
        when(clientServiceMapper.toClientEntity(command)).thenReturn(mappedEntity);
        when(clientServiceMapper.toClientResponse(mappedEntity)).thenReturn(expectedResponse);

        ClientResponse actualResponse = clientService.createClient(command);

        assertSame(expectedResponse, actualResponse);
        verify(clientRepository).persist(mappedEntity);
        verify(clientRepository).flush();
    }

    @Test
    void createClient_whenRucDuplicate_throwsApiExceptionCLI001_andDoesNotPersist() {
        CreateClientCommand command = sampleCommand();
        when(clientRepository.existsByRuc(command.ruc())).thenReturn(true);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> clientService.createClient(command)
        );
        assertEquals("CLI-001", ex.code());
        assertEquals(409, ex.status());

        verify(clientRepository, never()).persist(any(Client.class));
    }

    @Test
    void createClient_whenNameDuplicate_throwsApiExceptionCLI002_andDoesNotPersist() {
        CreateClientCommand command = sampleCommand();
        when(clientRepository.existsByRuc(command.ruc())).thenReturn(false);
        when(clientRepository.existsByName(command.name())).thenReturn(true);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> clientService.createClient(command)
        );
        assertEquals("CLI-002", ex.code());
        assertEquals(409, ex.status());

        verify(clientRepository, never()).persist(any(Client.class));
    }

    @Test
    void createClient_checksRucBeforeName_failsFastOnRucConflict() {
        // Si ambos colisionan, el orden de checks debe ser RUC primero (más específico).
        // Defensa anti-refactor.
        CreateClientCommand command = sampleCommand();
        when(clientRepository.existsByRuc(command.ruc())).thenReturn(true);
        // No stub existsByName porque NO debe llamarse

        ApiException ex = assertThrows(
            ApiException.class,
            () -> clientService.createClient(command)
        );
        assertEquals("CLI-001", ex.code());
        verify(clientRepository, never()).existsByName(anyString());
    }

    @Test
    void createClient_withNullName_throwsValidationFailedCOM001() {
        // Si por algun bug el mapper deja name=null, el guard post-trim del service lo atrapa.
        CreateClientCommand command = new CreateClientCommand(null, "20123456789", null, null);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> clientService.createClient(command)
        );
        assertEquals("COM-001", ex.code());
        assertEquals(400, ex.status());

        verify(clientRepository, never()).persist(any(Client.class));
    }

    @Test
    void createClient_withEmptyName_throwsValidationFailedCOM001() {
        // Caso clave: name pasa Bean Validation pero post-trim quedó "". Service falla.
        CreateClientCommand command = new CreateClientCommand("", "20123456789", null, null);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> clientService.createClient(command)
        );
        assertEquals("COM-001", ex.code());

        verify(clientRepository, never()).persist(any(Client.class));
    }

    @Test
    void createClient_whenFlushThrowsConstraintViolationOnRuc_translatesToCLI001() {
        // Race condition: validateNoDuplicates pasa (existsByRuc=false) pero el INSERT
        // simultaneo de otro request hace que el flush viole el UNIQUE de ruc.
        // El catch del service debe traducir la ConstraintViolationException a CLI-001.
        CreateClientCommand command = sampleCommand();
        Client mappedEntity = new Client();

        when(clientRepository.existsByRuc(anyString())).thenReturn(false);
        when(clientRepository.existsByName(anyString())).thenReturn(false);
        when(clientServiceMapper.toClientEntity(command)).thenReturn(mappedEntity);

        org.hibernate.exception.ConstraintViolationException hibernateCve =
            new org.hibernate.exception.ConstraintViolationException(
                "Postgres UNIQUE violation",
                new java.sql.SQLException("violates unique constraint \"clients_ruc_key\""),
                "clients_ruc_key"
            );
        jakarta.persistence.PersistenceException jpaWrapper =
            new jakarta.persistence.PersistenceException(hibernateCve);
        doThrow(jpaWrapper).when(clientRepository).flush();

        ApiException ex = assertThrows(
            ApiException.class,
            () -> clientService.createClient(command)
        );
        assertEquals("CLI-001", ex.code());
    }

    @Test
    void createClient_whenFlushThrowsConstraintViolationOnName_translatesToCLI002() {
        // Misma race condition pero contra el UNIQUE de name.
        CreateClientCommand command = sampleCommand();
        Client mappedEntity = new Client();

        when(clientRepository.existsByRuc(anyString())).thenReturn(false);
        when(clientRepository.existsByName(anyString())).thenReturn(false);
        when(clientServiceMapper.toClientEntity(command)).thenReturn(mappedEntity);

        org.hibernate.exception.ConstraintViolationException hibernateCve =
            new org.hibernate.exception.ConstraintViolationException(
                "Postgres UNIQUE violation",
                new java.sql.SQLException("violates unique constraint \"clients_name_key\""),
                "clients_name_key"
            );
        jakarta.persistence.PersistenceException jpaWrapper =
            new jakarta.persistence.PersistenceException(hibernateCve);
        doThrow(jpaWrapper).when(clientRepository).flush();

        ApiException ex = assertThrows(
            ApiException.class,
            () -> clientService.createClient(command)
        );
        assertEquals("CLI-002", ex.code());
    }

    // =========================================================================
    // listClients — paginado + busqueda fuzzy
    // =========================================================================

    @Test
    void listClients_passesQueryToRepoUnchanged() {
        // El service ya no transforma el Query (q viene uppercased desde el mapper).
        // Lock-in: pasa la misma instancia al search y al count.
        ListClientsQuery listClientsQuery = new ListClientsQuery("ACME", true, 0, 20);
        when(clientRepository.searchPaged(eq(listClientsQuery))).thenReturn(List.of());
        when(clientRepository.countSearch(eq(listClientsQuery))).thenReturn(0L);

        PageResponse<ClientResponse> response = clientService.listClients(listClientsQuery);

        verify(clientRepository).searchPaged(listClientsQuery);
        verify(clientRepository).countSearch(listClientsQuery);
        assertTrue(response.content().isEmpty());
    }

    @Test
    void listClients_withNullQAndNullIsActive_passesNullsToRepo() {
        ListClientsQuery listClientsQuery = new ListClientsQuery(null, null, 0, 20);
        when(clientRepository.searchPaged(eq(listClientsQuery))).thenReturn(List.of());
        when(clientRepository.countSearch(eq(listClientsQuery))).thenReturn(0L);

        clientService.listClients(listClientsQuery);

        verify(clientRepository).searchPaged(listClientsQuery);
    }

    @Test
    void listClients_withIsActiveTrue_passesFilterInQuery() {
        ListClientsQuery listClientsQuery = new ListClientsQuery(null, true, 0, 20);
        when(clientRepository.searchPaged(eq(listClientsQuery))).thenReturn(List.of());
        when(clientRepository.countSearch(eq(listClientsQuery))).thenReturn(0L);

        clientService.listClients(listClientsQuery);

        verify(clientRepository).searchPaged(listClientsQuery);
    }

    @Test
    void listClients_withIsActiveFalse_passesFilterInQuery() {
        ListClientsQuery listClientsQuery = new ListClientsQuery(null, false, 0, 20);
        when(clientRepository.searchPaged(eq(listClientsQuery))).thenReturn(List.of());
        when(clientRepository.countSearch(eq(listClientsQuery))).thenReturn(0L);

        clientService.listClients(listClientsQuery);

        verify(clientRepository).searchPaged(listClientsQuery);
    }

    @Test
    void listClients_mapsRepoPageToPageResponse_correctMeta() {
        Client c1 = new Client(); c1.id = 1; c1.name = "A"; c1.ruc = "11111111111";
        c1.isActive = true; c1.createdAt = OffsetDateTime.now();
        Client c2 = new Client(); c2.id = 2; c2.name = "B"; c2.ruc = "22222222222";
        c2.isActive = true; c2.createdAt = OffsetDateTime.now();
        Client c3 = new Client(); c3.id = 3; c3.name = "C"; c3.ruc = "33333333333";
        c3.isActive = true; c3.createdAt = OffsetDateTime.now();

        ListClientsQuery listClientsQuery = new ListClientsQuery(null, null, 1, 5);
        when(clientRepository.searchPaged(eq(listClientsQuery))).thenReturn(List.of(c1, c2, c3));
        when(clientRepository.countSearch(eq(listClientsQuery))).thenReturn(11L);

        List<ClientResponse> mappedList = List.of(
            new ClientResponse(1, "A", "11111111111", null, null, true, c1.createdAt),
            new ClientResponse(2, "B", "22222222222", null, null, true, c2.createdAt),
            new ClientResponse(3, "C", "33333333333", null, null, true, c3.createdAt)
        );
        when(clientServiceMapper.toClientResponseList(any())).thenReturn(mappedList);

        PageResponse<ClientResponse> response = clientService.listClients(listClientsQuery);

        assertEquals(1, response.page());
        assertEquals(5, response.size());
        assertEquals(11L, response.totalElements());
        assertEquals(3, response.totalPages());      // ceil(11/5) = 3
        assertEquals(3, response.numberOfElements());
        assertFalse(response.first());
        assertFalse(response.last());
        assertFalse(response.empty());
        assertEquals(3, response.content().size());
        verify(clientServiceMapper, times(1)).toClientResponseList(any());
    }

    @Test
    void listClients_emptyRepoResult_returnsEmptyPageWithFirstAndLastTrue() {
        when(clientRepository.searchPaged(any(ListClientsQuery.class))).thenReturn(List.of());
        when(clientRepository.countSearch(any(ListClientsQuery.class))).thenReturn(0L);
        when(clientServiceMapper.toClientResponseList(any())).thenReturn(List.of());

        PageResponse<ClientResponse> response = clientService.listClients(
            new ListClientsQuery("NADA", null, 0, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(0L, response.totalElements());
        assertEquals(0, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
        assertTrue(response.empty());
    }

    @Test
    void listClients_firstPageOfMany_setsFirstTrueLastFalse() {
        when(clientRepository.searchPaged(any(ListClientsQuery.class))).thenReturn(List.of());
        when(clientRepository.countSearch(any(ListClientsQuery.class))).thenReturn(50L);
        when(clientServiceMapper.toClientResponseList(any())).thenReturn(List.of());

        PageResponse<ClientResponse> response = clientService.listClients(
            new ListClientsQuery(null, null, 0, 20)
        );

        assertTrue(response.first());
        assertFalse(response.last());
        assertEquals(3, response.totalPages()); // ceil(50/20) = 3
    }

    @Test
    void listClients_lastPageOfMany_setsFirstFalseLastTrue() {
        when(clientRepository.searchPaged(any(ListClientsQuery.class))).thenReturn(List.of());
        when(clientRepository.countSearch(any(ListClientsQuery.class))).thenReturn(50L);
        when(clientServiceMapper.toClientResponseList(any())).thenReturn(List.of());

        PageResponse<ClientResponse> response = clientService.listClients(
            new ListClientsQuery(null, null, 2, 20)
        );

        assertFalse(response.first());
        assertTrue(response.last());
    }

    @Test
    void listClients_singlePageResult_setsBothFirstAndLastTrue() {
        Client c = new Client(); c.id = 1; c.name = "ONLY"; c.ruc = "11111111111";
        c.isActive = true; c.createdAt = OffsetDateTime.now();
        when(clientRepository.searchPaged(any(ListClientsQuery.class))).thenReturn(List.of(c));
        when(clientRepository.countSearch(any(ListClientsQuery.class))).thenReturn(1L);
        when(clientServiceMapper.toClientResponseList(any())).thenReturn(
            List.of(new ClientResponse(1, "ONLY", "11111111111", null, null, true, c.createdAt))
        );

        PageResponse<ClientResponse> response = clientService.listClients(
            new ListClientsQuery(null, null, 0, 20)
        );

        assertTrue(response.first());
        assertTrue(response.last());
        assertEquals(1, response.numberOfElements());
        assertFalse(response.empty());
    }

    @Test
    void listClients_pageOverflow_emptyContentButLastTrue() {
        // page=99 con totalElements=2 → content vacio, totalPages=1, last=true.
        when(clientRepository.searchPaged(any(ListClientsQuery.class))).thenReturn(List.of());
        when(clientRepository.countSearch(any(ListClientsQuery.class))).thenReturn(2L);
        when(clientServiceMapper.toClientResponseList(any())).thenReturn(List.of());

        PageResponse<ClientResponse> response = clientService.listClients(
            new ListClientsQuery(null, null, 99, 20)
        );

        assertTrue(response.content().isEmpty());
        assertEquals(2L, response.totalElements());
        assertEquals(1, response.totalPages());
        assertFalse(response.first());
        assertTrue(response.last());
        assertTrue(response.empty());
    }
}
