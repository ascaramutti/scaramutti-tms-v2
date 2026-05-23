package com.scaramutti.tms.clients.service;

import com.scaramutti.tms.clients.ClientsError;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.mapper.ClientServiceMapper;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.clients.service.cmd.ListClientsQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.ClientRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class ClientService {

    private static final Logger LOG = Logger.getLogger(ClientService.class);

    @Inject ClientRepository clientRepository;
    @Inject ClientServiceMapper clientServiceMapper;

    /**
     * Listado paginado con busqueda fuzzy opcional. Read-only, no requiere
     * @Transactional (Quarkus abre tx implicita si Hibernate la necesita).
     *
     * Doble query (search + count) con el mismo predicado garantizado por el
     * helper privado del repo. Para 100k clientes el count es O(rows_filtered)
     * pero los GIN trgm indexes lo hacen aceptable; si se vuelve cuello,
     * cachear el count por (q,isActive) o cambiar a keyset pagination.
     *
     * El Query viene ya normalizado desde el ClientResourceMapper
     * (q trimmed + uppercased). El service solo orquesta.
     */
    /**
     * Devuelve el cliente con el id dado, o tira CLI-003 (404) si no existe.
     * NO filtra por isActive — el caller decide segun contexto:
     *  - GET /clients/{id} (admin/operations): puede mostrar inactivos.
     *  - POST /quotations (loader): valida isActive y tira COM-001 si no aplica.
     */
    public ClientResponse findById(Integer id) {
        Client client = clientRepository.findById(id);
        if (client == null) {
            throw ClientsError.NOT_FOUND.toException();
        }
        return clientServiceMapper.toClientResponse(client);
    }

    public PageResponse<ClientResponse> listClients(ListClientsQuery listClientsQuery) {
        List<Client> clients = clientRepository.searchPaged(listClientsQuery);
        long totalElements = clientRepository.countSearch(listClientsQuery);

        List<ClientResponse> content = clientServiceMapper.toClientResponseList(clients);
        return PageResponse.of(content, listClientsQuery.page(), listClientsQuery.size(), totalElements);
    }

    @Transactional
    public ClientResponse createClient(CreateClientCommand createClientCommand) {
        validatePostTrim(createClientCommand.name());
        validateNoDuplicates(createClientCommand);

        Client client = clientServiceMapper.toClientEntity(createClientCommand);
        persistOrTranslateDuplicate(client);

        return clientServiceMapper.toClientResponse(client);
    }

    /**
     * Guarda invariante del dominio: el name no puede llegar vacío al service.
     *
     * Por el flow REST normal, `ClientResourceMapper.trimUpperOrNull` ya convierte
     * "   " → null antes de armar el Command, asi que el branch `isEmpty()` aqui
     * solo dispara para callers que bypasean el mapper (batch importers, otros
     * services internos, tests unitarios directos). Es defense-in-depth.
     */
    private void validatePostTrim(String name) {
        if (name == null || name.isEmpty()) {
            throw CommonError.VALIDATION_FAILED.toException();
        }
    }

    /**
     * Query previo para detectar duplicados antes del INSERT.
     * El frontend conoce el ruc/name que envió, así que el detail genérico del
     * enum (sin valor concreto) es suficiente — el frontend renderiza el mensaje.
     */
    private void validateNoDuplicates(CreateClientCommand createClientCommand) {
        if (clientRepository.existsByRuc(createClientCommand.ruc())) {
            throw ClientsError.DUPLICATE_RUC.toException();
        }
        if (clientRepository.existsByName(createClientCommand.name())) {
            throw ClientsError.DUPLICATE_NAME.toException();
        }
    }

    /**
     * Persiste + flush dentro de la tx. Cubre la race condition donde dos
     * requests pasan `validateNoDuplicates` simultaneamente: el segundo INSERT
     * viola el UNIQUE de Postgres y Hibernate tira ConstraintViolationException.
     * Sin este catch caeriamos a 500 sin Problem ni code.
     */
    private void persistOrTranslateDuplicate(Client client) {
        try {
            clientRepository.persist(client);
            clientRepository.flush();
        } catch (PersistenceException ex) {
            ConstraintViolationException cve = extractConstraintViolation(ex);
            if (cve == null) {
                throw ex;
            }
            String constraintName = cve.getConstraintName();
            if (constraintName != null && constraintName.contains("ruc")) {
                LOG.warnf("Race condition: UNIQUE ruc violation [ruc=%s]", client.ruc);
                throw ClientsError.DUPLICATE_RUC.toException();
            }
            if (constraintName != null && constraintName.contains("name")) {
                LOG.warnf("Race condition: UNIQUE name violation [name=%s]", client.name);
                throw ClientsError.DUPLICATE_NAME.toException();
            }
            // Constraint desconocido — log para ops y propagamos sin enmascarar bugs.
            LOG.errorf(ex, "Unhandled DB constraint violation [constraint=%s]", constraintName);
            throw ex;
        }
    }

    /** Hibernate envuelve la ConstraintViolationException dentro de PersistenceException. */
    private ConstraintViolationException extractConstraintViolation(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) {
            return cve;
        }
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? cve : null;
    }
}
