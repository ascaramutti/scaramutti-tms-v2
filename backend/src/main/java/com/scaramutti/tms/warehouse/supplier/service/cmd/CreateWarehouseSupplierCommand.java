package com.scaramutti.tms.warehouse.supplier.service.cmd;

/** name/contactName ya vienen trim()-eados; cadena vacía -> null. ruc/phone pasan tal cual (Bean Validation ya exige el patrón exacto). */
public record CreateWarehouseSupplierCommand(String name, String ruc, String phone, String contactName) {}
