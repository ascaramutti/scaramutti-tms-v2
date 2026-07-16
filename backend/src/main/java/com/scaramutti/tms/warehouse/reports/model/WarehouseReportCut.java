package com.scaramutti.tms.warehouse.reports.model;

/**
 * Corte del reporte de almacen (GET /warehouse/reports), los 4 de la pantalla
 * Reportes. Enum de dominio (vive en {@code warehouse/reports/model/} por
 * convencion del proyecto). Un valor de {@code cut} que no matchee ninguna
 * constante hace que RESTEasy responda 404 vacio (query param tipado que no
 * parsea), no 400.
 *
 * <ul>
 *   <li>{@code BY_UNIT}: consumo por unidad de flota (retiros sin unidad se
 *       agrupan en "Sin unidad asignada").</li>
 *   <li>{@code BY_PERIOD}: consumo por semana (lunes como clave).</li>
 *   <li>{@code BY_PRODUCT}: consumo por producto ({@code count} = unidades
 *       retiradas, no numero de movimientos).</li>
 *   <li>{@code BY_SUPPLIER}: compras por proveedor (facturas activas por
 *       {@code invoiceDate}).</li>
 * </ul>
 */
public enum WarehouseReportCut {
    BY_UNIT,
    BY_PERIOD,
    BY_PRODUCT,
    BY_SUPPLIER
}
