package com.example.app;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The security baseline and the SPA routing that every project inherits from the template. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WebSecurityTest {

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
    @DisplayName("health check is public and reports UP without details")
    void healthIsPublic() throws Exception {
        mvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("an API route the project did not open is denied")
    void apiIsDeniedByDefault() throws Exception {
        mvc.perform(get("/api/anything")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a POST without the CSRF token is rejected")
    void postWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/system/health")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an app route such as /pedidos/42 gets the React index.html")
    void appRoutesGetTheSpa() throws Exception {
        mvc.perform(get("/pedidos/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("spa-index-for-tests")));
    }

    @Test
    @DisplayName("a missing file is 404, not the React page")
    void missingFileIsNotFound() throws Exception {
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("pages carry the security headers")
    void securityHeaders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
