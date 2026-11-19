package br.com.sergio.sapiensia.payment;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production: the Asaas HTTP API (v3). Only the fields the application needs are read from the answers, and an answer
 * body, the key, the CPF or the copy-and-paste code never reach a log or an exception message. Logs carry only the step,
 * the HTTP status, the exception class name, the charge id and the Asaas error codes reduced to {@code [A-Za-z0-9_.]}.
 */
@Component
@ConditionalOnProperty(name = "app.pix.provider", havingValue = "asaas")
class AsaasPixGateway implements PixGateway {

    private static final Logger log = LoggerFactory.getLogger(AsaasPixGateway.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LOOPBACK_URL = Pattern.compile("http://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}");
    private static final List<String> ASAAS_URLS = List.of("https://api-sandbox.asaas.com", "https://api.asaas.com");
    private static final Pattern CODE_CHARS = Pattern.compile("[^A-Za-z0-9_.]");
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(10);
    // an Asaas answer is small; anything bigger is read only up to this size
    private static final int MAX_BODY = 64 * 1024;
    // a page of 100 charges does not fit in MAX_BODY; an answer above this is refused, never read in part
    private static final int MAX_LIST_BODY = 2 * 1024 * 1024;
    // an error answer may list many errors; only this many codes are read (for the log and the refused-CPF check)
    private static final int MAX_ERROR_CODES = 10;

    private static final String FIND_CUSTOMER = "FIND_CUSTOMER";
    private static final String CREATE_CUSTOMER = "CREATE_CUSTOMER";
    private static final String CREATE_CHARGE = "CREATE_CHARGE";
    private static final String READ_QR_CODE = "READ_QR_CODE";
    private static final String CANCEL_CHARGE = "CANCEL_CHARGE";
    private static final String READ_CHARGE = "READ_CHARGE";
    private static final String LIST_CHARGES = "LIST_CHARGES";

    private final RestClient restClient;

    // no RestClient.Builder bean exists in this application, so Spring uses this one and starts from a plain builder
    @Autowired
    AsaasPixGateway(
            @Value("${app.asaas.api.base-url}") String baseUrl,
            @Value("${app.asaas.api.key:}") String apiKey,
            @Value("${app.asaas.api.connect-timeout}") Duration connectTimeout,
            @Value("${app.asaas.api.read-timeout}") Duration readTimeout) {
        this(RestClient.builder(), baseUrl, apiKey, connectTimeout, readTimeout);
    }

    // the builder is a test seam; the timeouts are applied here so the tests exercise the real ones
    AsaasPixGateway(
            RestClient.Builder builder, String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("APP_ASAAS_API_KEY is required when app.pix.provider=asaas");
        }
        // the key goes only to Asaas (or to a local server in tests), never to another host
        if (baseUrl == null
                || !(ASAAS_URLS.contains(baseUrl)
                        || LOOPBACK_URL.matcher(baseUrl).matches())) {
            throw new IllegalStateException("app.asaas.api.base-url must be an Asaas address or a loopback address");
        }
        requireWithin("connect-timeout", connectTimeout, MAX_CONNECT_TIMEOUT);
        requireWithin("read-timeout", readTimeout, MAX_READ_TIMEOUT);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("access_token", apiKey)
                .defaultHeader("User-Agent", "sapiensia")
                .build();
    }

