package com.scaramutti.tms.support;

import com.scaramutti.tms.shared.entity.CargoType;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.repository.CargoTypeRepository;
import com.scaramutti.tms.shared.repository.ClientRepository;
import com.scaramutti.tms.shared.repository.CurrencyRepository;
import com.scaramutti.tms.shared.repository.PaymentTermRepository;
import com.scaramutti.tms.shared.repository.QuotationServiceTypeRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixtures herméticos para los tests de integración de cotizaciones y cargo-types
 * (follow-up D-1). Reemplaza la dependencia de la dev-DB compartida: en vez de
 * asumir los ids fijos del restore de prod ({@code CLIENT_ID=1}, {@code CURRENCY_ID=1},
 * {@code ST_INT=24}...), este helper:
 *  - RESUELVE por clave natural los catálogos que el DevDataSeeder SÍ siembra de
 *    forma determinista (currencies por code, payment_terms por name,
 *    quotation_service_types por code, users por username);
 *  - SIEMBRA con fixtures sintéticos {@code ZTEST_} lo que NO se siembra (clients,
 *    cargo_types), con name/ruc únicos por corrida.
 * Así la suite pasa igual en una DB virgen (lo que ve el CI) que en la dev-DB.
 *
 * Molde de siembra idéntico a los tests de warehouse: prefijo {@code ZTEST_} +
 * {@link QuarkusTransaction} + limpieza por prefijo en {@code @AfterEach}.
 */
@ApplicationScoped
public class HermeticTestData {

    public static final String PREFIX = "ZTEST_";

    // Contador de proceso para claves únicas (name/ruc) entre tests de la corrida.
    private static final AtomicLong SEQ = new AtomicLong(0);

    @Inject ClientRepository clientRepository;
    @Inject CargoTypeRepository cargoTypeRepository;
    @Inject CurrencyRepository currencyRepository;
    @Inject PaymentTermRepository paymentTermRepository;
    @Inject QuotationServiceTypeRepository quotationServiceTypeRepository;
    @Inject UserRepository userRepository;
    @Inject EntityManager entityManager;

    // ---------- Resolver ids de catálogos SEMBRADOS por clave natural ----------

    public int currencyId(String code) {
        return currencyRepository.find("code", code).singleResultOptional()
            .orElseThrow(() -> new IllegalStateException("Moneda sembrada no encontrada: code=" + code)).id;
    }

