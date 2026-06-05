package com.scaramutti.tms.quotations.pdf;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.quotations.dto.QuotationItemResponse;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCargoTypeSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCurrencySummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationPaymentTermSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QuotationPdfServiceTest {

    @Inject
    QuotationPdfService pdfService;

    @Test
    void generates_validPdf_andWritesSample() throws Exception {
        byte[] pdf = pdfService.generate(sampleQuotation());

        assertNotNull(pdf);
        assertTrue(pdf.length > 1000, "el PDF deberia tener contenido");
        assertEquals("%PDF", new String(pdf, 0, 4), "deberia empezar con la firma %PDF");

        // Muestra para revision manual del formato (la valida el usuario).
        Path out = Path.of("target", "cotizacion-sample.pdf");
        Files.write(out, pdf);
        System.out.println(">>> PDF de muestra: " + out.toAbsolutePath());
    }

    @Test
    void generates_fullQuotation_sample() throws Exception {
        byte[] pdf = pdfService.generate(fullQuotation());

        assertNotNull(pdf);
        assertTrue(pdf.length > 1000, "el PDF deberia tener contenido");
        assertEquals("%PDF", new String(pdf, 0, 4), "deberia empezar con la firma %PDF");

        // Muestra al tope: 5 items root (max), Integral con 4 hijos, todos los campos llenos.
        Path out = Path.of("target", "cotizacion-full-sample.pdf");
        Files.write(out, pdf);
        System.out.println(">>> PDF full: " + out.toAbsolutePath());
    }

    /** Cotizacion al tope: 5 items root (MAX_ROOT_ITEMS), Integral con 4 hijos, todos los campos. */
    private QuotationResponse fullQuotation() {
        var igv = new BigDecimal("18.00");

        var stInt = new QuotationServiceTypeSummary(24, "INT", "Servicio Integral", "INTEGRAL");
        var stCamaBaja = new QuotationServiceTypeSummary(3, "SCB", "Servicio de transporte en Cama Baja", "SERVICIO");
        var stCamaCuna = new QuotationServiceTypeSummary(4, "SCC", "Servicio de transporte en Cama Cuna", "SERVICIO");
        var stEscolta = new QuotationServiceTypeSummary(18, "CES", "Servicio de Escolta", "COMPLEMENTARIO");
        var stCustodia = new QuotationServiceTypeSummary(19, "CCU", "Servicio de Custodia armada", "COMPLEMENTARIO");
        var stMontacarga = new QuotationServiceTypeSummary(10, "SMC", "Servicio de Montacarga", "SERVICIO");
        var stSeguro = new QuotationServiceTypeSummary(20, "CSE", "Seguro de Carga", "COMPLEMENTARIO");
        var stGrua = new QuotationServiceTypeSummary(11, "SGR", "Servicio de Grúa telescópica", "SERVICIO");

        var excavadora = new QuotationCargoTypeSummary(7, "EXCAVADORA 336");
        var grupoElect = new QuotationCargoTypeSummary(8, "GRUPO ELECTRÓGENO 500KVA");
        var tractor = new QuotationCargoTypeSummary(9, "TRACTOR ORUGA D8");

        // Root 1: Servicio Integral con 4 hijos (transporte + 3 complementarios).
        var h1a = new QuotationItemResponse(2L, 1L, 2, "1.a", stCamaBaja, excavadora,
            "Carga sobredimensionada, requiere permiso especial", new BigDecimal("36500"),
            new BigDecimal("10.20"), new BigDecimal("3.40"), new BigDecimal("3.60"),
            1, BigDecimal.ZERO, new BigDecimal("950"), igv, BigDecimal.ZERO, null, null, null);
        var h1b = new QuotationItemResponse(3L, 1L, 3, "1.b", stEscolta, null, null,
            null, null, null, null, 1, BigDecimal.ZERO, new BigDecimal("380"), igv, BigDecimal.ZERO, null,
            new QuotationStandbyCostResponse(1L, new BigDecimal("150.00"), false), null);
        var h1c = new QuotationItemResponse(4L, 1L, 4, "1.c", stCustodia, null, null,
            null, null, null, null, 1, BigDecimal.ZERO, new BigDecimal("420"), igv, BigDecimal.ZERO, null,
            new QuotationStandbyCostResponse(2L, new BigDecimal("180.00"), true), null);
        var h1d = new QuotationItemResponse(5L, 1L, 5, "1.d", stMontacarga, null, null,
            null, null, null, null, 1, BigDecimal.ZERO, new BigDecimal("300"), igv, BigDecimal.ZERO, null, null, null);
        var integral = new QuotationItemResponse(1L, null, 1, "1", stInt, null, null,
            null, null, null, null, 1, new BigDecimal("18500.00"), null,
            igv, new BigDecimal("18500.00"), null, null, List.of(h1a, h1b, h1c, h1d));

        // Root 2: transporte Cama Cuna con carga, dims y stand-by.
        var camaCuna = new QuotationItemResponse(6L, null, 6, "2", stCamaCuna, grupoElect, null,
            new BigDecimal("12800"), new BigDecimal("6.10"), new BigDecimal("2.40"), new BigDecimal("2.55"),
            1, new BigDecimal("4500.00"), new BigDecimal("3800"), igv, new BigDecimal("4500.00"), null,
            new QuotationStandbyCostResponse(3L, new BigDecimal("220.00"), true), null);

        // Root 3: transporte Cama Baja con carga y dims.
        var camaBaja = new QuotationItemResponse(7L, null, 7, "3", stCamaBaja, tractor, null,
            new BigDecimal("38500"), new BigDecimal("8.50"), new BigDecimal("3.20"), new BigDecimal("3.30"),
            2, new BigDecimal("3200.00"), new BigDecimal("2900"), igv, new BigDecimal("6400.00"), null, null, null);

        // Root 4: Seguro de Carga con monto asegurado.
        var seguro = new QuotationItemResponse(8L, null, 8, "4", stSeguro, null,
            "Valor asegurado: S/ 250,000.00 — cobertura todo riesgo (transporte, carga y descarga)",
            null, null, null, null, 1, new BigDecimal("280.00"), null,
            igv, new BigDecimal("280.00"), new BigDecimal("250000.00"), null, null);

        // Root 5: Grúa telescópica con stand-by.
        var grua = new QuotationItemResponse(9L, null, 9, "5", stGrua, null, null,
            null, null, null, null, 1, new BigDecimal("1500.00"), new BigDecimal("1300"),
            igv, new BigDecimal("1500.00"), null,
            new QuotationStandbyCostResponse(4L, new BigDecimal("350.00"), false), null);

        var admin = new UserResponse(1, "jperez", "Juan Pérez Quispe", "Ejecutivo Comercial", "sales", true);
        // Totales: subtotal = 18500 + 4500 + 6400 + 280 + 1500 = 31180; igv 18% = 5612.40; total = 36792.40
        return new QuotationResponse(
            200L, "2026-00099", QuotationType.TRANSPORTE, QuotationStatus.DRAFT,
            new QuotationClientSummary(2, "MINERA LAS BAMBAS S.A.C.", "20603451287"),
            "Carlos Huamán Flores", "987123456",
            new QuotationCurrencySummary(1, "PEN", "S/"),
            new QuotationPaymentTermSummary(2, "Crédito 30 días", 30),
            LocalDate.parse("2026-04-20"), 30,
            OffsetDateTime.parse("2026-04-10T00:00:00Z"), false,
            "Lima (Callao)", "Apurímac (Challhuahuacho)",
            new BigDecimal("31180.00"), new BigDecimal("5612.40"), new BigDecimal("36792.40"),
            List.of(integral, camaCuna, camaBaja, seguro, grua),
            admin, admin,
            OffsetDateTime.parse("2026-03-11T10:00:00-05:00"),
            OffsetDateTime.parse("2026-03-11T10:00:00-05:00")
        );
    }

    /** Cotizacion de muestra: Servicio Integral (transporte con carga + escolta con stand-by) + seguro. */
    private QuotationResponse sampleQuotation() {
        var stInt = new QuotationServiceTypeSummary(24, "INT", "Servicio Integral", "INTEGRAL");
        var stTransporte = new QuotationServiceTypeSummary(3, "SCB", "Servicio de transporte en Cama Baja", "SERVICIO");
        var stEscolta = new QuotationServiceTypeSummary(18, "CES", "Servicio de Escolta", "COMPLEMENTARIO");
        var stSeguro = new QuotationServiceTypeSummary(20, "CSE", "Seguro de Carga", "COMPLEMENTARIO");
        var cargo = new QuotationCargoTypeSummary(7, "EXCAVADORA 326");

        var igv = new BigDecimal("18.00");
        var child1 = new QuotationItemResponse(2L, 1L, 2, "1.a", stTransporte, cargo, null,
            new BigDecimal("25900"), new BigDecimal("9.94"), new BigDecimal("3.34"), new BigDecimal("3.6"),
            1, BigDecimal.ZERO, new BigDecimal("900"),
            igv, BigDecimal.ZERO, null, null, null);
        var child2 = new QuotationItemResponse(3L, 1L, 3, "1.b", stEscolta, null, null,
            null, null, null, null, 1, BigDecimal.ZERO, new BigDecimal("371.19"),
            igv, BigDecimal.ZERO, null,
            new QuotationStandbyCostResponse(1L, new BigDecimal("150.00"), false), null);
        var integral = new QuotationItemResponse(1L, null, 1, "1", stInt, null, null,
            null, null, null, null, 1, new BigDecimal("15254.24"), null,
            igv, new BigDecimal("15254.24"), null, null, List.of(child1, child2));
        var seguro = new QuotationItemResponse(4L, null, 4, "2", stSeguro, null, null,
            null, null, null, null, 1, new BigDecimal("150.00"), null,
            igv, new BigDecimal("150.00"), null, null, null);

        var admin = new UserResponse(1, "admin", "Administrador Sistema", "Administrador del Sistema", "admin", true);
        return new QuotationResponse(
            100L, "2026-00042", QuotationType.TRANSPORTE, QuotationStatus.DRAFT,
            new QuotationClientSummary(1, "IPHD PRUEBA", "20518989889"),
            "ASQ", "987654321",
            new QuotationCurrencySummary(1, "PEN", "S/"),
            new QuotationPaymentTermSummary(1, "Contado", 0),
            null, 15,
            OffsetDateTime.parse("2026-06-15T00:00:00Z"), false,
            "Lima", "Arequipa",
            new BigDecimal("15404.24"), new BigDecimal("2772.76"), new BigDecimal("18177.00"),
            List.of(integral, seguro),
            admin, admin,
            OffsetDateTime.parse("2026-03-11T10:00:00-05:00"),
            OffsetDateTime.parse("2026-03-11T10:00:00-05:00")
        );
    }
}
