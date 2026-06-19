package com.scaramutti.tms.quotations.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaramutti.tms.quotations.dto.BankAccount;
import com.scaramutti.tms.quotations.dto.CompanyPdfSettings;
import com.scaramutti.tms.shared.repository.SystemSettingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Lee de system_settings los datos de la empresa emisora + cuentas bancarias que el
 * PDF de cotizacion necesita. Editables sin redeploy (seed manual con db/seed_system_settings.sql
 * en dev y prod). Lectura directa: el PDF es on-demand y el ETag/304 del endpoint ya reduce la
 * frecuencia, asi que 1 query por PDF es aceptable.
 */
@ApplicationScoped
public class PdfSettingsService {

    private static final Logger LOG = Logger.getLogger(PdfSettingsService.class);

    static final String KEY_LEGAL_NAME = "company.legal_name";
    static final String KEY_ADDRESS = "company.address";
    static final String KEY_PHONE = "company.phone";
    static final String KEY_EMAIL = "company.email";
    static final String KEY_BANK_ACCOUNTS = "quotation.pdf_bank_accounts";

    @Inject SystemSettingRepository repository;
    @Inject ObjectMapper objectMapper;

    /** Datos de empresa / bancos para el PDF (una sola query a system_settings). */
    public CompanyPdfSettings forPdf() {
        Map<String, String> settings = repository.findAllAsMap();
        return new CompanyPdfSettings(
            settings.getOrDefault(KEY_LEGAL_NAME, ""),
            settings.getOrDefault(KEY_ADDRESS, ""),
            settings.getOrDefault(KEY_PHONE, ""),
            settings.getOrDefault(KEY_EMAIL, ""),
            parseJsonList(settings.get(KEY_BANK_ACCOUNTS), new TypeReference<List<BankAccount>>() {}, KEY_BANK_ACCOUNTS)
        );
    }

    /** Parsea un value JSON a lista; lista vacia (con warning) si falta o es invalido — el PDF
     * se genera igual sin esa seccion, no falla por un setting mal cargado. */
    private <T> List<T> parseJsonList(String value, TypeReference<List<T>> type, String key) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception e) {
            LOG.warnf("system_settings['%s'] no es un JSON valido, se ignora en el PDF: %s", key, e.getMessage());
            return List.of();
        }
    }
}