    private static void requireWithin(String name, Duration value, Duration max) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(max) > 0) {
            throw new IllegalStateException("app.asaas.api." + name + " must be above zero and at most " + max);
        }
    }

    // request bodies: the toString hides the values, so a stray log of a body leaks nothing
    private record CustomerBody(String name, String cpfCnpj, String externalReference, boolean notificationDisabled) {
        @Override
        public String toString() {
            return "CustomerBody[redacted]";
        }
    }

    private record ChargeBody(
            String customer,
            String billingType,
            BigDecimal value,
            String dueDate,
            String description,
            String externalReference) {
        @Override
        public String toString() {
            return "ChargeBody[redacted]";
        }
    }

    @Override
    public Optional<String> findCustomerCode(UUID customerId) {
        JsonNode root = call(
                FIND_CUSTOMER,
                null,
                restClient.get().uri("/v3/customers?externalReference={ref}", customerId.toString()),
                false);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw unavailable(FIND_CUSTOMER, "unexpected answer", null);
        }
        if (data.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(requiredText(FIND_CUSTOMER, data.get(0), "id", null));
    }

    @Override
    public String createCustomer(UUID customerId, String name, String cpf) {
        JsonNode root = call(
                CREATE_CUSTOMER,
                null,
                restClient
                        .post()
                        .uri("/v3/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CustomerBody(name, cpf, customerId.toString(), true)),
                false);
        return requiredText(CREATE_CUSTOMER, root, "id", null);
    }

    @Override
    public String createCharge(
            String customerCode, UUID orderId, int amountCents, LocalDate dueDate, String description) {
        JsonNode root = call(
                CREATE_CHARGE,
                null,
                restClient
                        .post()
                        .uri("/v3/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ChargeBody(
                                customerCode,
                                "PIX",
                                BigDecimal.valueOf(amountCents, 2),
                                dueDate.toString(),
                                description,
                                orderId.toString())),
                false);
        String chargeId = requiredText(CREATE_CHARGE, root, "id", null);
        if (!PaymentConfirmation.CHARGE_ID.matcher(chargeId).matches()) {
            // the refused value is never logged
            throw unavailable(CREATE_CHARGE, "unexpected answer", null);
        }
        return chargeId;
    }

    @Override
    public String readCopyPaste(String chargeId) {
        JsonNode root =
                call(READ_QR_CODE, chargeId, restClient.get().uri("/v3/payments/{id}/pixQrCode", chargeId), false);
        // only the payload: the image is ignored
        return requiredText(READ_QR_CODE, root, "payload", chargeId);
    }

    @Override
    public PixCharge readCharge(String chargeId) {
        JsonNode root = call(READ_CHARGE, chargeId, restClient.get().uri("/v3/payments/{id}", chargeId), false);
        return toCharge(READ_CHARGE, root, chargeId);
    }

    @Override
    public PixChargePage listCharges(LocalDate createdFrom, int offset, int limit) {
        JsonNode root = call(
                LIST_CHARGES,
                null,
                restClient
                        .get()
                        .uri(
                                "/v3/payments?billingType=PIX&dateCreated[ge]={from}&offset={offset}&limit={limit}",
                                createdFrom.toString(),
                                offset,
                                limit),
                false,
                MAX_LIST_BODY,
                true);
        JsonNode data = root.get("data");
        JsonNode hasMore = root.get("hasMore");
        if (data == null || !data.isArray() || hasMore == null || !hasMore.isBoolean()) {
            throw unavailable(LIST_CHARGES, "unexpected answer", null);
        }
        List<PixCharge> charges = new ArrayList<>();
        for (JsonNode item : data) {
            charges.add(toCharge(LIST_CHARGES, item, null));
        }
        return new PixChargePage(charges, hasMore.asBoolean());
    }

    /** Reads the six fields the application needs from a charge; the rest of the answer is dropped. */
    private static PixCharge toCharge(String step, JsonNode node, String chargeId) {
        JsonNode value = node.get("value");
        if (value == null || !value.isNumber()) {
            throw unavailable(step, "unexpected answer", chargeId);
        }
        JsonNode reference = node.get("externalReference");
        JsonNode deleted = node.get("deleted");
        if (deleted != null && !deleted.isNull() && !deleted.isBoolean()) {
            throw unavailable(step, "unexpected answer", chargeId);
        }
        return new PixCharge(
                requiredText(step, node, "id", chargeId),
                requiredText(step, node, "status", chargeId),
                value.decimalValue(),
                requiredText(step, node, "billingType", chargeId),
                reference != null && reference.isString() ? reference.asString() : null,
                deleted != null && deleted.asBoolean());
    }

    @Override
    public void cancelCharge(String chargeId) {
        // a 404 means the charge is already gone: that is a success
        call(CANCEL_CHARGE, chargeId, restClient.delete().uri("/v3/payments/{id}", chargeId), true);
    }

    /** Sends the request and returns the parsed JSON answer, or null when {@code lenient} and the answer is 2xx or 404. */
    private JsonNode call(String step, String chargeId, RestClient.RequestHeadersSpec<?> request, boolean lenient) {
        return call(step, chargeId, request, lenient, MAX_BODY, false);
    }

    /** An answer above {@code maxBody} is refused when {@code refuseOversized}, otherwise read only up to the limit. */
    private JsonNode call(
            String step,
            String chargeId,
            RestClient.RequestHeadersSpec<?> request,
            boolean lenient,
            int maxBody,
            boolean refuseOversized) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                // the request factory's read timeout also closes a body that stalls after the headers: the read
                // then fails with an IOException
                byte[] body;
                try {
                    body = response.getBody().readNBytes(maxBody + 1);
                    if (body.length > maxBody) {
                        if (refuseOversized) {
                            throw unavailable(step, "HTTP " + status + " answer too large", chargeId);
                        }
                        body = Arrays.copyOf(body, maxBody);
                    }
                } catch (IOException e) {
                    throw unavailable(step, e.getClass().getSimpleName(), chargeId);
                }
                if (response.getStatusCode().is2xxSuccessful()) {
                    if (lenient) {
                        return null;
                    }
                    return parseObject(step, chargeId, status, body);
                }
                if (lenient && status == 404) {
                    return null;
                }
                throw failure(step, chargeId, status, body);
            });
        } catch (PixGatewayException e) {
            // already classified inside the exchange: keep its kind
            throw e;
        } catch (RuntimeException e) {
            // timeout, network error or any unexpected client failure: only the class name is reported
            throw unavailable(step, e.getClass().getSimpleName(), chargeId);
        }
    }

    private static JsonNode parseObject(String step, String chargeId, int status, byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root != null && root.isObject()) {
                return root;
            }
        } catch (JacksonException e) {
            // falls through to the same failure as a body of the wrong shape
        }
        throw unavailable(step, "HTTP " + status + " unexpected answer", chargeId);
    }

    private static String requiredText(String step, JsonNode node, String field, String chargeId) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw unavailable(step, "unexpected answer", chargeId);
        }
        return value.asString();
    }

    private static PixGatewayException failure(String step, String chargeId, int status, byte[] body) {
        List<String> codes = errorCodes(body);
        PixGatewayException.Kind kind;
        if (status == 401 || status == 403) {
            // the key is wrong or revoked: someone has to fix the configuration
            log.error("Asaas refused the API key: step={} HTTP {}", step, status);
            kind = PixGatewayException.Kind.UNAVAILABLE;
        } else if (status == 429 || status >= 500) {
            kind = PixGatewayException.Kind.UNAVAILABLE;
        } else if (status == 400
                && CREATE_CUSTOMER.equals(step)
                && codes.stream().anyMatch(code -> code.toLowerCase(Locale.ROOT).contains("cpfcnpj"))) {
            kind = PixGatewayException.Kind.DOCUMENT_REJECTED;
        } else if (READ_CHARGE.equals(step) && status != 404 && status >= 400 && status < 500) {
            // only a 404 is a final "charge not found"; any other 4xx may be transient and must be retried
            kind = PixGatewayException.Kind.UNAVAILABLE;
        } else if (status >= 400 && status < 500) {
            kind = PixGatewayException.Kind.REFUSED;
        } else {
            kind = PixGatewayException.Kind.UNAVAILABLE;
        }
        log.warn("Asaas call failed: step={} HTTP {} charge={} codes={}", step, status, chargeId, codes);
        return new PixGatewayException(kind, step, "HTTP " + status);
    }

    // a body that cannot be read changes nothing: the kind comes from the status
    private static List<String> errorCodes(byte[] body) {
        List<String> codes = new ArrayList<>();
        try {
            JsonNode errors = JSON.readTree(body).get("errors");
            if (errors != null && errors.isArray()) {
                for (JsonNode error : errors) {
                    if (codes.size() >= MAX_ERROR_CODES) {
                        break;
                    }
                    JsonNode code = error.get("code");
                    if (code != null && code.isValueNode()) {
                        String clean = CODE_CHARS.matcher(code.asString()).replaceAll("");
                        if (clean.length() > 64) {
                            clean = clean.substring(0, 64);
                        }
                        if (!clean.isEmpty()) {
                            codes.add(clean);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // ignored on purpose
        }
        return codes;
    }

    private static PixGatewayException unavailable(String step, String reason, String chargeId) {
        log.warn("Asaas call failed: step={} reason={} charge={}", step, reason, chargeId);
        return new PixGatewayException(PixGatewayException.Kind.UNAVAILABLE, step, reason);
    }
}
