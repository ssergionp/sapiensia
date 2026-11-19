package br.com.sergio.sapiensia.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
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

/** SEC-010: who may call the order routes, and that every other route and verb stays denied. */
@Import(TestcontainersConfiguration.class)
// its own context: the csrf() post-processor replaces the CSRF token repository of a shared filter chain
@SpringBootTest(properties = "test.isolated-context=order-route-access")
class OrderRouteAccessTest {

    private static final String ORDERS = "/api/orders";
    private static final String BODY = "{\"productId\":\"" + UUID.randomUUID() + "\"}";

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
    @DisplayName("SEC-010: shouldRefuseAnAnonymousVisitor")
    void shouldRefuseAnAnonymousVisitor() throws Exception {
        mvc.perform(post(ORDERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mvc.perform(get(ORDERS + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseAnAdministrator")
    void shouldRefuseAnAdministrator() throws Exception {
        var admin = user("admin@example.com").roles("ADMIN");
        mvc.perform(post(ORDERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(admin)
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + "/" + UUID.randomUUID()).with(admin)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseTheStatusToTheAdministrator")
    void shouldRefuseTheStatusToTheAdministrator() throws Exception {
        String status = ORDERS + "/" + UUID.randomUUID() + "/status";

        mvc.perform(get(status).with(user("admin@example.com").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get(status)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseAPostWithoutTheCsrfToken")
    void shouldRefuseAPostWithoutTheCsrfToken() throws Exception {
        mvc.perform(post(ORDERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(user("cliente@example.com").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-010: shouldKeepEveryOtherOrderRouteDenied")
    void shouldKeepEveryOtherOrderRouteDenied() throws Exception {
        var customer = user("cliente@example.com").roles("CUSTOMER");
        String one = ORDERS + "/" + UUID.randomUUID();

        mvc.perform(post(one).with(customer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put(one).with(customer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete(one).with(customer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(one + "/pix").with(customer)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-010: o pedido de Reembolso recusa o visitante anônimo (401) e o Administrador (403)")
    void shouldRefuseTheRefundRequestToAnAnonymousVisitorAndToAnAdministrator() throws Exception {
        String refund = ORDERS + "/" + UUID.randomUUID() + "/refund-request";
        String body = "{\"reason\":\"OTHER\"}";

        mvc.perform(post(refund)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mvc.perform(post(refund)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseTheListToAnAnonymousVisitor")
    void shouldRefuseTheListToAnAnonymousVisitor() throws Exception {
        mvc.perform(get(ORDERS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseTheListToAnAdministrator")
    void shouldRefuseTheListToAnAdministrator() throws Exception {
        mvc.perform(get(ORDERS).with(user("admin@example.com").roles("ADMIN"))).andExpect(status().isForbidden());
    }
}
