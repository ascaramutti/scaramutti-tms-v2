package com.scaramutti.tms.clients.service;

import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.mapper.ClientServiceMapper;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
}
