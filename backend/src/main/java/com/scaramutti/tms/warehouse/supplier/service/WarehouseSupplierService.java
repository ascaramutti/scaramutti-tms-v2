package com.scaramutti.tms.warehouse.supplier.service;

import com.scaramutti.tms.shared.dto.PageResponse;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.warehouse.supplier.dto.WarehouseSupplierResponse;
import com.scaramutti.tms.warehouse.supplier.mapper.WarehouseSupplierServiceMapper;
import com.scaramutti.tms.warehouse.supplier.service.cmd.ListWarehouseSuppliersQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class WarehouseSupplierService {

    @Inject SupplierRepository supplierRepository;
    @Inject WarehouseSupplierServiceMapper warehouseSupplierServiceMapper;

    public PageResponse<WarehouseSupplierResponse> listSuppliers(ListWarehouseSuppliersQuery listWarehouseSuppliersQuery) {
        List<Supplier> suppliers = supplierRepository.searchPaged(listWarehouseSuppliersQuery);
        long totalElements = supplierRepository.countSearch(listWarehouseSuppliersQuery);

        List<WarehouseSupplierResponse> content = warehouseSupplierServiceMapper.toWarehouseSupplierResponseList(suppliers);
        return PageResponse.of(content, listWarehouseSuppliersQuery.page(), listWarehouseSuppliersQuery.size(), totalElements);
    }
}
