package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Known devices and the new-device e-mail (ADR-0004, SEC-007). A real server, so each request is a separate browser;
 * the device cookie is sent only when a test passes it. Own context, like SessionRevocationTest, for the real
 * XSRF-TOKEN cookie.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // every request comes from one IP and the class logs in more than the default per-IP limit
        properties = {"test.isolated-context=new-device-notice", "app.login-limit.ip-max-attempts=1000"})
class NewDeviceNoticeTest {

    private static final String PASSWORD = "senha-antiga-123";
    private static final String NEW_PASSWORD = "senha-nova-4567"; // gitleaks:allow
    private static final String SUBJECT = "Novo acesso à sua conta na SapiensIA";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration NO_MAIL_WAIT = Duration.ofSeconds(3);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    PasswordResetTokens tokens;

    @Autowired
    RecordingEmailSender sender;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLogs() {
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(logs);
        logs.stop();
    }

    private record Account(UUID id, String email) {}

    private Account newAccount() {
        Account account = new Account(UUID.randomUUID(), "device-" + UUID.randomUUID() + "@example.com");
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, ?, now())",
                account.id(),
                account.email(),
                passwordEncoder.encode(PASSWORD));
        return account;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static Optional<String> setCookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .reduce((first, last) -> last);
    }

    private static String cookieValue(HttpResponse<?> response, String name) {
        String header = setCookie(response, name).orElseThrow();
        return header.substring(name.length() + 1, header.indexOf(';'));
    }

    private HttpResponse<String> postJson(String path, String json, String deviceCookie) throws Exception {
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(url("/entrar"))).build(), HttpResponse.BodyHandlers.ofString());
        String xsrf = cookieValue(page, "XSRF-TOKEN");
        String cookies = "XSRF-TOKEN=" + xsrf + (deviceCookie == null ? "" : "; SAPIENSIA_DEVICE=" + deviceCookie);
        return client.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .header("Cookie", cookies)
                        .header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(Account account, String password, String deviceCookie) throws Exception {
        return postJson(
                "/api/sessions",
                "{\"email\":\"" + account.email() + "\",\"password\":\"" + password + "\"}",
                deviceCookie);
    }

    private String deviceOf(HttpResponse<String> login) {
        return cookieValue(login, "SAPIENSIA_DEVICE");
    }

    private Optional<EmailMessage> mail(Account account, Duration wait) throws Exception {
        return sender.awaitMessageTo(account.email(), wait);
    }

    private int deviceRows(UUID customerId) {
        return jdbc.queryForObject(
                "select count(*) from customer_devices where customer_id = ?", Integer.class, customerId);
    }

    @Test
    @DisplayName("SEC-007: o primeiro login não avisa e entrega o cookie do aparelho com os atributos certos")
    void shouldSetTheDeviceCookieAndNotNotifyOnTheFirstLogin() throws Exception {
        Account account = newAccount();

        HttpResponse<String> response = login(account, PASSWORD, null);

        assertThat(response.statusCode()).isEqualTo(204);
        String header = setCookie(response, "SAPIENSIA_DEVICE").orElseThrow();
        assertThat(header.split("; ")[0]).matches("SAPIENSIA_DEVICE=[A-Za-z0-9_-]{43}");
        assertThat(List.of(header.split("; ")))
                .contains("Max-Age=31536000", "Path=/api/sessions", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure");
        assertThat(mail(account, NO_MAIL_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("SEC-007: o segundo login sem cookie manda o e-mail de aparelho novo, com a data de Brasília")
    void shouldEmailTheOwnerAboutASecondDeviceWithTheLoginTime() throws Exception {
        Account account = newAccount();
        login(account, PASSWORD, null);

        Instant before = Instant.now();
        HttpResponse<String> second = login(account, PASSWORD, null);
        Instant after = Instant.now();

        assertThat(second.statusCode()).isEqualTo(204);
        EmailMessage message = mail(account, WAIT).orElseThrow();
        assertThat(message.subject()).isEqualTo(SUBJECT);
        Matcher date = Pattern.compile("acessada em (\\d\\d/\\d\\d/\\d{4} às \\d\\d:\\d\\d) \\(horário de Brasília\\)")
                .matcher(message.text());
        assertThat(date.find()).isTrue();
        ZonedDateTime sent = ZonedDateTime.parse(
                date.group(1).replace(" às ", " "),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Sao_Paulo")));
        // the e-mail shows minutes only, so the lower bound is the minute of the instant before the login
        assertThat(sent.toInstant()).isBetween(before.truncatedTo(ChronoUnit.MINUTES), after);
        assertThat(message.text()).isEqualTo("""
                        Olá!

                        Sua conta na SapiensIA foi acessada em %s (horário de Brasília), em um aparelho ou navegador \
                        que ainda não tínhamos visto.

                        Se foi você, não precisa fazer nada.

                        Se não foi você, use "Esqueci minha senha" para criar outra senha agora. A troca de senha \
                        desconecta todos os aparelhos.
                        """.formatted(date.group(1)));
        assertThat(message.text()).doesNotContain(PASSWORD).doesNotContain(deviceOf(second));
    }

    @Test
    @DisplayName("SEC-007: um login com o cookie recebido antes não manda e-mail e mantém o mesmo cookie")
    void shouldNotNotifyWhenTheDeviceIsKnown() throws Exception {
        Account account = newAccount();
        String device = deviceOf(login(account, PASSWORD, null));

        HttpResponse<String> again = login(account, PASSWORD, device);

        assertThat(again.statusCode()).isEqualTo(204);
        assertThat(deviceOf(again)).isEqualTo(device);
        assertThat(mail(account, NO_MAIL_WAIT)).isEmpty();
        assertThat(deviceRows(account.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: o cookie da conta A no primeiro login da conta B não avisa B e volta o mesmo valor")
    void shouldNotNotifyAccountBOnItsFirstLoginWithTheCookieOfAccountA() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        String deviceOfA = deviceOf(login(a, PASSWORD, null));

        HttpResponse<String> firstOfB = login(b, PASSWORD, deviceOfA);

        assertThat(firstOfB.statusCode()).isEqualTo(204);
        assertThat(deviceOf(firstOfB)).isEqualTo(deviceOfA);
        assertThat(mail(b, NO_MAIL_WAIT)).isEmpty();
        assertThat(mail(a, NO_MAIL_WAIT)).isEmpty();
        assertThat(deviceRows(b.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: sem cookie, o segundo login de B avisa B")
    void shouldNotifyAccountBOnASecondLoginWithoutCookie() throws Exception {
        Account b = newAccount();
        login(b, PASSWORD, null);

        login(b, PASSWORD, null);

        assertThat(mail(b, WAIT).orElseThrow().subject()).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("SEC-007: o cookie de A, depois do primeiro login de B, é aparelho novo de B")
    void shouldTreatTheCookieOfAccountAAsANewDeviceOfBAfterItsFirstLogin() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        String deviceOfA = deviceOf(login(a, PASSWORD, null));
        login(b, PASSWORD, null);

        HttpResponse<String> response = login(b, PASSWORD, deviceOfA);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(mail(b, WAIT).orElseThrow().subject()).isEqualTo(SUBJECT);
        assertThat(deviceOf(response)).isEqualTo(deviceOfA);
        assertThat(deviceRows(b.id())).isEqualTo(2);
        assertThat(deviceRows(a.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: o cookie de aparelho sozinho não dá acesso")
    void shouldNotGrantAccessWithTheDeviceCookieAlone() throws Exception {
        Account account = newAccount();
        String device = deviceOf(login(account, PASSWORD, null));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/sessions/current")))
                        .header("Cookie", "SAPIENSIA_DEVICE=" + device)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-007: um cookie lixo vale como ausente, não é gravado e é trocado por um novo")
    void shouldTreatAMalformedCookieAsAbsent() throws Exception {
        Account account = newAccount();
        login(account, PASSWORD, null);

        HttpResponse<String> response = login(account, PASSWORD, "abc");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(mail(account, WAIT).orElseThrow().subject()).isEqualTo(SUBJECT);
        assertThat(deviceOf(response)).isNotEqualTo("abc").hasSize(43);
        Integer rows = jdbc.queryForObject(
                "select count(*) from customer_devices where token_hash = ?",
                Integer.class,
                EmailConfirmationTokens.hash("abc"));
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("SEC-007: senha errada de aparelho novo dá 401, sem cookie, sem e-mail e sem linha nova")
    void shouldNotRegisterNorEmailOnAWrongPassword() throws Exception {
        Account account = newAccount();
        login(account, PASSWORD, null);

        HttpResponse<String> response = login(account, "senha-errada-999", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(setCookie(response, "SAPIENSIA_DEVICE")).isEmpty();
        assertThat(mail(account, NO_MAIL_WAIT)).isEmpty();
        assertThat(deviceRows(account.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: a troca de senha pelo link esquece os aparelhos e o cookie antigo vira aparelho novo")
    void shouldForgetTheDevicesWhenThePasswordIsReset() throws Exception {
        Account account = newAccount();
        String device = deviceOf(login(account, PASSWORD, null));
        String resetToken = tokens.issue(account.id());

        HttpResponse<String> reset = postJson(
                "/api/customers/password-resets/confirmation",
                "{\"token\":\"" + resetToken + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}",
                null);
        assertThat(reset.statusCode()).isEqualTo(204);
        assertThat(deviceRows(account.id())).isZero();
        // the password-changed notice arrives first
        assertThat(mail(account, WAIT).orElseThrow().subject()).isEqualTo("Sua senha na SapiensIA foi alterada");

        HttpResponse<String> after = login(account, NEW_PASSWORD, device);

        assertThat(after.statusCode()).isEqualTo(204);
        assertThat(mail(account, WAIT).orElseThrow().subject()).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("SEC-007: um envio que falha não quebra o login, e o e-mail chega na nova tentativa")
    void shouldKeepTheLoginWorkingWhenTheFirstSendFails() throws Exception {
        Account account = newAccount();
        login(account, PASSWORD, null);
        sender.failNextSendTo(account.email());

        HttpResponse<String> second = login(account, PASSWORD, null);

        assertThat(second.statusCode()).isEqualTo(204);
        String session = "JSESSIONID=" + cookieValue(second, "JSESSIONID");
        HttpResponse<String> current = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/sessions/current")))
                        .header("Cookie", session)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(mail(account, Duration.ofSeconds(60)).orElseThrow().subject())
                .isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("SEC-007: nenhum log do fluxo contém o token do aparelho nem o e-mail")
    void shouldNotLogTheDeviceTokenNorTheEmail() throws Exception {
        Account account = newAccount();
        String device = deviceOf(login(account, PASSWORD, null));
        HttpResponse<String> second = login(account, PASSWORD, null);
        mail(account, WAIT).orElseThrow();

        List<String> lines =
                logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(lines).noneMatch(l -> l.contains(device) || l.contains(deviceOf(second)));
        assertThat(lines).noneMatch(l -> l.contains(account.email()));
    }
}
