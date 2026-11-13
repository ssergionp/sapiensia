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
                    } catch (IOException e) {
                        hangs = true;
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
}
