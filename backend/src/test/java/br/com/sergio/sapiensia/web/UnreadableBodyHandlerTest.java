package br.com.sergio.sapiensia.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** SEC-033: the global handler of an unreadable body keeps the answer every endpoint gave before it existed. */
@Import(TestcontainersConfiguration.class)
// its own context: the csrf() post-processor replaces the CSRF token repository of a shared filter chain
@SpringBootTest(properties = "test.isolated-context=unreadable-body")
class UnreadableBodyHandlerTest {

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
    @DisplayName("SEC-033: shouldAnswerA400ProblemForAMalformedBodyOnAnotherEndpoint")
    void shouldAnswerA400ProblemForAMalformedBodyOnAnotherEndpoint() throws Exception {
        mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": ")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Failed to read request"));
    }

    @Test
    @DisplayName("SEC-015: shouldCloseTheConnectionOnA413AnsweredByTheControllerLayer")
    void shouldCloseTheConnectionOnA413AnsweredByTheControllerLayer() throws Exception {
        // the counting stream marks the request when a chunked body crosses the limit mid-read
        mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": ")
                        .requestAttr(RequestBodyLimitFilter.class.getName() + ".EXCEEDED", Boolean.TRUE)
                        .with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Connection", "close"))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
}
