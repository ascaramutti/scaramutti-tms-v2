package com.scaramutti.tms.shared.dev;

import com.scaramutti.tms.auth.service.PasswordService;
import com.scaramutti.tms.shared.entity.Currency;
import com.scaramutti.tms.shared.entity.PaymentTerm;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.PaymentTermRepository;
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

        LOG.info("Dev seed: usuarios garantizados — admin, lcampos, inactivo. "
            + "Monedas garantizadas — USD, PEN. "
            + "Términos de pago garantizados — Contado, 15d, 30d, 60d, 50/50.");
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
