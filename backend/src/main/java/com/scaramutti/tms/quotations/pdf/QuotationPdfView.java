package com.scaramutti.tms.quotations.pdf;

import com.scaramutti.tms.quotations.dto.BankAccount;

import java.util.List;

/**
 * Modelo de vista del PDF de cotizacion: todo ya formateado (montos con simbolo, fechas en
 * texto, montos en letras) para que el template Qute solo renderice strings, sin logica.
 * Lo arma {@link QuotationPdfService} desde el QuotationResponse + los settings de empresa.
 */
public record QuotationPdfView(
    // --- Empresa emisora (system_settings) ---
    String companyName,
    String companyAddress,
    String companyPhone,
    String companyEmail,
    // --- Cabecera ---
    String dateText,      // "Lima, 11 de Marzo del 2026"
    String code,          // "2026-00003"
    String clientName,
    String clientRuc,
    String contactName,
    String contactPhone,  // telefono de contacto ("" si no hay)
    String routeText,     // null si no hay ruta; si no "Lima a Cusco"
    // --- Items (jerarquicos: root + hijos del Integral) ---
    List<ItemRow> items,
    // --- Totales (cuadro azul) ---
    String subtotalText,  // "S/ 15,404.24"
    String igvText,       // "S/ 2,772.76"
    String igvLabel,      // "IGV (18%)"
    String grandTotal,    // "S/ 18,177.00"
    String amountInWords, // "Mil setecientos setenta con 00/100 soles"
    // --- Condiciones comerciales ---
    String currencyCode,
    String paymentTerm,
    String validity,      // "15 días calendario" o ""
    String tentativeDate, // "" si no hay
    // --- Stand-by (vacio si no hay) ---
    List<StandbyRow> standby,
    // --- Observacion para el cliente (null si no hay; se renderiza tras el stand-by) ---
    // NOTA: la observacion INTERNA NO existe en este view a proposito (RN-03): asi es
    // estructuralmente imposible que llegue al PDF. No agregar internalNote aca.
    String clientNote,
    // --- Firma ---
    String signerName,
    String signerPosition,
    // --- Condiciones generales (system_settings) ---
    List<String> terms,           // incluye el marcador "[[BANK_ACCOUNTS]]" (ver BANK_ACCOUNTS_MARKER)
    List<BankAccount> bankAccounts
) {

    /** Fila de la tabla de items. {@code child}=true para hijos del Integral (indentados, sin montos). */
    public record ItemRow(
        String label,        // displayLabel: "1", "1.a", "2"
        int quantity,
        String name,         // serviceType.name
        String cargoSubtext, // null o "EXCAVADORA 326 · 25.900 kg"
        String observations, // null o texto libre del item (campo abierto que el vendedor completa)
        boolean child,
        String net,          // "S/ 1,500.00" o "—" (hijos)
        String igv,          // "S/ 270.00" o "—"
        String total         // "S/ 1,770.00" o "—"
    ) {}

    /** Fila de la tabla de stand-by. */
    public record StandbyRow(String itemLabel, String pricePerDay, boolean includesIgv) {}

    /** Marcador en {@code terms} donde el template inserta la tabla de cuentas bancarias. */
    public static final String BANK_ACCOUNTS_MARKER = "[[BANK_ACCOUNTS]]";
}
