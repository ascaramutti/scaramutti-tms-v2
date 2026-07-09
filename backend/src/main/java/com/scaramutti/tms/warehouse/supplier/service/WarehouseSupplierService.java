package com.scaramutti.tms.warehouse.supplier.service;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.warehouse.WarehouseError;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierResponse;
import com.scaramutti.tms.warehouse.supplier.mapper.WarehouseSupplierServiceMapper;
import com.scaramutti.tms.warehouse.supplier.service.cmd.CreateWarehouseSupplierCommand;
import com.scaramutti.tms.warehouse.supplier.service.cmd.ListWarehouseSuppliersQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Patron identico a ClientService (2 campos unicos: name y ruc), con una
 * diferencia: ruc es nullable en almacen.suppliers (a diferencia de
 * clients.ruc, NOT NULL) — el chequeo de duplicado de ruc solo corre si vino.
 */
@ApplicationScoped
public class WarehouseSupplierService {

    private static final Logger LOG = Logger.getLogger(WarehouseSupplierService.class);

    @Inject SupplierRepository supplierRepository;
    @Inject WarehouseSupplierServiceMapper warehouseSupplierServiceMapper;

    public PageResponse<WarehouseSupplierResponse> listSuppliers(ListWarehouseSuppliersQuery listWarehouseSuppliersQuery) {
        List<Supplier> suppliers = supplierRepository.searchPaged(listWarehouseSuppliersQuery);
        long totalElements = supplierRepository.countSearch(listWarehouseSuppliersQuery);

        List<WarehouseSupplierResponse> content = warehouseSupplierServiceMapper.toWarehouseSupplierResponseList(suppliers);
        return PageResponse.of(content, listWarehouseSuppliersQuery.page(), listWarehouseSuppliersQuery.size(), totalElements);
    }

    @Transactional
    public WarehouseSupplierResponse createSupplier(CreateWarehouseSupplierCommand createWarehouseSupplierCommand) {
        validatePostTrim(createWarehouseSupplierCommand.name());
        validateNoDuplicates(createWarehouseSupplierCommand);

        Supplier supplier = warehouseSupplierServiceMapper.toSupplierEntity(createWarehouseSupplierCommand);
        persistOrTranslateDuplicate(supplier);

        return warehouseSupplierServiceMapper.toWarehouseSupplierResponse(supplier);
    }

    private void validatePostTrim(String name) {
        if (name == null || name.isEmpty()) {
            throw CommonError.VALIDATION_FAILED.toException();
        }
    }

    /**
     * ruc es nullable en BD — el chequeo de duplicado solo tiene sentido si
     * vino un valor (existsByRucIgnoreCase(null) nunca matchearia nada en
     * SQL, pero llamarla igual seria una consulta inutil y confusa de leer).
     */
    private void validateNoDuplicates(CreateWarehouseSupplierCommand createWarehouseSupplierCommand) {
        if (supplierRepository.existsByNameIgnoreCase(createWarehouseSupplierCommand.name())) {
            throw WarehouseError.SUPPLIER_NAME_DUPLICATED.toException();
        }
        if (createWarehouseSupplierCommand.ruc() != null
                && supplierRepository.existsByRucIgnoreCase(createWarehouseSupplierCommand.ruc())) {
            throw WarehouseError.SUPPLIER_RUC_DUPLICATED.toException();
        }
    }

    /**
     * Cubre la race condition donde dos requests pasan validateNoDuplicates
     * simultaneamente. Los indices funcionales uq_suppliers_name_ci /
     * uq_suppliers_ruc_ci (V004) garantizan que Postgres rechaza el segundo
     * INSERT; los constraints autogenerados de los UNIQUE planos de V002
     * tambien contienen "name"/"ruc" y caen en la misma rama.
     */
    private void persistOrTranslateDuplicate(Supplier supplier) {
        try {
            supplierRepository.persist(supplier);
            supplierRepository.flush();
        } catch (PersistenceException ex) {
            ConstraintViolationException cve = extractConstraintViolation(ex);
            if (cve == null) {
                throw ex;
            }
            String constraintName = cve.getConstraintName();
            if (constraintName != null && constraintName.contains("name")) {
                LOG.warnf("Race condition: UNIQUE name violation [name=%s]", supplier.name);
                throw WarehouseError.SUPPLIER_NAME_DUPLICATED.toException();
            }
            if (constraintName != null && constraintName.contains("ruc")) {
                LOG.warnf("Race condition: UNIQUE ruc violation [ruc=%s]", supplier.ruc);
                throw WarehouseError.SUPPLIER_RUC_DUPLICATED.toException();
            }
            LOG.errorf(ex, "Unhandled DB constraint violation [constraint=%s]", constraintName);
            throw ex;
        }
    }

    private ConstraintViolationException extractConstraintViolation(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) {
            return cve;
        }
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? cve : null;
    }
}
