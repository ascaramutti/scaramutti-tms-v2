package com.scaramutti.tms.warehouse.productcategory.service.cmd;

/** name/description ya vienen trim()-eados; cadena vacía -> null (ver WarehouseProductCategoryResourceMapper). */
public record CreateWarehouseProductCategoryCommand(String name, String description) {}
