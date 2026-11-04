package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
// Own context with the real limit (20 per 15 minutes): the other login tests lower it to keep their runs short.
@SpringBootTest(
        properties = {
            "test.isolated-context=login-ip-window",
            "app.login-limit.ip-max-attempts=20",
            "app.login-limit.client-ip-header=X-Test-Client-Ip"
        })
class LoginIpWindowTest {

    private static final int IP_LIMIT = 20;

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;
    Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        csrfCookie = mvc.perform(get("/entrar")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MvcResult login(String ip) throws Exception {
        // a new e-mail every time, so only the per-IP limit is in play
        return mvc.perform(post("/api/sessions")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .header("X-Test-Client-Ip", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + UUID.randomUUID()
                                + "@example.com\",\"password\":\"senhaerrada123\"}"))
                .andReturn();
    }

    @Test
    @DisplayName("SEC-014: the 21st login attempt from one IP inside the window is refused with 429")
    void shouldRefuseTheTwentyFirstAttemptFromOneIpInsideTheWindow() throws Exception {
        for (int i = 1; i <= IP_LIMIT; i++) {
            assertThat(login("10.20.0.1").getResponse().getStatus())
                    .as("attempt %d", i)
                    .isEqualTo(401);
        }

        MvcResult refused = login("10.20.0.1");

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString()).isEmpty();
        assertThat(Long.parseLong(refused.getResponse().getHeader("Retry-After")))
                .isBetween(1L, 900L);
        // another IP is not affected
        assertThat(login("10.20.0.2").getResponse().getStatus()).isEqualTo(401);
    }
}