    /**
     * Moneda SINTÉTICA y descartable, ACTIVA, para los casos que necesitan darla de baja.
     *
     * <p>Se siembra POR CASO, no en el {@code @BeforeEach} de la clase: mientras existe es una
     * moneda activa más en el catálogo que el wizard del sistema anterior lee en vivo, así que la
     * ventana en la que alguien podría elegirla tiene que durar lo mínimo. Se borra con
     * {@link #cleanupDisposableCurrency()}, que llama solo la clase que la sembró.
     *
     * <p>Existe porque `public.currencies` es un catálogo SEMBRADO que la base de dev comparte con
     * el sistema anterior: apagar PEN o USD ahí, aunque sea por un instante, rompe el wizard de
     * cotizaciones de v1 y media suite de v2 si la corrida se corta antes de restituirlas. Con una
     * moneda propia, un corte no rompe nada de v1: deja una fila de más —ACTIVA, o sea
     * seleccionable en su wizard hasta la próxima corrida, que la borra— en vez de dejar apagada
     * una moneda que se usa de verdad.
     *
     * <p>Es DISTINTA de la `XTS` de {@code CurrenciesResourceTest} a propósito, aunque las dos
     * sean sintéticas: aquella se siembra INACTIVA porque prueba el filtro de inactivas, y esta
     * tiene que nacer ACTIVA porque se le crean servicios encima. Compartir la fila haría que cada
     * suite dejara a la otra en el estado equivocado, y el orden de ejecución no está garantizado.
     *
     * <p>El estado se NORMALIZA al reusarla, no se hereda: si una corrida murió con la moneda
     * apagada, la siguiente la encontraría inactiva y dos casos pasarían por el motivo equivocado
     * (el que prueba el rechazo, porque ya venía apagada; y el que crea un servicio con ella,
     * que fallaría en el alta con un mensaje que no menciona el residuo).
     */
    public int disposableCurrencyId() {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
            .createNativeQuery("INSERT INTO public.currencies (code, name, symbol, is_active)"
                    + " VALUES ('ZZT', 'ZTEST_ Moneda de prueba', 'Z', true)"
                    + " ON CONFLICT (code) DO UPDATE SET is_active = true RETURNING id")
            .getSingleResult()).intValue());
    }

    public int paymentTermId(String name) {
        return paymentTermRepository.find("name", name).singleResultOptional()
            .orElseThrow(() -> new IllegalStateException("Término de pago sembrado no encontrado: name=" + name)).id;
    }

    public int serviceTypeId(String code) {
        return quotationServiceTypeRepository.find("code", code).singleResultOptional()
            .orElseThrow(() -> new IllegalStateException("Tipo de servicio sembrado no encontrado: code=" + code)).id;
    }

    public int userId(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalStateException("Usuario sembrado no encontrado: username=" + username)).id;
    }

    // ---------- Sembrar fixtures NO sembrados (client, cargo_type) --------------

    /** Cliente sintético {@code ZTEST_} con name/ruc únicos. Devuelve el id generado. */
    public int seedClient() {
        long n = SEQ.incrementAndGet();
        return QuarkusTransaction.requiringNew().call(() -> {
            Client client = new Client();
            client.name = PREFIX + "Cliente " + n;
            client.ruc = ruc(n);
            client.isActive = true;
            clientRepository.persist(client);
            return client.id;
        });
    }

    /** Tipo de carga sintético {@code ZTEST_}. Devuelve el id generado. */
    public int seedCargoType() {
        long n = SEQ.incrementAndGet();
        return QuarkusTransaction.requiringNew().call(() -> {
            CargoType cargoType = new CargoType();
            cargoType.name = PREFIX + "Carga " + n;
            cargoType.standardWeight = BigDecimal.ONE;
            cargoType.isActive = true;
            cargoTypeRepository.persist(cargoType);
            return cargoType.id;
        });
    }

    /** RUC sintético de 11 dígitos, único por seq (prefijo 99 = inexistente en prod). */
    private static String ruc(long n) {
        return String.format("99%09d", n % 1_000_000_000L);
    }

    // ---------- Limpieza (llamar en @AfterEach DESPUÉS de borrar los quotations) -

    /**
     * Borra los fixtures sintéticos de client/cargo_type. Los quotations que los
     * referencian por FK deben borrarse ANTES (el {@code @AfterEach} del test los
     * borra por prefijo {@code ZTEST_} en contact_name/origin/destination).
     */
    public void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            cargoTypeRepository.delete("name like ?1", PREFIX + "%");
            clientRepository.delete("name like ?1", PREFIX + "%");
        });
    }

    /**
     * Borra la moneda descartable. Va APARTE de {@link #cleanup()} y tolerando el fallo: lo llama
     * solo la clase que la siembra (las otras doce no tienen por qué pagar una transacción de
     * más), y si un servicio de test todavía la referenciara, reventar acá se llevaría puesta la
     * limpieza de clientes y tipos de carga. El peor caso tolerado es una fila huérfana, que la
     * próxima corrida reutiliza normalizándole el estado.
     */
    public void cleanupDisposableCurrency() {
        try {
            QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("DELETE FROM public.currencies WHERE code = 'ZZT'")
                .executeUpdate());
        } catch (RuntimeException stillReferenced) {
            // Queda para la próxima corrida, que la reutiliza normalizándole el estado. Se avisa
            // igual: si el borrado falla siempre, la moneda sintética se queda ACTIVA en un
            // catálogo que la otra aplicación lee en vivo, y un catch mudo lo vuelve invisible.
            System.err.println("no se pudo borrar la moneda de prueba ZZT: " + stillReferenced);
        }
    }
}
