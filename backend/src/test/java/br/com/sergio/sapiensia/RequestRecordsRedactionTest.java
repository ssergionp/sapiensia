package br.com.sergio.sapiensia;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring MVC logs the request body object at DEBUG through its toString. Every record that a controller receives as
 * {@code @RequestBody} and that has an e-mail, a password, a token or a code must declare its own toString, and that
 * toString must not print the values (ADR-0006 / SEC-036).
 */
class RequestRecordsRedactionTest {

    private static final Set<String> SENSITIVE_WORDS = Set.of("email", "password", "token", "code", "secret", "cpf");

    @Test
    @DisplayName(
            "ADR-0006 / SEC-036: every request record with an e-mail, password, token or code redacts its toString")
    void shouldRedactToStringOfEveryRequestRecordWithSensitiveFields() throws Exception {
        Set<Class<?>> requestRecords = requestRecords();
        // the scan is not vacuous
        assertThat(requestRecords)
                .extracting(Class::getSimpleName)
                .contains("AdminLoginRequest", "LoginRequest", "CreateOrderRequest");

        Set<String> problems = new TreeSet<>();
        for (Class<?> type : requestRecords) {
            RecordComponent[] components = type.getRecordComponents();
            Set<String> sentinels = new TreeSet<>();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                if (types[i] == String.class) {
                    values[i] = "sentinel-" + components[i].getName().toLowerCase(Locale.ROOT) + "-value";
                    if (isSensitive(components[i].getName())) {
                        sentinels.add((String) values[i]);
                    }
                }
            }
            if (sentinels.isEmpty()) {
                continue;
            }
            try {
                type.getDeclaredMethod("toString");
            } catch (NoSuchMethodException e) {
                problems.add(type.getSimpleName() + " does not declare its own toString");
                continue;
            }
            var constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            String text = constructor.newInstance(values).toString();
            for (String sentinel : sentinels) {
                if (text.contains(sentinel)) {
                    problems.add(type.getSimpleName() + ".toString prints " + sentinel);
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    private static boolean isSensitive(String componentName) {
        String name = componentName.toLowerCase(Locale.ROOT);
        return SENSITIVE_WORDS.stream().anyMatch(name::contains);
    }

    /** The record types that a controller of the application receives as @RequestBody. */
    private static Set<Class<?>> requestRecords() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Class<?>> found = new java.util.LinkedHashSet<>();
        for (var definition : scanner.findCandidateComponents("br.com.sergio.sapiensia")) {
            Class<?> controller = ClassUtils.forName(definition.getBeanClassName(), null);
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)
                            && parameter.getType().isRecord()) {
                        found.add(parameter.getType());
                    }
                }
            }
        }
        return found;
    }
}
