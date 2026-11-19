package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Points the real adapter, with the real JDK HTTP client, at a local fake server and records what arrives. */
@ExtendWith(OutputCaptureExtension.class)
class AsaasPixGatewayWireTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // built at run time: a literal that looks like an Asaas key is flagged by gitleaks
    private static final String KEY = "$aact_" + "hmlg_" + "test" + "0000000000";
    private static final String CPF = "52998224725";
    private static final String MARKER = "UNIQUE-MARKER-9f3c1d";
    private static final Duration CONNECT = Duration.ofSeconds(3);
    private static final Duration READ = Duration.ofSeconds(10);

    private record Seen(String method, String path, String query, Map<String, List<String>> headers, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    private record Reply(int status, String body) {}

    private HttpServer server;
    private ExecutorService executor;
    private final List<Seen> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Seen, Reply> handler = seen -> new Reply(200, "{}");

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(exchange.getRequestHeaders());
            Seen seen = new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    headers,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(seen);
            Reply reply = handler.apply(seen);
            byte[] answer = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), answer.length == 0 ? -1 : answer.length);
            if (answer.length > 0) {
                exchange.getResponseBody().write(answer);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AsaasPixGateway gateway() {
        return gateway(READ);
    }

    private AsaasPixGateway gateway(Duration readTimeout) {
        return new AsaasPixGateway(RestClient.builder(), baseUrl(), KEY, CONNECT, readTimeout);
    }

    private void replyWith(int status, String body) {
        handler = seen -> new Reply(status, body);
    }

    private static void assertKind(Runnable call, PixGatewayException.Kind kind) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        PixGatewayException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    @DisplayName("FR-019: shouldCreateTheCustomerWithNotificationsOffAndReturnItsCode")
    void shouldCreateTheCustomerWithNotificationsOffAndReturnItsCode() {
        UUID customerId = UUID.randomUUID();
        replyWith(200, "{\"id\":\"cus_123\"}");

        String code = gateway().createCustomer(customerId, "ana@example.com", CPF);

        assertThat(code).isEqualTo("cus_123");
        Seen seen = requests.get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/v3/customers");
        assertThat(seen.headers().get("access_token")).containsExactly(KEY);
        assertThat(seen.headers().get("User-Agent")).containsExactly("sapiensia");
        JsonNode body = seen.json();
        assertThat(body.get("name").asString()).isEqualTo("ana@example.com");
        assertThat(body.get("cpfCnpj").asString()).isEqualTo(CPF);
        assertThat(body.get("externalReference").asString()).isEqualTo(customerId.toString());
        assertThat(body.get("notificationDisabled").asBoolean()).isTrue();
    }

    @Test
    void shouldFindAnExistingCustomerByExternalReference() {
        UUID customerId = UUID.randomUUID();
        replyWith(200, "{\"data\":[{\"id\":\"cus_777\"}],\"totalCount\":1}");

        Optional<String> code = gateway().findCustomerCode(customerId);

        assertThat(code).contains("cus_777");
        Seen seen = requests.get(0);
        assertThat(seen.method()).isEqualTo("GET");
        assertThat(seen.path()).isEqualTo("/v3/customers");
        assertThat(seen.query()).isEqualTo("externalReference=" + customerId);
        assertThat(seen.headers().get("access_token")).containsExactly(KEY);
    }

    @Test
    void shouldFindNoCustomerWhenTheListIsEmpty() {
        replyWith(200, "{\"data\":[],\"totalCount\":0}");

        assertThat(gateway().findCustomerCode(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("FR-019, SEC-012: shouldCreateAPixChargeInReaisFromCents")
    void shouldCreateAPixChargeInReaisFromCents() {
        UUID orderId = UUID.randomUUID();
        replyWith(200, "{\"id\":\"pay_abc\",\"status\":\"PENDING\"}");

        String id = gateway().createCharge("cus_123", orderId, 2990, LocalDate.of(2026, 11, 14), "SapiensIA: Guia");

        assertThat(id).isEqualTo("pay_abc");
        assertThat(requests).hasSize(1);
        Seen seen = requests.get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/v3/payments");
        JsonNode body = seen.json();
        assertThat(body.get("customer").asString()).isEqualTo("cus_123");
        assertThat(body.get("billingType").asString()).isEqualTo("PIX");
        assertThat(body.get("value").decimalValue()).isEqualByComparingTo("29.9");
        assertThat(body.get("dueDate").asString()).isEqualTo("2026-11-14");
        assertThat(body.get("description").asString()).isEqualTo("SapiensIA: Guia");
        assertThat(body.get("externalReference").asString()).isEqualTo(orderId.toString());
    }

    static Stream<String> chargeIdsOutsideThePattern() {
        return Stream.of("pay 1", "pay-1", "pay_1\nINFO forged", "a".repeat(65));
    }

    @ParameterizedTest
    @MethodSource("chargeIdsOutsideThePattern")
    @DisplayName("SEC-026: shouldRefuseACreatedChargeWhoseIdIsOutsideThePattern")
    void shouldRefuseACreatedChargeWhoseIdIsOutsideThePattern(String chargeId, CapturedOutput output) {
        replyWith(200, JSON.createObjectNode().put("id", chargeId).toString());

        assertKind(
                () -> gateway().createCharge("cus_123", UUID.randomUUID(), 2990, LocalDate.of(2026, 11, 14), "d"),
                PixGatewayException.Kind.UNAVAILABLE);

        assertThat(output.getAll()).doesNotContain("forged").doesNotContain(chargeId);
    }

    @Test
    @DisplayName("SEC-026: shouldAcceptACreatedChargeIdOf64Characters")
    void shouldAcceptACreatedChargeIdOf64Characters() {
        String chargeId = "p".repeat(64);
        replyWith(200, JSON.createObjectNode().put("id", chargeId).toString());

        assertThat(gateway().createCharge("cus_123", UUID.randomUUID(), 2990, LocalDate.of(2026, 11, 14), "d"))
                .isEqualTo(chargeId);
    }

    @Test
    @DisplayName("ADR-0003: shouldReadTheCopyPasteCodeOnEveryCall")
    void shouldReadTheCopyPasteCodeOnEveryCall() {
        replyWith(200, "{\"encodedImage\":\"AAAA\",\"payload\":\"00020126-code\",\"expirationDate\":\"2026-11-14\"}");
        AsaasPixGateway gateway = gateway();

        assertThat(gateway.readCopyPaste("pay_abc")).isEqualTo("00020126-code");
        assertThat(gateway.readCopyPaste("pay_abc")).isEqualTo("00020126-code");

        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(seen -> {
            assertThat(seen.method()).isEqualTo("GET");
            assertThat(seen.path()).isEqualTo("/v3/payments/pay_abc/pixQrCode");
        });
    }

    @Test
    @DisplayName("ADR-0003: shouldCancelAChargeAndTreat404AsCancelled")
    void shouldCancelAChargeAndTreat404AsCancelled() {
        replyWith(200, "{\"deleted\":true,\"id\":\"pay_abc\"}");
        gateway().cancelCharge("pay_abc");
        Seen seen = requests.get(0);
        assertThat(seen.method()).isEqualTo("DELETE");
        assertThat(seen.path()).isEqualTo("/v3/payments/pay_abc");

        replyWith(404, "{\"errors\":[{\"code\":\"invalid_object\",\"description\":\"gone\"}]}");
        gateway().cancelCharge("pay_abc");
        assertThat(requests).hasSize(2);
    }

    @Test
    @DisplayName("SEC-033: shouldReportARejectedDocumentWithoutEchoingIt")
    void shouldReportARejectedDocumentWithoutEchoingIt(CapturedOutput output) {
        replyWith(400, "{\"errors\":[{\"code\":\"invalid_cpfCnpj\",\"description\":\"CPF " + CPF + " invalid\"}]}");

        assertThatThrownBy(() -> gateway().createCustomer(UUID.randomUUID(), "ana@example.com", CPF))
                .isInstanceOfSatisfying(PixGatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.DOCUMENT_REJECTED);
                    assertThat(e.step()).isEqualTo("CREATE_CUSTOMER");
                    assertThat(e.getMessage()).doesNotContain(CPF);
                });
        assertThat(output.getAll()).doesNotContain(CPF);
    }

    @Test
    void shouldRefuseOtherClientErrors() {
        replyWith(400, "{\"errors\":[{\"code\":\"invalid_value\",\"description\":\"x\"}]}");

        assertKind(
                () -> gateway().createCharge("cus_1", UUID.randomUUID(), 100, LocalDate.now(), "d"),
                PixGatewayException.Kind.REFUSED);
    }

    private static String errorsBody(int count, int documentIndex) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = i == documentIndex ? "invalid_cpfCnpj" : "code_" + i;
            errors.add("{\"code\":\"" + code + "\",\"description\":\"x\"}");
        }
        return "{\"errors\":[" + String.join(",", errors) + "]}";
    }

    @Test
    @DisplayName("SEC-018: shouldLogAtMostTenErrorCodes")
    void shouldLogAtMostTenErrorCodes(CapturedOutput output) {
        replyWith(400, errorsBody(50, -1));

        assertKind(
                () -> gateway().createCharge("cus_1", UUID.randomUUID(), 100, LocalDate.now(), "d"),
                PixGatewayException.Kind.REFUSED);
        assertThat(output.getAll()).contains("code_9").doesNotContain("code_10");
    }

    @Test
    @DisplayName("SEC-033: shouldStillRecognizeARejectedDocumentAmongTheFirstTenCodes")
    void shouldStillRecognizeARejectedDocumentAmongTheFirstTenCodes() {
        replyWith(400, errorsBody(50, 9));

        assertKind(
                () -> gateway().createCustomer(UUID.randomUUID(), "ana@example.com", CPF),
                PixGatewayException.Kind.DOCUMENT_REJECTED);
    }

    @Test
    @DisplayName("SEC-018: shouldReportAnUnexpectedClientFailureAsUnavailable")
    void shouldReportAnUnexpectedClientFailureAsUnavailable(CapturedOutput output) {
        AsaasPixGateway failing = new AsaasPixGateway(
                RestClient.builder().requestInterceptor((request, body, execution) -> {
                    throw new IllegalStateException(MARKER);
                }),
                baseUrl(),
                KEY,
                CONNECT,
                READ);

        assertThatThrownBy(() -> failing.readCharge("pay_abc")).isInstanceOfSatisfying(PixGatewayException.class, e -> {
            assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
            assertThat(e.getMessage()).doesNotContain(MARKER);
        });
        assertThat(output.getAll()).doesNotContain(MARKER);
    }

    static Stream<Arguments> unavailableAnswers() {
        return Stream.of(
                Arguments.of(500, "{\"errors\":[]}"),
                Arguments.of(429, "{\"errors\":[]}"),
                Arguments.of(200, "{}"),
                Arguments.of(200, "{not json"));
    }

    @ParameterizedTest
    @MethodSource("unavailableAnswers")
    @DisplayName("SEC-018: shouldReportUnavailableForServerErrorAndMalformedAnswers")
    void shouldReportUnavailableForServerErrorAndMalformedAnswers(int status, String body) {
        replyWith(status, body);
        AsaasPixGateway gateway = gateway();

        assertKind(() -> gateway.findCustomerCode(UUID.randomUUID()), PixGatewayException.Kind.UNAVAILABLE);
        assertKind(() -> gateway.createCustomer(UUID.randomUUID(), "a@b.c", CPF), PixGatewayException.Kind.UNAVAILABLE);
        assertKind(
                () -> gateway.createCharge("cus_1", UUID.randomUUID(), 100, LocalDate.now(), "d"),
                PixGatewayException.Kind.UNAVAILABLE);
        assertKind(() -> gateway.readCopyPaste("pay_1"), PixGatewayException.Kind.UNAVAILABLE);
        if (status != 200) {
            assertKind(() -> gateway.cancelCharge("pay_1"), PixGatewayException.Kind.UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("SEC-018: shouldReportUnavailableForAQrCodeAnswerWithoutPayload")
    void shouldReportUnavailableForAQrCodeAnswerWithoutPayload() {
        replyWith(200, "{\"encodedImage\":\"AAAA\",\"expirationDate\":\"2026-11-14\"}");

        assertThatThrownBy(() -> gateway().readCopyPaste("pay_1"))
                .isInstanceOfSatisfying(PixGatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
                    assertThat(e.step()).isEqualTo("READ_QR_CODE");
                });
    }

    @Test
    @DisplayName("SEC-018: shouldGiveUpOnASlowAnswerWithinTheReadTimeout")
    void shouldGiveUpOnASlowAnswerWithinTheReadTimeout() {
        handler = seen -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reply(200, "{\"id\":\"x\"}");
        };
        AsaasPixGateway gateway = gateway(Duration.ofMillis(500));

        List<Runnable> calls = List.of(
                () -> gateway.findCustomerCode(UUID.randomUUID()),
                () -> gateway.createCustomer(UUID.randomUUID(), "a@b.c", CPF),
                () -> gateway.createCharge("cus_1", UUID.randomUUID(), 100, LocalDate.now(), "d"),
                () -> gateway.readCopyPaste("pay_1"),
                () -> gateway.cancelCharge("pay_1"));
        for (Runnable call : calls) {
            long start = System.nanoTime();
            assertKind(call, PixGatewayException.Kind.UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    @DisplayName("SEC-018: shouldGiveUpOnABodyThatStallsAfterTheHeaders")
    void shouldGiveUpOnABodyThatStallsAfterTheHeaders() {
        // the headers and the start of a body arrive, then the server goes quiet
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write("{\"id\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        AsaasPixGateway gateway = gateway(Duration.ofMillis(500));

        List<Runnable> calls = List.of(
                () -> gateway.findCustomerCode(UUID.randomUUID()),
                () -> gateway.createCustomer(UUID.randomUUID(), "a@b.c", CPF),
                () -> gateway.createCharge("cus_1", UUID.randomUUID(), 100, LocalDate.now(), "d"),
                () -> gateway.readCopyPaste("pay_1"),
                () -> gateway.cancelCharge("pay_1"));
        for (Runnable call : calls) {
            long start = System.nanoTime();
            assertKind(call, PixGatewayException.Kind.UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    @DisplayName("SEC-018: shouldGiveUpOnAConnectionThatDoesNotOpenWithinTheConnectTimeout")
    void shouldGiveUpOnAConnectionThatDoesNotOpenWithinTheConnectTimeout() throws IOException {
        // a listener that never accepts, with its backlog filled: new connections hang instead of being refused
        try (ServerSocket full = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> fillers = new ArrayList<>();
            try {
                boolean hangs = false;
                for (int i = 0; i < 20 && !hangs; i++) {
                    Socket filler = new Socket();
                    fillers.add(filler);
                    try {
                        filler.connect(full.getLocalSocketAddress(), 300);
                    } catch (SocketTimeoutException e) {
                        hangs = true;
                    } catch (IOException e) {
                        // refused or reset: this operating system does not leave a full backlog hanging
                        break;
                    }
                }
                assumeTrue(hangs, "this operating system does not leave a full backlog hanging");

                AsaasPixGateway gateway = new AsaasPixGateway(
                        RestClient.builder(),
                        "http://127.0.0.1:" + full.getLocalPort(),
                        KEY,
                        Duration.ofMillis(500),
                        READ);
                long start = System.nanoTime();
                assertKind(() -> gateway.readCopyPaste("pay_1"), PixGatewayException.Kind.UNAVAILABLE);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            } finally {
                for (Socket filler : fillers) {
                    filler.close();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("SEC-022: shouldReportAnInvalidKeyWithoutLoggingIt")
    void shouldReportAnInvalidKeyWithoutLoggingIt(int status, CapturedOutput output) {
        replyWith(status, "{\"errors\":[{\"code\":\"invalid_access_token\",\"description\":\"bad " + KEY + "\"}]}");

        assertThatThrownBy(() -> gateway().readCopyPaste("pay_1"))
                .isInstanceOfSatisfying(PixGatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain(KEY);
                });
        assertThat(output.getAll()).contains("ERROR").doesNotContain(KEY);
    }

    @Test
    @DisplayName("SEC-018: shouldNeverPutTheResponseBodyInTheExceptionOrTheLog")
    void shouldNeverPutTheResponseBodyInTheExceptionOrTheLog(CapturedOutput output) {
        AsaasPixGateway gateway = gateway();
        List<Consumer<AsaasPixGateway>> calls = List.of(
                g -> g.findCustomerCode(UUID.randomUUID()),
                g -> g.createCustomer(UUID.randomUUID(), "a@b.c", CPF),
                g -> g.createCharge("cus_1", UUID.randomUUID(), 100, LocalDate.now(), "d"),
                g -> g.readCopyPaste("pay_1"),
                g -> g.cancelCharge("pay_1"));
        List<String> messages = new ArrayList<>();
        String errorBody =
                "{\"errors\":[{\"code\":\"some_code\",\"description\":\"" + MARKER + "\"}],\"x\":\"" + MARKER + "\"}";
        List<Reply> answers = List.of(
                new Reply(400, errorBody),
                new Reply(500, errorBody),
                // a 200 whose body is not what the adapter expects
                new Reply(200, "{\"unexpected\":\"" + MARKER + "\"}"));
        for (Reply answer : answers) {
            replyWith(answer.status(), answer.body());
            for (Consumer<AsaasPixGateway> call : calls) {
                try {
                    call.accept(gateway);
                } catch (PixGatewayException e) {
                    messages.add(e.getMessage());
                }
            }
        }
        // the copy-and-paste code is returned to the caller but never logged
        replyWith(200, "{\"payload\":\"" + MARKER + "-payload\"}");
        assertThat(gateway.readCopyPaste("pay_1")).isEqualTo(MARKER + "-payload");

        assertThat(messages)
                .isNotEmpty()
                .allSatisfy(message -> assertThat(message).doesNotContain(MARKER));
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(CPF);
    }

    @Test
    @DisplayName("FR-022: lista as cobranças Pix criadas desde a data, com offset e limit")
    void shouldListPixChargesCreatedSinceTheDateWithOffsetAndLimit() {
        replyWith(
                200,
                "{\"object\":\"list\",\"hasMore\":true,\"totalCount\":500,\"limit\":100,\"offset\":100,\"data\":["
                        + "{\"id\":\"pay_1\",\"status\":\"RECEIVED\",\"value\":29.9,\"billingType\":\"PIX\","
                        + "\"externalReference\":\"order-1\",\"deleted\":false,\"description\":\"" + MARKER + "\"},"
                        + "{\"id\":\"pay_2\",\"status\":\"PENDING\",\"value\":10,\"billingType\":\"PIX\","
                        + "\"externalReference\":null,\"deleted\":true}]}");

        PixChargePage page = gateway().listCharges(LocalDate.of(2026, 10, 16), 100, 100);

        assertThat(requests).hasSize(1);
        Seen seen = requests.get(0);
        assertThat(seen.method()).isEqualTo("GET");
        assertThat(seen.path()).isEqualTo("/v3/payments");
        assertThat(seen.query()).contains("billingType=PIX");
        assertThat(seen.query()).contains("offset=100").contains("limit=100");
        assertThat(java.net.URLDecoder.decode(seen.query(), StandardCharsets.UTF_8))
                .contains("dateCreated[ge]=2026-10-16");
        assertThat(seen.headers().get("access_token")).containsExactly(KEY);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.charges()).hasSize(2);
        PixCharge first = page.charges().get(0);
        assertThat(first.id()).isEqualTo("pay_1");
        assertThat(first.status()).isEqualTo("RECEIVED");
        assertThat(first.value()).isEqualByComparingTo("29.90");
        assertThat(first.billingType()).isEqualTo("PIX");
        assertThat(first.externalReference()).isEqualTo("order-1");
        assertThat(first.deleted()).isFalse();
        PixCharge second = page.charges().get(1);
        assertThat(second.id()).isEqualTo("pay_2");
        assertThat(second.externalReference()).isNull();
        assertThat(second.deleted()).isTrue();
    }

    static Stream<Arguments> badListAnswers() {
        String ok = "\"status\":\"PENDING\",\"value\":10,\"billingType\":\"PIX\"";
        return Stream.of(
                Arguments.of(
                        "{\"hasMore\":false,\"data\":[{\"status\":\"PENDING\",\"value\":10,\"billingType\":\"PIX\"}]}"),
                Arguments.of("{\"hasMore\":false,\"data\":[{\"id\":\"pay_1\",\"value\":10,\"billingType\":\"PIX\"}]}"),
                Arguments.of(
                        "{\"hasMore\":false,\"data\":[{\"id\":\"pay_1\",\"status\":\"PENDING\",\"billingType\":\"PIX\"}]}"),
                Arguments.of("{\"hasMore\":false,\"data\":[{\"id\":\"pay_1\"," + ok.replace("10", "\"10\"") + "}]}"),
                Arguments.of("{\"hasMore\":false}"),
                Arguments.of("{\"hasMore\":false,\"data\":{}}"),
                Arguments.of("{\"data\":[]}"),
                Arguments.of("{\"hasMore\":\"false\",\"data\":[]}"),
                Arguments.of("{not json"));
    }

    @ParameterizedTest
    @MethodSource("badListAnswers")
    @DisplayName("FR-022: item da listagem sem id, status ou value é indisponibilidade")
    void shouldReportAnUnexpectedListAnswerAsUnavailable(String body) {
        replyWith(200, body);

        assertThatThrownBy(() -> gateway().listCharges(LocalDate.of(2026, 10, 16), 0, 100))
                .isInstanceOfSatisfying(PixGatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
                    assertThat(e.step()).isEqualTo("LIST_CHARGES");
                });
    }

    @Test
    @DisplayName("FR-022: 429 na listagem é indisponibilidade")
    void shouldReportA429ListAsUnavailable() {
        replyWith(429, "{\"errors\":[]}");

        assertThatThrownBy(() -> gateway().listCharges(LocalDate.of(2026, 10, 16), 0, 100))
                .isInstanceOfSatisfying(PixGatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
                    assertThat(e.step()).isEqualTo("LIST_CHARGES");
                });
    }

    @Test
    @DisplayName("FR-022: listagem acima de 2 MiB é indisponibilidade")
    void shouldReportAListAbove2MiBAsUnavailable() {
        // a valid page followed by blanks: cut at 2 MiB it would still parse, so only a size check refuses it
        String page = "{\"hasMore\":false,\"data\":[]}";
        replyWith(200, page + " ".repeat(2 * 1024 * 1024 + 1));

        assertThatThrownBy(() -> gateway().listCharges(LocalDate.of(2026, 10, 16), 0, 100))
                .isInstanceOfSatisfying(PixGatewayException.class, e -> {
                    assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
                    assertThat(e.step()).isEqualTo("LIST_CHARGES");
                });
    }

    @Test
    @DisplayName("FR-022: página de 100 cobranças, mais de 64 KiB, cabe na listagem")
    void shouldReadAPageLargerThan64KiB() {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            data.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"pay_")
                    .append(i)
                    .append("\",\"status\":\"PENDING\",\"value\":10,\"billingType\":\"PIX\",\"description\":\"")
                    .append("d".repeat(1000))
                    .append("\"}");
        }
        replyWith(200, "{\"hasMore\":false,\"data\":[" + data + "]}");

        PixChargePage page = gateway().listCharges(LocalDate.of(2026, 10, 16), 0, 100);

        assertThat(page.charges()).hasSize(100);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("SEC-030: a falha da listagem não leva o corpo da resposta ao log")
    void shouldNeverPutTheListFailureBodyInTheLog(CapturedOutput output) {
        String planted = "ana.silva@example.com " + CPF + " " + MARKER;
        for (Reply answer : List.of(
                new Reply(500, "{\"errors\":[{\"code\":\"c\",\"description\":\"" + planted + "\"}]}"),
                new Reply(429, "{\"description\":\"" + planted + "\"}"),
                new Reply(200, "{\"hasMore\":false,\"data\":[{\"id\":\"pay_1\",\"description\":\"" + planted + "\"}]}"),
                new Reply(200, "{\"hasMore\":false,\"data\":[],\"x\":\"" + planted + "\""))) {
            replyWith(answer.status(), answer.body());
            assertThatThrownBy(() -> gateway().listCharges(LocalDate.of(2026, 10, 16), 0, 100))
                    .isInstanceOfSatisfying(PixGatewayException.class, e -> {
                        assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
                        assertThat(e.getMessage())
                                .doesNotContain("example.com")
                                .doesNotContain(CPF)
                                .doesNotContain(MARKER);
                    });
        }

        assertThat(output.getAll())
                .contains("LIST_CHARGES")
                .doesNotContain("example.com")
                .doesNotContain(CPF)
                .doesNotContain(MARKER);
    }

    @Test
    @DisplayName("SEC-026: shouldReadTheChargeWithTheApiKey")
    void shouldReadTheChargeWithTheApiKey() {
        replyWith(
                200,
                "{\"id\":\"pay_1\",\"status\":\"RECEIVED\",\"value\":29.9,\"billingType\":\"PIX\","
                        + "\"externalReference\":\"order-1\",\"deleted\":true}");

        PixCharge charge = gateway().readCharge("pay_1");

        Seen seen = requests.get(0);
        assertThat(seen.method()).isEqualTo("GET");
        assertThat(seen.path()).isEqualTo("/v3/payments/pay_1");
        assertThat(seen.headers().get("access_token")).containsExactly(KEY);
        assertThat(seen.headers().get("User-Agent")).containsExactly("sapiensia");
        assertThat(charge.id()).isEqualTo("pay_1");
        assertThat(charge.status()).isEqualTo("RECEIVED");
        assertThat(charge.value()).isEqualByComparingTo("29.90");
        assertThat(charge.billingType()).isEqualTo("PIX");
        assertThat(charge.externalReference()).isEqualTo("order-1");
        assertThat(charge.deleted()).isTrue();
        assertThat(charge.isPaid()).isTrue();
        assertThat(charge.toString()).isEqualTo("PixCharge[id=pay_1, status=RECEIVED]");
    }

    @Test
    @DisplayName("shouldReadAChargeWithoutExternalReference")
    void shouldReadAChargeWithoutExternalReference() {
        replyWith(200, "{\"id\":\"pay_1\",\"status\":\"PENDING\",\"value\":10,\"billingType\":\"PIX\"}");

        PixCharge charge = gateway().readCharge("pay_1");

        assertThat(charge.externalReference()).isNull();
        assertThat(charge.deleted()).isFalse();
        assertThat(charge.isAwaitingPayment()).isTrue();
        assertThat(charge.isPaid()).isFalse();
    }

    @Test
    @DisplayName("SEC-026: shouldReportAMissingChargeAsRefused")
    void shouldReportAMissingChargeAsRefused() {
        replyWith(404, "{\"errors\":[{\"code\":\"invalid_object\"}]}");

        assertThatThrownBy(() -> gateway().readCharge("pay_1")).isInstanceOfSatisfying(PixGatewayException.class, e -> {
            assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.REFUSED);
            assertThat(e.step()).isEqualTo("READ_CHARGE");
        });
    }

    static Stream<Arguments> unreadableChargeAnswers() {
        return Stream.of(
                Arguments.of(500, "{\"errors\":[]}", READ),
                Arguments.of(429, "{\"errors\":[]}", READ),
                Arguments.of(401, "{\"errors\":[]}", READ),
                // a 4xx other than 404 may be transient: retried, never a final "charge not found"
                Arguments.of(400, "{\"errors\":[]}", READ),
                Arguments.of(408, "{\"errors\":[]}", READ),
                Arguments.of(409, "{\"errors\":[]}", READ),
                Arguments.of(200, "{not json", READ),
                Arguments.of(200, "{\"id\":\"pay_1\",\"value\":10,\"billingType\":\"PIX\"}", READ),
                Arguments.of(200, "{\"status\":\"RECEIVED\",\"value\":10,\"billingType\":\"PIX\"}", READ),
                Arguments.of(200, "{\"id\":\"pay_1\",\"status\":\"RECEIVED\",\"value\":10}", READ),
                Arguments.of(
                        200,
                        "{\"id\":\"pay_1\",\"status\":\"RECEIVED\",\"value\":\"10\",\"billingType\":\"PIX\"}",
                        READ),
                // timeout: the server sleeps longer than the read timeout
                Arguments.of(-1, "", Duration.ofMillis(500)));
    }

    @ParameterizedTest
    @MethodSource("unreadableChargeAnswers")
    @DisplayName("SEC-031: shouldReportTheProviderAsUnavailableWhenTheChargeCannotBeRead")
    void shouldReportTheProviderAsUnavailableWhenTheChargeCannotBeRead(int status, String body, Duration readTimeout) {
        if (status < 0) {
            handler = seen -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Reply(200, "{}");
            };
        } else {
            replyWith(status, body);
        }
        AsaasPixGateway gateway = gateway(readTimeout);

        assertThatThrownBy(() -> gateway.readCharge("pay_1")).isInstanceOfSatisfying(PixGatewayException.class, e -> {
            assertThat(e.kind()).isEqualTo(PixGatewayException.Kind.UNAVAILABLE);
            assertThat(e.step()).isEqualTo("READ_CHARGE");
        });
    }

    @Test
    @DisplayName("SEC-018: shouldNeverPutTheChargeAnswerInAnExceptionOrLog")
    void shouldNeverPutTheChargeAnswerInAnExceptionOrLog(CapturedOutput output) {
        AsaasPixGateway gateway = gateway();
        List<String> messages = new ArrayList<>();
        // a good answer carrying the marker in fields the adapter must ignore
        replyWith(
                200,
                "{\"id\":\"pay_1\",\"status\":\"RECEIVED\",\"value\":10,\"billingType\":\"PIX\"," + "\"description\":\""
                        + MARKER + "\",\"customer\":\"" + MARKER + "\"}");
        PixCharge charge = gateway.readCharge("pay_1");
        assertThat(charge.toString()).doesNotContain(MARKER);
        // failing answers carrying the marker
        for (Reply answer : List.of(
                new Reply(404, "{\"errors\":[{\"code\":\"c\",\"description\":\"" + MARKER + "\"}]}"),
                new Reply(500, "{\"description\":\"" + MARKER + "\",\"customer\":\"" + MARKER + "\"}"),
                new Reply(200, "{\"description\":\"" + MARKER + "\",\"customer\":\"" + MARKER + "\"}"))) {
            replyWith(answer.status(), answer.body());
            try {
                gateway.readCharge("pay_1");
            } catch (PixGatewayException e) {
                messages.add(e.getMessage());
            }
        }

        assertThat(messages)
                .hasSize(3)
                .allSatisfy(message -> assertThat(message).doesNotContain(MARKER));
        assertThat(output.getAll()).doesNotContain(MARKER);
    }
}
