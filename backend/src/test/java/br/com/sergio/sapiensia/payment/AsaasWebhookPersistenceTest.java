package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.event;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=asaas-webhook-persistence")
class AsaasWebhookPersistenceTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private HttpResponse<String> post(String body) throws Exception {
        return AsaasWebhookTestSupport.post(port, LOOPBACK, AUTH, body, "application/json");
    }

    private int count(String id) {
        return jdbc.queryForObject(
                "select count(*) from payment_events where provider_event_id = ?", Integer.class, id);
    }

    private long total() {
        return jdbc.queryForObject("select count(*) from payment_events", Long.class);
    }

    @Test
    @DisplayName("FR-021: shouldStoreTheEventBeforeAnsweringOk")
    void shouldStoreTheEventBeforeAnsweringOk() throws Exception {
        String id = uniqueId();

        assertThat(post(event(id, "PAYMENT_RECEIVED")).statusCode()).isEqualTo(200);

        Map<String, Object> row = jdbc.queryForMap(
                "select event_type, payload->'payment'->>'value' as value, received_at"
                        + " from payment_events where provider_event_id = ?",
                id);
        assertThat(row.get("event_type")).isEqualTo("PAYMENT_RECEIVED");
        assertThat(row.get("value")).isEqualTo("59.9");
        assertThat(row.get("received_at")).isNotNull();
    }

    @Test
    @DisplayName("SEC-025, SEC-031: shouldAnswerOkAndKeepOneRowForARepeatedEvent")
    void shouldAnswerOkAndKeepOneRowForARepeatedEvent() throws Exception {
        String id = uniqueId();

        assertThat(post(event(id, "PAYMENT_RECEIVED", "59.9")).statusCode()).isEqualTo(200);
        assertThat(post(event(id, "PAYMENT_RECEIVED", "10.5")).statusCode()).isEqualTo(200);

        assertThat(count(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload->'payment'->>'value' from payment_events where provider_event_id = ?",
                        String.class,
                        id))
                .isEqualTo("59.9");
    }

    @Test
    @DisplayName("SEC-025, SEC-031: shouldLetTheDatabaseDecideBetweenTwoSimultaneousDeliveries")
    void shouldLetTheDatabaseDecideBetweenTwoSimultaneousDeliveries() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                String id = uniqueId();
                String body = event(id, "PAYMENT_RECEIVED");
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> results = List.of(
                        pool.submit(() -> {
                            start.await();
                            return post(body).statusCode();
                        }),
                        pool.submit(() -> {
                            start.await();
                            return post(body).statusCode();
                        }));
                start.countDown();
                for (Future<Integer> result : results) {
                    assertThat(result.get()).isEqualTo(200);
                }
                assertThat(count(id)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName(
            "FR-021: BALANCE_VALUE_UNBLOCKED, PAYMENT_REFUND_IN_PROGRESS e PAYMENT_CHARGEBACK_REQUESTED são gravados sem tarefa")
    void shouldStoreUnblockInProgressAndChargebackEventsWithoutATask() throws Exception {
        for (String type :
                List.of("BALANCE_VALUE_UNBLOCKED", "PAYMENT_REFUND_IN_PROGRESS", "PAYMENT_CHARGEBACK_REQUESTED")) {
            String id = uniqueId();

            assertThat(post(event(id, type)).statusCode()).isEqualTo(200);

            assertThat(count(id)).as(type).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "select count(*) from scheduled_tasks where task_name = 'process-payment-event'"
                                    + " and task_instance = (select id::text from payment_events"
                                    + " where provider_event_id = ?)",
                            Integer.class,
                            id))
                    .as(type)
                    .isZero();
        }
    }

    @Test
    @DisplayName("FR-021: shouldStoreAnUnknownEventType")
    void shouldStoreAnUnknownEventType() throws Exception {
        String id = uniqueId();

        assertThat(post(event(id, "PAYMENT_SOMETHING_NEW_2027")).statusCode()).isEqualTo(200);

        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-021: shouldIgnoreUnknownFields")
    void shouldIgnoreUnknownFields() throws Exception {
        String id = uniqueId();
        String body = "{\"id\": \"" + id + "\", \"event\": \"PAYMENT_RECEIVED\", \"futureField\": {\"x\": 1},"
                + " \"payment\": {\"id\": \"pay_1\", \"futureField\": {\"x\": 1}}}";

        assertThat(post(body).statusCode()).isEqualTo(200);

        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseMalformedJson")
    void shouldRefuseMalformedJson() throws Exception {
        long before = total();

        HttpResponse<String> response = post("{\"id\": \"evt_1\", ");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("BODY_INVALID").doesNotContain("evt_1");
        assertThat(total()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseABodyWithoutIdOrEvent")
    void shouldRefuseABodyWithoutIdOrEvent() throws Exception {
        long before = total();
        List<String> bodies = List.of(
                "{\"event\": \"PAYMENT_RECEIVED\"}",
                "{\"id\": \"\", \"event\": \"PAYMENT_RECEIVED\"}",
                "{\"id\": \"evt 1\", \"event\": \"PAYMENT_RECEIVED\"}",
                "{\"id\": \"" + "a".repeat(256) + "\", \"event\": \"PAYMENT_RECEIVED\"}",
                "{\"id\": \"evt_1\"}",
                "{\"id\": \"evt_1\", \"event\": \"payment received\"}",
                "[]",
                "\"texto\"");

        for (String body : bodies) {
            assertThat(post(body).statusCode()).as(body).isEqualTo(400);
        }

        assertThat(total()).isEqualTo(before);
    }

    @Test
    @DisplayName("FR-021: shouldRefuseABodyLargerThanTheLimit")
    void shouldRefuseABodyLargerThanTheLimit() throws Exception {
        String id = uniqueId();
        String body = "{\"id\": \"" + id + "\", \"event\": \"PAYMENT_RECEIVED\", \"payment\": {\"description\": \""
                + "x".repeat(70_000) + "\"}}";

        assertThat(post(body).statusCode()).isEqualTo(413);

        assertThat(count(id)).isZero();
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseAnotherContentType")
    void shouldRefuseAnotherContentType() throws Exception {
        String id = uniqueId();

        HttpResponse<String> response =
                AsaasWebhookTestSupport.post(port, LOOPBACK, AUTH, event(id, "PAYMENT_RECEIVED"), "text/plain");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(count(id)).isZero();
    }

    @Test
    @DisplayName("ADR-0003: shouldRedactACpfIfTheProviderEverSendsOne")
    void shouldRedactACpfIfTheProviderEverSendsOne() throws Exception {
        String id = uniqueId();
        String body = "{\"id\": \"" + id + "\", \"event\": \"PAYMENT_RECEIVED\", \"CPF\": \"12345678901\","
                + " \"payment\": {\"customer\": {\"id\": \"cus_1\", \"cpfCnpj\": \"12345678901\"}}}";

        assertThat(post(body).statusCode()).isEqualTo(200);

        String stored = jdbc.queryForObject(
                "select payload::text from payment_events where provider_event_id = ?", String.class, id);
        assertThat(stored).doesNotContain("12345678901").contains("\"[redacted]\"");
    }

    @Test
    @DisplayName("FR-021: shouldStoreAnEventWhoseTextContainsANulCharacter")
    void shouldStoreAnEventWhoseTextContainsANulCharacter() throws Exception {
        String id = uniqueId();
        String body = "{\"id\": \"" + id + "\", \"event\": \"PAYMENT_RECEIVED\","
                + " \"payment\": {\"description\": \"a\\u0000b\"}}";

        assertThat(post(body).statusCode()).isEqualTo(200);

        assertThat(count(id)).isEqualTo(1);
        String description = jdbc.queryForObject(
                "select payload->'payment'->>'description' from payment_events where provider_event_id = ?",
                String.class,
                id);
        assertThat(description).isEqualTo("a�b");
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseAPropertyNameWithANulCharacter")
    void shouldRefuseAPropertyNameWithANulCharacter() throws Exception {
        String id = uniqueId();
        String body = "{\"id\": \"" + id + "\", \"event\": \"PAYMENT_RECEIVED\","
                + " \"payment\": {\"bad\\u0000key\": \"x\"}}";

        HttpResponse<String> response = post(body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(count(id)).isZero();
    }

    @Test
    @DisplayName("FR-021: shouldAnswerWithoutABodyOrCookie")
    void shouldAnswerWithoutABodyOrCookie() throws Exception {
        HttpResponse<String> response = post(event(uniqueId(), "PAYMENT_RECEIVED"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("content-length")).hasValue("0");
        assertThat(response.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
    }
}
