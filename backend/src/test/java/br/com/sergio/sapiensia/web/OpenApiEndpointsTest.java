package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SEC-016: the endpoints the API exposes are exactly the reviewed list. A new endpoint fails this test until it is
 * reviewed against SecurityConfig and added to endpoints.txt and docs/api/endpoints.md.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OpenApiEndpointsTest {

    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    WebApplicationContext context;

    @Test
    @DisplayName("SEC-016: os endpoints do OpenAPI são exatamente os da lista revisada")
    void endpointsMatchTheReviewedList() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        String body =
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode paths = JsonMapper.builder().build().readTree(body).path("paths");

        Set<String> actual = new TreeSet<>();
        paths.properties()
                .forEach(path -> path.getValue().propertyNames().forEach(method -> {
                    if (METHODS.contains(method)) {
                        actual.add(method.toUpperCase(Locale.ROOT) + " " + path.getKey());
                    }
                }));

        Set<String> reviewed = new TreeSet<>(reviewedList());
        List<String> extra = new ArrayList<>(actual);
        extra.removeAll(reviewed);
        List<String> missing = new ArrayList<>(reviewed);
        missing.removeAll(actual);

        assertThat(extra.isEmpty() && missing.isEmpty())
                .as(
                        "OpenAPI endpoints not in endpoints.txt (extra): %s; in endpoints.txt but not in OpenAPI"
                                + " (missing): %s. Review the new route against SecurityConfig, then update"
                                + " src/test/resources/openapi/endpoints.txt and docs/api/endpoints.md.",
                        extra, missing)
                .isTrue();
    }

    private static List<String> reviewedList() throws IOException {
        String text = new ClassPathResource("openapi/endpoints.txt").getContentAsString(StandardCharsets.UTF_8);
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}
