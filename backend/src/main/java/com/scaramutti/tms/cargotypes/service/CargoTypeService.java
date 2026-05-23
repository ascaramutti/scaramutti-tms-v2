package com.scaramutti.tms.cargotypes.service;

import com.scaramutti.tms.cargotypes.CargoTypesError;
import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.mapper.CargoTypeServiceMapper;
import com.scaramutti.tms.cargotypes.service.cmd.CreateCargoTypeCommand;
import com.scaramutti.tms.cargotypes.service.cmd.ListCargoTypesQuery;
import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CargoTypeService {

    private static final Logger LOG = Logger.getLogger(CargoTypeService.class);

    @Inject CargoTypeRepository cargoTypeRepository;
    @Inject CargoTypeServiceMapper cargoTypeServiceMapper;

    /**
     * Bulk fetch por ids. UNA sola query (WHERE id IN). NO valida que todos
     * los ids existan — el caller compara la lista devuelta vs los ids pedidos.
     * Método interno, sin endpoint REST asociado.
     */
    public List<CargoTypeResponse> findByIds(Set<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        List<CargoType> cargoTypes = cargoTypeRepository.list("id in ?1", new ArrayList<>(ids));
        return cargoTypeServiceMapper.toCargoTypeResponseList(cargoTypes);
    }

    /**
     * Listado paginado con busqueda fuzzy opcional. Read-only, no requiere
     * @Transactional (Quarkus abre tx implicita si Hibernate la necesita).
     *
     * El Query viene ya normalizado desde el CargoTypeResourceMapper
     * (q trimmed + uppercased). El service solo orquesta entre repo y mapper.
     */
    public PageResponse<CargoTypeResponse> listCargoTypes(ListCargoTypesQuery listCargoTypesQuery) {
        List<CargoType> cargoTypes = cargoTypeRepository.searchPaged(listCargoTypesQuery);
        long totalElements = cargoTypeRepository.countSearch(listCargoTypesQuery);

        List<CargoTypeResponse> content = cargoTypeServiceMapper.toCargoTypeResponseList(cargoTypes);
        return PageResponse.of(content, listCargoTypesQuery.page(), listCargoTypesQuery.size(), totalElements);
    }

    /**
     * Crea un cargo type al vuelo. Patron identico a ClientService.createClient
     * pero simplificado: solo 1 chequeo de duplicado (name) en vez de 2 (ruc + name).
     *
     * Flow:
     *  1. Defense-in-depth: name no puede llegar vacio al service (Bean Validation
     *     + mapper deberian garantizarlo, pero validamos por si algun caller
     *     bypassea el mapper).
     *  2. Query previo para detectar duplicados (happy path).
     *  3. Persist + flush dentro de la tx, con catch para traducir
     *     ConstraintViolationException (race condition) a CGT-001 con logging.
     */
    @Transactional
    public CargoTypeResponse createCargoType(CreateCargoTypeCommand createCargoTypeCommand) {
        validatePostTrim(createCargoTypeCommand.name());
        validateNoDuplicates(createCargoTypeCommand);

        CargoType cargoType = cargoTypeServiceMapper.toCargoTypeEntity(createCargoTypeCommand);
        persistOrTranslateDuplicate(cargoType);

        return cargoTypeServiceMapper.toCargoTypeResponse(cargoType);
    }

    /**
     * Guarda invariante: el name no puede llegar vacio al service.
     * Por el flow REST normal, CargoTypeResourceMapper.trimUpperOrNull ya convierte
     * "   " → null antes de armar el Command, asi que el branch isEmpty() aqui
     * solo dispara para callers que bypassen el mapper. Defense-in-depth.
     */
    private void validatePostTrim(String name) {
        if (name == null || name.isEmpty()) {
            throw CommonError.VALIDATION_FAILED.toException();
        }
    }

    /**
     * Query previo para detectar duplicado antes del INSERT.
     * El frontend conoce el name que envio, el detail generico del enum
     * es suficiente — el frontend renderiza el mensaje contextual.
     */
    private void validateNoDuplicates(CreateCargoTypeCommand createCargoTypeCommand) {
        if (cargoTypeRepository.existsByName(createCargoTypeCommand.name())) {
            throw CargoTypesError.DUPLICATE_NAME.toException();
        }
    }

    /**
     * Persiste + flush dentro de la tx. Cubre la race condition donde dos
     * requests pasan validateNoDuplicates simultaneamente: el segundo INSERT
     * viola el UNIQUE de Postgres y Hibernate tira ConstraintViolationException.
     * Sin este catch caeriamos a 500 sin Problem ni code.
     */
    private void persistOrTranslateDuplicate(CargoType cargoType) {
        try {
            cargoTypeRepository.persist(cargoType);
            cargoTypeRepository.flush();
        } catch (PersistenceException ex) {
            ConstraintViolationException cve = extractConstraintViolation(ex);
            if (cve == null) {
                throw ex;
            }
            String constraintName = cve.getConstraintName();
            if (constraintName != null && constraintName.contains("name")) {
                LOG.warnf("Race condition: UNIQUE name violation [name=%s]", cargoType.name);
                throw CargoTypesError.DUPLICATE_NAME.toException();
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
