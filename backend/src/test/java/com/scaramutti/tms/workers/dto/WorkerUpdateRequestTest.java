package com.scaramutti.tms.workers.dto;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cuerpo de la edicion repite el del alta, campo por campo y anotacion por anotacion.
 *
 * <p>Este caso existe porque el javadoc del cuerpo de edicion afirmaba que existia y NO existia.
 * La afirmacion importaba: el contrato declara el cuerpo de edicion como el del alta mas el
 * motivo, y un record no hereda, asi que las dos formas pueden separarse sin que nada deje de
 * compilar. El dia que alguien sume un campo al alta, el contrato lo declara tambien en la
 * edicion, el cliente generado lo manda, y la edicion lo ignora en silencio.
 *
 * <p>Compara tambien las ANOTACIONES, no solo los nombres: perder un limite de largo o el formato
 * del telefono al copiar es exactamente el error que esto ataja, y ninguno de los dos se ve en
 * una comparacion de tipos.
 */
class WorkerUpdateRequestTest {

    private List<String> firmaDe(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
            .map(component -> firmaDelComponente(record, component)).toList();
    }

    /**
     * Las anotaciones se leen del CAMPO y no del componente del record: las de validacion no
     * declaran el componente entre sus destinos, asi que preguntarselas al componente devuelve
     * siempre vacio y la comparacion pasaria a ser de solo nombres, sin darse cuenta.
     */
    private String firmaDelComponente(Class<?> record, RecordComponent component) {
        Annotation[] anotacionesDelCampo;
        try {
            anotacionesDelCampo = record.getDeclaredField(component.getName()).getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("un record siempre tiene el campo de su componente", e);
        }
        // Solo las de VALIDACION: son las que cambian el comportamiento. Las de documentacion
        // quedan afuera a proposito porque hoy no hacen nada (el escaneo esta desactivado y las
        // dos specs se escriben a mano), y compararlas llenaria el caso de ruido hasta que
        // alguien lo borre por molesto.
        String anotaciones = Arrays.stream(anotacionesDelCampo)
            .filter(a -> a.annotationType().getPackageName().startsWith("jakarta.validation"))
            .map(this::describir).sorted().collect(Collectors.joining(" "));
        return component.getType().getSimpleName() + " " + component.getName()
            + (anotaciones.isEmpty() ? "" : " [" + anotaciones + "]");
    }

    /** El texto de la anotacion con sus atributos: sin ellos, cambiar un maximo no se veria. */
    private String describir(Annotation annotation) {
        return annotation.toString().replace("jakarta.validation.constraints.", "");
    }

    @Test
    void theUpdateBody_repeatsEveryComponentOfTheCreateBody_plusTheReason() {
        List<String> alta = firmaDe(WorkerRequest.class);
        List<String> edicion = firmaDe(WorkerUpdateRequest.class);

        // Se compara contra la lista del ALTA, no contra un pedazo de la propia lista de la
        // edicion: una comparacion que saca su valor esperado del valor medido no mide nada.
        assertEquals(alta.size() + 1, edicion.size(), "la edicion es el alta mas un solo campo");
        assertEquals(alta, edicion.subList(0, alta.size()),
            "los campos compartidos, con sus anotaciones, tienen que ser identicos y en el mismo orden");
    }

    /** Y el campo de mas es el motivo, con su maximo y sin minimo. */
    @Test
    void theExtraComponent_isTheReason_withItsMaximumAndNoMinimum() {
        List<String> edicion = firmaDe(WorkerUpdateRequest.class);
        String ultimo = edicion.get(edicion.size() - 1);

        assertTrue(ultimo.startsWith("String reason ["), "el campo de mas es el motivo: " + ultimo);
        assertTrue(ultimo.contains("max=500"), "con su maximo: " + ultimo);
        assertTrue(ultimo.contains("min=0"), "y sin minimo en el borde: " + ultimo);
    }

    /** El piso del motivo NO vive en el borde: es condicional y lo mide el servicio. */
    @Test
    void theReasonMinimum_isNotDeclaredOnTheBody() {
        assertEquals(10, WorkerUpdateRequest.MIN_REASON_LENGTH);
        assertTrue(firmaDe(WorkerUpdateRequest.class).stream().noneMatch(f -> f.contains("min=10")),
            "con el piso en el borde, el codigo de negocio no podria emitirse nunca");
    }
}
