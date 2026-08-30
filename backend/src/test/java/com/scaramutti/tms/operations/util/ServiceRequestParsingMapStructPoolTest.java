package com.scaramutti.tms.operations.util;

import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sostiene el invariante que hace SEGURO declarar {@link ServiceRequestParsing} en el {@code uses}
 * de un mapper. El COMPORTAMIENTO de la clase se prueba desde {@code ServiceResourceMapperTest},
 * que es por donde entra todo el tráfico; acá solo vive esto.
 *
 * <p>MapStruct suma los estáticos de las clases de {@code uses} a su pool de candidatos
 * AUTOMÁTICOS y elige por firma: un método de un argumento que convierta de A a B se aplica solo a
 * cualquier campo A→B sin {@code qualifiedByName}. {@code parseSearch} es {@code String -> String},
 * así que sin protección pisaría todo campo de texto que hoy pasa tal cual, rechazando con el 400
 * de la búsqueda valores que no tienen nada que ver. La protección es {@code @Named}: un método
 * calificado sale del pool y solo se usa si alguien lo pide por nombre.
 *
 * <p>El caso de abajo lo verifica de la única forma que de verdad mata la mutación: un mapper
 * CANARIO que declara la clase en {@code uses} y tiene un destino {@code String} SIN calificar,
 * o sea la situación exacta que el pool automático aprovecha. Con el {@code @Named} puesto el valor
 * pasa intacto; borrándolo de {@code parseSearch}, MapStruct lo elige solo y el valor de dos
 * caracteres se va en excepción.
 *
 * <p>Se intentó antes por reflexión y NO sirve: {@code org.mapstruct.Named} tiene retención
 * {@code CLASS}, así que no llega al {@code Method} en tiempo de ejecución y da un falso rojo sobre
 * los tres métodos que sí lo llevan. Leerlo del {@code .class} sería posible, pero mediría la
 * anotación en vez de su efecto, que es lo que importa acá.
 */
class ServiceRequestParsingMapStructPoolTest {

    record Canary(String freeText) { }

    record CanarySource(String freeText) { }

    /**
     * Sin un solo {@code @Mapping}: el destino se mapea por coincidencia de nombre, que es
     * exactamente el caso donde MapStruct recurre al pool automático para elegir cómo convertirlo.
     */
    @Mapper(uses = ServiceRequestParsing.class)
    interface CanaryMapper {
        Canary toCanary(CanarySource source);
    }

    /**
     * Un texto de DOS caracteres: por debajo del mínimo de la búsqueda, así que si el pool
     * automático llegara a elegir {@code parseSearch} el mapeo tiraría en vez de copiar.
     */
    @Test
    void anUnqualifiedStringTarget_isCopiedVerbatimAndNotRunThroughTheSearchParser() {
        CanaryMapper canaryMapper = Mappers.getMapper(CanaryMapper.class);

        assertEquals("ab", canaryMapper.toCanary(new CanarySource("ab")).freeText());
    }

    /** Un valor que el parseo de búsqueda también rechazaría por su contenido, no por su largo. */
    @Test
    void anUnqualifiedStringTarget_keepsEvenWhatTheSearchParserWouldReject() {
        CanaryMapper canaryMapper = Mappers.getMapper(CanaryMapper.class);

        assertEquals("de la", canaryMapper.toCanary(new CanarySource("de la")).freeText());
    }

    /**
     * El otro movimiento que mete a alguien en el pool: agregar un método público de un argumento,
     * o convertir uno de dos en uno de uno. El canario de arriba solo cubre {@code String -> String};
     * este caso avisa por cualquier firma.
     *
     * <p>Se descuentan los parámetros que MapStruct NO cuenta como parte de la firma
     * ({@code @Context}, {@code @TargetType}, {@code @MappingTarget}): con ellos, un
     * {@code parseAlgo(String, @Context Foo)} entraría al pool teniendo dos parámetros.
     */
    @Test
    void theMethodsMapStructCouldPickOnItsOwn_areTheThreeExpectedOnes() {
        List<String> candidates = Stream.of(ServiceRequestParsing.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .filter(method -> mappingArgumentCount(method) == 1)
            .map(Method::getName)
            .sorted()
            .toList();

        assertEquals(List.of("parseDateTime", "parseForce", "parseSearch"), candidates);
    }

    private static long mappingArgumentCount(Method method) {
        return Stream.of(method.getParameterAnnotations())
            .filter(annotations -> Stream.of(annotations).noneMatch(annotation -> {
                String name = annotation.annotationType().getName();
                return name.equals("org.mapstruct.Context")
                    || name.equals("org.mapstruct.TargetType")
                    || name.equals("org.mapstruct.MappingTarget");
            }))
            .count();
    }

    /**
     * Las constantes que definen la ventana de negocio y los límites de la búsqueda están CERRADAS.
     * Ahí ocurrió el defecto que la mudanza vino a cerrar: una prueba comparaba los bordes de la
     * ventana contra las constantes que definen esa misma ventana, así que seguía en verde con la
     * ventana corrida a cualquier otro par de fechas.
     *
     * <p>Las cuatro que quedan abiertas son las que el mapper usa, así que contra ESAS la tautología
     * todavía se puede escribir: hoy las pruebas de paginación fijan 0, 20, 100 y 1 a mano, y
     * conviene que siga así.
     */
    @Test
    void onlyTheConstantsTheMapperUses_areVisibleFromOutside() {
        List<String> visible = Stream.of(ServiceRequestParsing.class.getDeclaredFields())
            .filter(field -> Modifier.isPublic(field.getModifiers()))
            .map(java.lang.reflect.Field::getName)
            .sorted()
            .toList();

        assertEquals(
            List.of("ASCII_INTEGER", "DEFAULT_PAGE", "DEFAULT_PAGE_SIZE", "MAX_PAGE_SIZE"),
            visible);
    }
}
