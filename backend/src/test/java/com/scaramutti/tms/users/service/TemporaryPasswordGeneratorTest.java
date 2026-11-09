package com.scaramutti.tms.users.service;

import com.scaramutti.tms.auth.dto.ChangePasswordRequest;
import com.scaramutti.tms.auth.service.PasswordService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryPasswordGeneratorTest {

    /** El patron que publica el contrato, escrito aca y no tomado del generador. */
    private static final Pattern CONTRACT_PATTERN = Pattern.compile("^[A-HJ-NP-Za-hjkmnp-z2-9]{12}$");

    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();
    private final List<String> thousand = IntStream.range(0, 1000).mapToObj(i -> generator.generate()).toList();

    @Test
    void generate_alwaysMatchesThePatternOfTheContract() {
        for (String password : thousand) {
            assertTrue(CONTRACT_PATTERN.matcher(password).matches(), password);
        }
    }

    @Test
    void generate_neverRepeatsInAThousandCalls() {
        assertEquals(1000, new HashSet<>(thousand).size());
    }

    /** Con 12 000 caracteres, que falte uno de los 55 por azar es del orden de 10^-94. */
    @Test
    void generate_drawsEveryCharacterOfTheAlphabet() {
        Set<Integer> drawn = new HashSet<>();
        thousand.forEach(password -> password.chars().forEach(drawn::add));
        assertEquals(55, drawn.size());
    }

    /** La persona la usa como actual y la reemplaza en el cambio propio: tiene que pasar su regla. */
    @Test
    void generate_passesTheRuleOfTheOwnPasswordChange() {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory()) {
            Validator validator = factory.getValidator();
            for (String password : thousand.subList(0, 50)) {
                assertTrue(PasswordService.fitsBcrypt(password), password);
                assertEquals(Set.of(), validator.validate(new ChangePasswordRequest(password, password)), password);
            }
        }
    }
}
