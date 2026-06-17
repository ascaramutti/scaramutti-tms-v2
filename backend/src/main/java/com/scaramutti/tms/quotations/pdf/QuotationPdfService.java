package com.scaramutti.tms.quotations.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.scaramutti.tms.quotations.dto.CompanyPdfSettings;
import com.scaramutti.tms.quotations.dto.QuotationItemResponse;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.service.PdfSettingsService;
import com.scaramutti.tms.quotations.util.AmountToWords;
import com.scaramutti.tms.shared.exception.CommonError;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Genera el PDF de cotizacion: arma un {@link QuotationPdfView} (todo formateado) desde el
 * {@link QuotationResponse} + los datos de empresa (system_settings) + el monto en letras,
 * lo renderiza con un template Qute (HTML/CSS) y lo convierte a PDF con openhtmltopdf.
 */
@ApplicationScoped
public class QuotationPdfService {

    private static final Logger LOG = Logger.getLogger(QuotationPdfService.class);

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Inject
    @Location("quotation-pdf")
    Template template;

    @Inject PdfSettingsService settingsService;

    /** Genera el PDF de la cotizacion como bytes. */
    public byte[] generate(QuotationResponse quotation) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CompanyPdfSettings company = settingsService.forPdf();
            QuotationPdfView view = buildView(quotation, company);
            String html = template
                .data("view", view)
                .data("logo", logoDataUri())
                .data("bankMarker", QuotationPdfView.BANK_ACCOUNTS_MARKER)
                .render();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            // Un fallo de armado/render no debe propagar una excepcion cruda (500 fuera
            // del contrato Problem, con riesgo de filtrar internals). Se loguea la causa
            // y se responde 500 dentro del contrato (COM-500), igual que el resto del API.
            LOG.errorf(e, "Error generando el PDF de la cotizacion %s", quotation.code());
            throw CommonError.INTERNAL_ERROR.toException(
                "No se pudo generar el PDF de la cotización " + quotation.code());
        }
    }

    // ---------- View assembly ----------------------------------------------

    private QuotationPdfView buildView(QuotationResponse q, CompanyPdfSettings company) {
        String symbol = q.currency() != null ? q.currency().symbol() : "";
        String currencyCode = q.currency() != null ? q.currency().code() : "";
        return new QuotationPdfView(
            company.legalName(), company.address(), company.phone(), company.email(),
            "Lima, " + formatDateSpanish(q.createdAt()),
            q.code(),
            q.client() != null ? q.client().name() : "",
            q.client() != null ? q.client().ruc() : "",
            q.contactName(),
            q.contactPhone() != null ? q.contactPhone() : "",
            routeText(q),
            buildItemRows(q, symbol),
            money(symbol, q.totalSubtotal()),
            money(symbol, q.totalIgv()),
            igvLabel(q),
            money(symbol, q.totalAmount()),
            AmountToWords.amountToWords(q.totalAmount(), currencyCode),
            currencyCode,
            q.paymentTerm() != null ? q.paymentTerm().name() : "",
            (q.validityDays() != null && q.validityDays() > 0) ? q.validityDays() + " días calendario" : null,
            q.tentativeServiceDate() != null
                ? q.tentativeServiceDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : null,
            buildStandbyRows(q, symbol),
            q.clientNote(),
            q.createdBy() != null ? q.createdBy().fullName() : "",
            q.createdBy() != null ? q.createdBy().position() : "",
            company.terms(),
            company.bankAccounts()
        );
    }

    private List<QuotationPdfView.ItemRow> buildItemRows(QuotationResponse q, String symbol) {
        List<QuotationPdfView.ItemRow> rows = new ArrayList<>();
        for (QuotationItemResponse item : q.items()) {
            rows.add(rootRow(item, symbol));
            if (item.children() != null) {
                for (QuotationItemResponse child : item.children()) {
                    rows.add(childRow(child));
                }
            }
        }
        return rows;
    }

    private QuotationPdfView.ItemRow rootRow(QuotationItemResponse item, String symbol) {
        BigDecimal subtotal = item.subtotal() != null ? item.subtotal() : BigDecimal.ZERO;
        BigDecimal igvPct = item.igvPercentage() != null ? item.igvPercentage() : BigDecimal.ZERO;
        // Pedido del negocio (2026-06-12): las columnas P. Unit. e I.G.V. muestran
        // valores UNITARIOS (precio unitario y su IGV); P. Total sigue siendo el
        // total de LÍNEA (cant. × unitario + IGV), coherente con el cuadro de totales.
        BigDecimal unitPrice = item.unitPrice() != null ? item.unitPrice() : BigDecimal.ZERO;
        BigDecimal unitIgv = unitPrice.multiply(igvPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal lineIgv = subtotal.multiply(igvPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(lineIgv);
        return new QuotationPdfView.ItemRow(
            item.displayLabel(), item.quantity() != null ? item.quantity() : 0,
            item.serviceType().name(), cargoSubtext(item), observationsText(item), false,
            money(symbol, unitPrice), money(symbol, unitIgv), money(symbol, total)
        );
    }

    private QuotationPdfView.ItemRow childRow(QuotationItemResponse child) {
        return new QuotationPdfView.ItemRow(
            child.displayLabel(), child.quantity() != null ? child.quantity() : 0,
            child.serviceType().name(), cargoSubtext(child), observationsText(child), true,
            "—", "—", "—"
        );
    }

    private List<QuotationPdfView.StandbyRow> buildStandbyRows(QuotationResponse q, String symbol) {
        List<QuotationPdfView.StandbyRow> rows = new ArrayList<>();
        for (QuotationItemResponse item : q.items()) {
            addStandby(rows, item, symbol);
            if (item.children() != null) {
                for (QuotationItemResponse child : item.children()) {
                    addStandby(rows, child, symbol);
                }
            }
        }
        return rows;
    }

    private void addStandby(List<QuotationPdfView.StandbyRow> rows, QuotationItemResponse item, String symbol) {
        if (item.standby() != null) {
            rows.add(new QuotationPdfView.StandbyRow(
                item.displayLabel() + " (" + item.serviceType().name() + ")",
                money(symbol, item.standby().pricePerDay()),
                Boolean.TRUE.equals(item.standby().includesIgv())
            ));
        }
    }

    private String cargoSubtext(QuotationItemResponse item) {
        List<String> parts = new ArrayList<>();
        if (item.cargoType() != null && item.cargoType().name() != null) {
            parts.add(item.cargoType().name());
        }
        if (item.weightKg() != null) {
            parts.add(new DecimalFormat("#,##0").format(item.weightKg()) + " kg");
        }
        if (item.lengthMeters() != null && item.widthMeters() != null && item.heightMeters() != null) {
            parts.add(plain(item.lengthMeters()) + " × " + plain(item.widthMeters())
                + " × " + plain(item.heightMeters()) + " m");
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private String observationsText(QuotationItemResponse item) {
        return (item.observations() != null && !item.observations().isBlank()) ? item.observations() : null;
    }

    /**
     * Label del IGV del cuadro de totales. Asume IGV uniforme entre items (toma el
     * porcentaje del primer item): hoy el IGV es nacional/unico, asi que el monto
     * sumado ({@code totalIgv}) y el porcentaje del label son coherentes. Si en el
     * futuro coexistieran items con IGV distinto (snapshot historico mixto), el
     * porcentaje del label dejaria de representar al total y habria que omitirlo.
     */
    private String igvLabel(QuotationResponse q) {
        if (q.items() == null || q.items().isEmpty() || q.items().get(0).igvPercentage() == null) {
            return "IGV";
        }
        return "IGV (" + q.items().get(0).igvPercentage().stripTrailingZeros().toPlainString() + "%)";
    }

    private String routeText(QuotationResponse q) {
        boolean hasRoute = (q.origin() != null && !q.origin().isBlank())
            || (q.destination() != null && !q.destination().isBlank());
        if (!hasRoute) return null;
        String origin = (q.origin() != null && !q.origin().isBlank()) ? q.origin() : "—";
        String destination = (q.destination() != null && !q.destination().isBlank()) ? q.destination() : "—";
        return origin + " a " + destination;
    }

    private String money(String symbol, BigDecimal amount) {
        BigDecimal value = amount != null ? amount : BigDecimal.ZERO;
        return symbol + " " + new DecimalFormat("#,##0.00").format(value);
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String formatDateSpanish(OffsetDateTime dateTime) {
        if (dateTime == null) return "";
        String formatted = dateTime.atZoneSameInstant(LIMA)
            .format(DateTimeFormatter.ofPattern("dd 'de' MMMM 'del' yyyy", new Locale("es")));
        String[] parts = formatted.split(" ");
        if (parts.length >= 3) {
            parts[2] = parts[2].substring(0, 1).toUpperCase() + parts[2].substring(1);
        }
        return String.join(" ", parts);
    }

    private String logoDataUri() {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("logo.jpeg")) {
            if (is == null) {
                return null;
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }
}
