package com.scaramutti.tms.shared.dev;

import com.scaramutti.tms.auth.service.PasswordService;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
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
 * Crea (idempotente):
 *  - DocumentType DNI (si la tabla esta vacia)
 *  - Role: ADMINISTRADOR, VENDEDOR
 *  - Worker + User: admin / Admin1234 (activo)
 *  - Worker + User: lcampos / Vendedor1234 (activo)
 *  - Worker + User: inactivo / Inactivo1234 (isActive=false, para tests de USER_INACTIVE)
 */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class DevDataSeeder {

    private static final Logger LOG = Logger.getLogger(DevDataSeeder.class);

    @Inject UserRepository userRepository;
    @Inject WorkerRepository workerRepository;
    @Inject RoleRepository roleRepository;
    @Inject PasswordService passwordService;
    @Inject EntityManager entityManager;

    @Transactional
    public void onStart(@Observes StartupEvent startupEvent) {
        Integer dniId = ensureDniDocumentType();
        Role admin = ensureRole("ADMINISTRADOR", "Administrador del sistema");
        Role vendedor = ensureRole("VENDEDOR", "Vendedor / Ejecutiva de Ventas");

        ensureUser("admin",    "Admin1234",     "Admin",   "TMS",      "00000001", "Administrador del sistema", admin,    true,  dniId);
        ensureUser("lcampos",  "Vendedor1234",  "Luraidis","Campos",   "00000002", "Ejecutiva de Ventas",       vendedor, true,  dniId);
        ensureUser("inactivo", "Inactivo1234",  "Usuario", "Inactivo", "00000003", "Inactivo de prueba",        vendedor, false, dniId);

        LOG.info("Dev seed: usuarios garantizados — admin, lcampos, inactivo");
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

    /** Crea user+worker solo si no existe; idempotente para correr en cada arranque. */
    private void ensureUser(String username, String password,
                            String firstName, String lastName, String documentNumber, String position,
                            Role role, boolean isActive, Integer documentTypeId) {
        if (userRepository.findByUsername(username).isPresent()) {
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
