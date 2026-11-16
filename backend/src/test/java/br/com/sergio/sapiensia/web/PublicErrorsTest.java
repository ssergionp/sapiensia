package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * SEC-018 and SEC-020: what a visitor reads in an error is only the title (and the code, where there is one). The
 * test controller lives outside /api (open in the security chain) and exists only in this context.
 */
@Import({TestcontainersConfiguration.class, PublicErrorsTest.ThrowingConfig.class})
@ExtendWith(OutputCaptureExtension.class)
// its own context: the csrf() post-processor replaces the CSRF token repository of a shared filter chain
@SpringBootTest(properties = "test.isolated-context=public-errors")
class PublicErrorsTest {

    private static final String SECRET_TEXT = "secret-internal-text-4711";

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("SEC-020: shouldAnswer415WithoutSpringTextForAJsonRouteSentAsTextPlain")
    void shouldAnswer415WithoutSpringTextForAJsonRouteSentAsTextPlain() throws Exception {
        mvc.perform(post("/api/sessions")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("email=a@b.c")
                        .with(csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Unsupported media type"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("SEC-020: shouldAnswer405WithoutDetailAndWithAllowForAMethodTheRouteDoesNotHave")
    void shouldAnswer405WithoutDetailAndWithAllowForAMethodTheRouteDoesNotHave() throws Exception {
        MvcResult result = mvc.perform(post("/test-errors/boom").with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("Method not allowed"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(header().exists("Allow"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Allow")).contains("GET");
    }

    @Test
    @DisplayName("SEC-018: shouldAnswer400WithoutSpringTextForAParameterOfTheWrongType")
    void shouldAnswer400WithoutSpringTextForAParameterOfTheWrongType() throws Exception {
        mvc.perform(get("/test-errors/typed").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("SEC-018: shouldAnswer400WithoutSpringTextForAMissingParameter")
    void shouldAnswer400WithoutSpringTextForAMissingParameter() throws Exception {
        mvc.perform(get("/test-errors/typed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("SEC-015: shouldAnswer400ForAPageAbove10000AndStillServeThe10000th")
    void shouldAnswer400ForAPageAbove10000AndStillServeThe10000th() throws Exception {
        mvc.perform(get("/api/products").param("page", "10001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").doesNotExist());
        mvc.perform(get("/api/products").param("page", "10000")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-018: shouldAnswerAGeneric500WithoutMessageOrClassAndLogTheClassOnTheServer")
    void shouldAnswerAGeneric500WithoutMessageOrClassAndLogTheClassOnTheServer(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(get("/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET_TEXT).doesNotContain("IllegalStateException");
        // the server log keeps the class and the stack for whoever investigates
        assertThat(output.getAll()).contains("java.lang.IllegalStateException");
    }

    @Test
    @DisplayName("SEC-018: erro inesperado não grava a mensagem da exceção no log")
    void shouldNotWriteTheExceptionMessageToTheLog(CapturedOutput output) throws Exception {
        mvc.perform(get("/test-errors/personal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(jsonPath("$.detail").doesNotExist());

        assertThat(output.getAll())
                .contains("java.lang.IllegalStateException")
                .contains("PublicErrorsTest$ThrowingController")
                .doesNotContain("cliente@exemplo.com");
    }

    @Test
    @DisplayName("SEC-018: ResponseStatusException com motivo interno não mostra o motivo")
    void shouldNotShowTheInternalReasonOfAResponseStatusException() throws Exception {
        MvcResult result = mvc.perform(get("/test-errors/reason"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("internal reason");
    }

    @Test
    @DisplayName("SEC-018: cliente que desconecta não gera erro no log nem corpo")
    void shouldStayQuietWhenTheClientDisconnects(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(get("/test-errors/abort")).andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(output.getAll()).doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("SEC-018: shouldKeepA403ForAnAccessDeniedExceptionThrownByAController")
    void shouldKeepA403ForAnAccessDeniedExceptionThrownByAController() throws Exception {
        mvc.perform(get("/test-errors/denied").with(user("someone").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-018: shouldKeepA401ForAnAccessDeniedExceptionOfAnAnonymousVisitor")
    void shouldKeepA401ForAnAccessDeniedExceptionOfAnAnonymousVisitor() throws Exception {
        mvc.perform(get("/test-errors/denied")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-018: shouldKeepTheStatusOfAResponseStatusException")
    void shouldKeepTheStatusOfAResponseStatusException() throws Exception {
        mvc.perform(get("/test-errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("SEC-018: shouldKeepTheExistingSpecificAnswerForARateLimitedException")
    void shouldKeepTheExistingSpecificAnswerForARateLimitedException() throws Exception {
        mvc.perform(get("/test-errors/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @TestConfiguration
    static class ThrowingConfig {

        @Bean
        ThrowingController throwingController() {
            return new ThrowingController();
        }
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test-errors/boom")
        String boom() {
            throw new IllegalStateException(SECRET_TEXT);
        }

        @GetMapping("/test-errors/personal")
        String personal() {
            throw new IllegalStateException("cliente@exemplo.com");
        }

        @GetMapping("/test-errors/reason")
        String reason() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "internal reason");
        }

        @GetMapping("/test-errors/abort")
        String abort() throws Exception {
            throw new org.apache.catalina.connector.ClientAbortException(new java.io.IOException("Broken pipe"));
        }

        @GetMapping("/test-errors/denied")
        String denied() {
            throw new AccessDeniedException(SECRET_TEXT);
        }

        @GetMapping("/test-errors/conflict")
        String conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }

        @GetMapping("/test-errors/limited")
        String limited() {
            throw new RateLimitedException(java.time.Duration.ofSeconds(5));
        }

        @GetMapping("/test-errors/typed")
        String typed(@RequestParam int n) {
            return "n=" + n;
        }
    }
}
