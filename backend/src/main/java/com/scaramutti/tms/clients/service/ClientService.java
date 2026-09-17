package com.scaramutti.tms.clients.service;

import com.scaramutti.tms.clients.ClientsError;
import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.mapper.ClientServiceMapper;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.clients.service.cmd.ListClientsQuery;
import com.scaramutti.tms.clients.service.cmd.UpdateClientCommand;
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

    /** Nombres reales de las restricciones, tal como los reporta la base. */
    private static final String RUC_CONSTRAINT = "clients_ruc_key";
    private static final String NAME_CONSTRAINT = "clients_name_key";

    @Inject ClientRepository clientRepository;
    @Inject ClientServiceMapper clientServiceMapper;

    /**
     * Devuelve el cliente con el id dado, o tira CLI-003 (404) si no existe.
     * NO filtra por isActive — el caller decide segun contexto:
     *  - GET /clients/{id}: devuelve activos e inactivos (la edicion corrige
     *    datos, y un cliente desactivado tambien se lee).
     *  - POST /quotations (loader): valida isActive y tira COM-001 si no aplica.
     */
    public ClientResponse findById(Integer id) {
        Client client = clientRepository.findById(id);
        if (client == null) {
            throw ClientsError.NOT_FOUND.toException();
        }
        return clientServiceMapper.toClientResponse(client);
    }

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
     * Edicion de un cliente: name, ruc, phone y contactName. El estado de activo
     * y la fecha de creacion no viajan en el cuerpo y no cambian. Eso lo
     * garantizan DOS cosas y no una: los `ignore` del mapper, que protegen el
     * campo en memoria, y el `updatable = false` de las dos columnas, que es lo
     * que las saca del UPDATE. El UPDATE de una edicion que cambia AL MENOS UN
     * campo lleva todas las demas columnas actualizables, asi que los cuatro
     * editables se reemplazan con lo que llego: gana la ultima escritura, que
     * es lo decidido. La excepcion es la edicion que no cambia nada: sin nada
     * sucio no hay sentencia, asi que no desplaza una escritura concurrente.
     *
     * La respuesta se relee de la fila en vez de armarse del objeto en memoria,
     * y eso cuesta un SELECT por clave primaria en cada edicion. Se paga por las
     * dos columnas que el UPDATE no lleva: en memoria valen lo que valian al
     * cargar, y si otra transaccion las cambio en el medio, la fila guarda el
     * valor ajeno y el objeto el propio. Sin la relectura, una edicion que
     * compite con una desactivacion responderia que el cliente sigue activo, y
     * el cuerpo del 200 es lo que la pantalla pinta.
     *
     * SIN control de edicion simultanea: no hay @Version ni If-Match, asi que
     * gana la ultima escritura. Es la decision del dueno del 2026-09-14 y no una
     * deuda: lo unico que queda protegido es la unicidad, por las dos
     * restricciones de la base.
     *
     * EL ORDEN DE ESTE METODO NO ES COSMETICO. Los dos chequeos de unicidad van
     * ANTES de aplicar los campos a proposito: corren con el contexto de
     * persistencia limpio, asi que el auto-flush previo a cada count() no
     * escribe nada. Eso vale mientras este metodo sea la RAIZ de la
     * transaccion, que hoy lo es porque su unico llamador es el recurso; si
     * algun dia lo llama un service que ya dejo otra entidad sucia, ese
     * auto-flush la escribe y su violacion sale como 500.
     *
     * Si los campos se aplicaran primero, ese mismo auto-flush dispararia la
     * violacion UNIQUE DENTRO del count(), fuera del try que la traduce, y el
     * cliente veria un 500 sin Problem en vez de un 409.
     */
    @Transactional
    public ClientResponse updateClient(Integer id, UpdateClientCommand updateClientCommand) {
        Client client = clientRepository.findById(id);
        if (client == null) {
            throw ClientsError.NOT_FOUND.toException();
        }
        validatePostTrim(updateClientCommand.name());
        validateNoDuplicatesExcludingSelf(updateClientCommand, id);

        clientServiceMapper.applyUpdate(client, updateClientCommand);
        flushOrTranslateDuplicate(client);
        clientRepository.refresh(client);

        return clientServiceMapper.toClientResponse(client);
    }

    /**
     * Guarda invariante del dominio: el name no puede llegar vacío al service.
     *
     * Por el flow REST normal, `StringUtils.trimUpperOrNull` (via el ResourceMapper) ya convierte
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
     * Igual que {@link #validateNoDuplicates} pero excluyendo al cliente que se
     * esta editando: sin esa exclusion, guardar un cliente sin tocarle el RUC se
     * encontraria a si mismo y saldria un 409 contra su propio dato.
     *
     * El RUC se chequea antes que la razon social, mismo orden que el alta: con
     * los dos en conflicto gana CLI-001.
     */
    private void validateNoDuplicatesExcludingSelf(UpdateClientCommand updateClientCommand, Integer id) {
        if (clientRepository.existsByRucExcludingId(updateClientCommand.ruc(), id)) {
            throw ClientsError.DUPLICATE_RUC.toException();
        }
        if (clientRepository.existsByNameExcludingId(updateClientCommand.name(), id)) {
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
            throw translateDuplicateOrRethrow(ex, client);
        }
    }

    /**
     * La edicion solo necesita el flush: la entidad ya esta gestionada, asi que
     * no hay persist que hacer. La traduccion de la violacion es la misma que la
     * del alta, y por eso vive en un metodo aparte y no duplicada en dos catch.
     */
    private void flushOrTranslateDuplicate(Client client) {
        try {
            clientRepository.flush();
        } catch (PersistenceException ex) {
            throw translateDuplicateOrRethrow(ex, client);
        }
    }

    /**
     * Traduce la violacion de UNIQUE a su codigo de negocio, o devuelve la
     * original si no es una de las dos que conocemos.
     *
     * Cubre la carrera que el chequeo previo no puede cubrir: dos requests que
     * pasan la validacion a la vez y chocan recien en la base. Sin esto seria un
     * 500 sin Problem ni code.
     *
     * La comparacion es por nombre EXACTO y no por substring. El flush descarga
     * el contexto de persistencia entero, no solo este cliente, asi que el dia
     * que un service componga clientes con otro modulo en una misma transaccion
     * puede aflorar aca la violacion de otra entidad. Con un `contains("ruc")`,
     * el RUC duplicado de un proveedor saldria como "ya existe un cliente con
     * ese RUC"; con `contains("name")` caen ademas los unicos de usuarios,
     * proveedores, categorias, terminos de pago, tipos de carga, roles y los
     * tres catalogos de estados.
     *
     * El log lleva el id y no el RUC ni la razon social: el nivel de produccion
     * emite el WARN, y esos dos son datos del cliente. Para ops el id sirve mas.
     *
     * Devuelve la excepcion en vez de lanzarla para que quien llama escriba
     * `throw`, y el compilador vea que el flujo termina ahi.
     */
    private RuntimeException translateDuplicateOrRethrow(PersistenceException ex, Client client) {
        ConstraintViolationException cve = extractConstraintViolation(ex);
        if (cve == null) {
            return ex;
        }
        String constraintName = cve.getConstraintName();
        if (RUC_CONSTRAINT.equals(constraintName)) {
            LOG.warnf("Race condition: UNIQUE ruc violation [clientId=%s]", client.id);
            return ClientsError.DUPLICATE_RUC.toException();
        }
        if (NAME_CONSTRAINT.equals(constraintName)) {
            LOG.warnf("Race condition: UNIQUE name violation [clientId=%s]", client.id);
            return ClientsError.DUPLICATE_NAME.toException();
        }
        // Constraint desconocido: log para ops y propagamos sin enmascarar bugs.
        LOG.errorf(ex, "Unhandled DB constraint violation [constraint=%s]", constraintName);
        return ex;
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
