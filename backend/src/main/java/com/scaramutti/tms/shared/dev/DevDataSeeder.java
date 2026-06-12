package com.scaramutti.tms.shared.dev;

import com.scaramutti.tms.auth.service.PasswordService;
import com.scaramutti.tms.catalogs.quotationservicetype.model.QuotationServiceKind;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.PaymentTerm;
import com.scaramutti.tms.shared.entity.QuotationServiceType;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.PaymentTermRepository;
import com.scaramutti.tms.shared.repository.QuotationServiceTypeRepository;
import com.scaramutti.tms.shared.repository.RoleRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;

/**
 * Seed de datos de desarrollo. SOLO corre en perfil dev/test (NO en prod).
 *
 * Garantiza (idempotente):
 *  - DocumentType DNI (si la tabla esta vacia)
 *  - Los 5 roles del sistema (alineados con prod): admin, sales, dispatcher,
 *    general_manager, operations_manager
 *  - Usuarios dev con passwords conocidas:
 *      - admin / Admin1234       (role admin, activo)
 *      - lcampos / Sales1234     (role sales, activo)
 *      - inactivo / Inactivo1234 (role sales, isActive=false, para tests AUTH-002)
 *
 * Cuando la BD viene de un restore de prod, los usuarios admin/lcampos ya existen
 * con sus password hashes reales (desconocidos). En dev forzamos el password al
 * documentado para que el equipo pueda autenticarse. Esto es seguro porque el
 * seeder no corre en prod (@UnlessBuildProfile("prod")).
 */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class DevDataSeeder {

    private static final Logger LOG = Logger.getLogger(DevDataSeeder.class);

    @Inject UserRepository userRepository;
    @Inject WorkerRepository workerRepository;
    @Inject RoleRepository roleRepository;
    @Inject CurrencyRepository currencyRepository;
    @Inject PaymentTermRepository paymentTermRepository;
    @Inject QuotationServiceTypeRepository quotationServiceTypeRepository;
    @Inject PasswordService passwordService;
    @Inject EntityManager entityManager;

    @Transactional
    public void onStart(@Observes StartupEvent startupEvent) {
        Integer dniId = ensureDniDocumentType();
        Role admin = ensureRole("admin", "Administrador del sistema");
        Role sales = ensureRole("sales", "Encargado de Ventas");
        ensureRole("dispatcher", "Coordinador de Operaciones");
        ensureRole("general_manager", "Gerente General");
        ensureRole("operations_manager", "Gerente de Operaciones");

        ensureUser("admin",    "Admin1234",    "Admin",    "TMS",      "00000001", "Administrador del sistema", admin, true,  dniId);
        ensureUser("lcampos",  "Sales1234",    "Luraidis", "Campos",   "00000002", "Ejecutiva de Ventas",       sales, true,  dniId);
        ensureUser("inactivo", "Inactivo1234", "Usuario",  "Inactivo", "00000003", "Inactivo de prueba",        sales, false, dniId);

        ensureCurrency("USD", "$",  "Dólar Estadounidense");
        ensureCurrency("PEN", "S/", "Sol Peruano");

        ensurePaymentTerm("Contado",                              0);
        ensurePaymentTerm("15 días",                              15);
        ensurePaymentTerm("30 días",                              30);
        ensurePaymentTerm("60 días",                              60);
        ensurePaymentTerm("50% adelanto / 50% antes de descarga", 0);

        // Servicios de transporte (prefijo S → kind=SERVICIO)
        ensureQuotationServiceType("SCB", "Servicio de transporte en Cama Baja",                       "Transporte en cama baja");
        ensureQuotationServiceType("SCC", "Servicio de transporte en Cama Cuna",                       "Transporte en cama cuna");
        ensureQuotationServiceType("SPL", "Servicio de transporte en Plataforma",                      "Transporte en plataforma");
        ensureQuotationServiceType("SMO", "Servicio de transporte en Modular",                         "Transporte modular multilinea");
        ensureQuotationServiceType("SPE", "Servicio de transporte en Plataforma Extensible",           "Transporte en plataforma extensible");
        ensureQuotationServiceType("SCH", "Servicio de transporte en Cama Cuna Especial Hidráulica",   "Transporte en cama cuna especial hidráulica");
        ensureQuotationServiceType("SCM", "Servicio de transporte en Cama Cuna Modular Especial",      "Transporte en cama cuna modular especial");
        ensureQuotationServiceType("STR", "Servicio de Tracto",                                        "Servicio de tracto con conductor que engancha carreta del cliente (tránsito limitado)");

        // Alquileres (prefijo A → kind=ALQUILER)
        ensureQuotationServiceType("ACB", "Alquiler de Cama Baja",                                     "Alquiler de equipo cama baja");
        ensureQuotationServiceType("ACC", "Alquiler de Cama Cuna",                                     "Alquiler de equipo cama cuna");
        ensureQuotationServiceType("APL", "Alquiler de Plataforma",                                    "Alquiler de equipo plataforma");
        ensureQuotationServiceType("APE", "Alquiler de Plataforma Extensible",                         "Alquiler de equipo plataforma extensible");
        ensureQuotationServiceType("AMO", "Alquiler de Modular",                                       "Alquiler de equipo modular");
        ensureQuotationServiceType("AGR", "Alquiler de Grúa",                                          "Alquiler de grúa");
        ensureQuotationServiceType("AMN", "Alquiler de Montacargas",                                   "Alquiler de montacargas");
        ensureQuotationServiceType("ATR", "Alquiler de Tracto",                                        "Alquiler de tracto sin conductor, sin limitaciones de uso");
        ensureQuotationServiceType("ALM", "Almacenamiento",                                            "Alquiler de espacio para almacenamiento de carga");

        // Complementarios (prefijo C → kind=COMPLEMENTARIO)
        ensureQuotationServiceType("CES", "Servicio de Escolta",                                       "Servicio de escolta vehicular");
        ensureQuotationServiceType("COP", "Servicio de Operador",                                      "Servicio de operador de maquinaria");
        ensureQuotationServiceType("CSE", "Seguro de Carga",                                           "Seguro para la carga transportada");
        ensureQuotationServiceType("CGR", "Servicio de Grúa",                                          "Servicio de grúa para carga/descarga");
        ensureQuotationServiceType("CMN", "Servicio de Montacargas",                                   "Servicio de montacargas");
        ensureQuotationServiceType("CDE", "Servicio de Desconsolidación con Maniobra",                 "Desconsolidación de carga con maniobra");
        ensureQuotationServiceType("CAP", "Servicio de Apoyo Policial",                                "Servicio de apoyo policial para el resguardo de la carga");

        // Integral (prefijo I → kind=INTEGRAL)
        ensureQuotationServiceType("INT", "Servicio Integral",                                         "Servicio integral con jerarquía padre+hijos (transporte + complementarios en un solo precio con descuento)");

        LOG.info("Dev seed: usuarios garantizados — admin, lcampos, inactivo. "
            + "Monedas garantizadas — USD, PEN. "
            + "Términos de pago garantizados — Contado, 15d, 30d, 60d, 50/50. "
            + "Tipos de servicio cotizable garantizados — 8 servicios (S), 9 alquileres (A), 6 complementarios (C), 1 integral (I) = 24 total.");
    }

    private void ensureCurrency(String code, String symbol, String name) {
        if (currencyRepository.count("code", code) > 0) {
            return;
        }
        Currency currency = new Currency();
        currency.code = code;
        currency.symbol = symbol;
        currency.name = name;
        currency.isActive = true;
        currencyRepository.persist(currency);
    }

    private void ensurePaymentTerm(String name, int days) {
        if (paymentTermRepository.count("name", name) > 0) {
            return;
        }
        PaymentTerm paymentTerm = new PaymentTerm();
        paymentTerm.name = name;
        paymentTerm.days = days;
        paymentTerm.isActive = true;
        paymentTermRepository.persist(paymentTerm);
    }

    /**
     * Garantiza un quotation_service_type. Valida que el code respete la
     * convención del prefijo (S/A/C/I) — si no, falla loudly al startup.
     */
    private void ensureQuotationServiceType(String code, String name, String description) {
        // Valida la convencion del prefijo (tira ApiException CAT-001/CAT-002 si no cumple)
        QuotationServiceKind.fromCode(code);

        if (quotationServiceTypeRepository.count("code", code) > 0) {
            return;
        }
        QuotationServiceType quotationServiceType = new QuotationServiceType();
        quotationServiceType.code = code;
        quotationServiceType.name = name;
        quotationServiceType.description = description;
        quotationServiceType.isActive = true;
        quotationServiceTypeRepository.persist(quotationServiceType);
    }

    @SuppressWarnings("unchecked")
    private Integer ensureDniDocumentType() {
        var rows = entityManager.createNativeQuery("SELECT id FROM document_types WHERE code = 'DNI'").getResultList();
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0)).intValue();
        }
        entityManager.createNativeQuery(
            "INSERT INTO document_types (code, name, max_length, is_active) VALUES ('DNI', 'DNI', 8, true)"
        ).executeUpdate();
        Object newId = entityManager.createNativeQuery("SELECT id FROM document_types WHERE code = 'DNI'")
            .getSingleResult();
        return ((Number) newId).intValue();
    }

    private Role ensureRole(String name, String description) {
        return roleRepository.findByName(name).orElseGet(() -> {
            Role role = new Role();
            role.name = name;
            role.description = description;
            role.isActive = true;
            roleRepository.persist(role);
            return role;
        });
    }

    /**
     * Garantiza un user dev con credenciales conocidas.
     * - Si el user existe (ej. viene del restore de prod): solo actualiza
     *   passwordHash + isActive. Respeta worker/role/nombres reales.
     * - Si no existe: lo crea junto con su worker.
     */
    private void ensureUser(String username, String password,
                            String firstName, String lastName, String documentNumber, String position,
                            Role role, boolean isActive, Integer documentTypeId) {
        var existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            User user = existing.get();
            user.passwordHash = passwordService.hash(password);
            user.isActive = isActive;
            return;
        }
        Worker worker = new Worker();
        worker.firstName = firstName;
        worker.lastName = lastName;
        worker.documentTypeId = documentTypeId;
        worker.documentNumber = documentNumber;
        worker.position = position;
        worker.isActive = true;
        worker.createdAt = OffsetDateTime.now();
        workerRepository.persist(worker);

        User user = new User();
        user.username = username;
        user.passwordHash = passwordService.hash(password);
        user.worker = worker;
        user.role = role;
        user.isActive = isActive;
        user.createdAt = OffsetDateTime.now();
        userRepository.persist(user);
    }
}
