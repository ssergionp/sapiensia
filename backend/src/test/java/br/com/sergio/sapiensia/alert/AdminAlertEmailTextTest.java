package br.com.sergio.sapiensia.alert;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.alert.AdminAlertEmail.Line;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The approved pt-BR texts of the alert e-mails (plan #38, section 4), letter for letter. */
class AdminAlertEmailTextTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    // 17:32 UTC is 14:32 in Brasília (no daylight saving time in Brazil)
    private static final Instant WHEN = Instant.parse("2026-11-15T17:32:00Z");
    private static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String FOOTER =
            "Este aviso é automático. Avisos do mesmo tipo são agrupados: no máximo um e-mail a cada 15 minutos.";

    static Stream<Arguments> texts() {
        return Stream.of(
                Arguments.of(
                        AdminAlertType.PAYMENT_EVENT_GAVE_UP,
                        "evento de pagamento não processado",
                        "O sistema esgotou as tentativas de processar um evento de pagamento e desistiu. Pode haver"
                                + " Cliente que pagou e não recebeu o Acesso.",
                        "reprocesse o evento pelo runbook webhook-asaas.md, seção \"Reprocessar um evento de"
                                + " pagamento\"."),
                Arguments.of(
                        AdminAlertType.PAYMENT_EVENT_NEEDS_REVIEW,
                        "evento de pagamento para revisar",
                        "Um evento de pagamento terminou com um resultado que precisa da sua conferência.",
                        "confira o Pedido no painel e a cobrança no Asaas. O significado de cada resultado está no"
                                + " runbook webhook-asaas.md, seções \"Conferir um pagamento\" e \"Reembolso e MED\"."),
                Arguments.of(
                        AdminAlertType.RECONCILIATION_DIVERGENCE,
                        "divergência na conciliação com o Asaas",
                        "A conciliação noturna comparou os Pedidos com as cobranças do Asaas e encontrou diferenças"
                                + " que o sistema não corrige sozinho.",
                        "veja o que cada código quer dizer e como achar a cobrança no runbook conciliacao-asaas.md,"
                                + " seção \"Divergências\". Cada divergência é avisada uma vez só."),
                Arguments.of(
                        AdminAlertType.RECONCILIATION_FAILED,
                        "conciliação com o Asaas não terminou",
                        "A conciliação noturna com o Asaas parou antes do fim. O que já tinha sido conferido continua"
                                + " valendo; o resto não foi comparado nesta noite.",
                        "a próxima noite tenta de novo sozinha. Para rodar agora, ou se este aviso se repetir, siga o"
                                + " runbook conciliacao-asaas.md, seção \"A conciliação não terminou\"."),
                Arguments.of(
                        AdminAlertType.ORDER_PAID_EMAIL_GAVE_UP,
                        "e-mail de pagamento não enviado",
                        "O e-mail de pagamento confirmado não foi enviado ao Cliente depois de todas as tentativas."
                                + " O Pedido está pago e o Acesso foi concedido; só o e-mail falhou.",
                        "reenvie pelo runbook webhook-asaas.md, seção \"Reenviar o e-mail de pagamento"
                                + " confirmado\"."),
                Arguments.of(
                        AdminAlertType.WEBHOOK_REJECTED,
                        "webhook do Asaas recusado",
                        "O sistema recusou chamadas ao webhook do Asaas. Enquanto o Asaas for recusado, os"
                                + " pagamentos não são confirmados.",
                        "confira o token e a lista de IPs pelo runbook webhook-asaas.md, seção \"Alertas por"
                                + " e-mail\". O IP de origem aparece só no log da aplicação."),
                Arguments.of(
                        AdminAlertType.TASK_GAVE_UP,
                        "tarefa em segundo plano descartada",
                        "Uma tarefa em segundo plano falhou em todas as tentativas e foi descartada.",
                        "procure o nome da tarefa no log da aplicação para ver o motivo. Se for uma tarefa de"
                                + " e-mail, o Cliente pode pedir o e-mail de novo pelo site."),
                Arguments.of(
                        AdminAlertType.RECURRING_TASK_FAILING,
                        "tarefa recorrente falhando",
                        "Uma tarefa em segundo plano que roda de tempos em tempos falhou pelo menos 3 vezes seguidas. Ela"
                                + " continua tentando sozinha no próximo horário.",
                        "procure o nome da tarefa no log da aplicação para ver o motivo. Enquanto a tarefa continuar"
                                + " falhando, este aviso se repete com intervalos cada vez maiores: na 3ª, na 6ª, na 12ª falha"
                                + " seguida e assim por diante."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("texts")
    @DisplayName("NFR-009: assunto e corpo de cada tipo são os textos aprovados")
    void shouldUseTheApprovedTexts(AdminAlertType type, String title, String intro, String action) {
        List<Line> lines = List.of(new Line(WHEN, null, EVENT, ORDER, 1));

        assertThat(AdminAlertEmail.subject(type, 3)).isEqualTo("Alerta SapiensIA: " + title + " (3)");
        assertThat(AdminAlertEmail.body(type, 3, lines, 0, WINDOW))
                .isEqualTo("Olá!\n\n"
                        + intro
                        + "\n\nOcorrências desde o último aviso deste tipo: 3\n\n"
                        + "- 15/11/2026 14:32 · evento " + EVENT + " · Pedido " + ORDER
                        + "\n\nO que fazer: " + action
                        + "\n\n" + FOOTER);
    }

    @Test
    @DisplayName("SEC-030: o e-mail de divergência da conciliação traz o texto aprovado")
    void shouldUseTheApprovedReconciliationDivergenceText() {
        List<Line> lines = List.of(
                new Line(WHEN, "ORDER_PAID_CHARGE_NOT_PAID", null, ORDER, 1),
                new Line(WHEN, "CHARGE_WITHOUT_ORDER", null, null, 1));

        assertThat(AdminAlertEmail.subject(AdminAlertType.RECONCILIATION_DIVERGENCE, 2))
                .isEqualTo("Alerta SapiensIA: divergência na conciliação com o Asaas (2)");
        assertThat(AdminAlertEmail.body(AdminAlertType.RECONCILIATION_DIVERGENCE, 2, lines, 0, WINDOW))
                .isEqualTo("Olá!\n\n"
                        + "A conciliação noturna comparou os Pedidos com as cobranças do Asaas e encontrou diferenças"
                        + " que o sistema não corrige sozinho."
                        + "\n\nOcorrências desde o último aviso deste tipo: 2\n\n"
                        + "- 15/11/2026 14:32 · ORDER_PAID_CHARGE_NOT_PAID · Pedido " + ORDER + "\n"
                        + "- 15/11/2026 14:32 · CHARGE_WITHOUT_ORDER · Pedido não identificado"
                        + "\n\nO que fazer: veja o que cada código quer dizer e como achar a cobrança no runbook"
                        + " conciliacao-asaas.md, seção \"Divergências\". Cada divergência é avisada uma vez só."
                        + "\n\n" + FOOTER);
    }

    @Test
    @DisplayName("SEC-030: o e-mail de conciliação que não terminou traz o texto aprovado")
    void shouldUseTheApprovedReconciliationFailedText() {
        List<Line> lines = List.of(new Line(WHEN, "ASAAS_UNAVAILABLE", null, null, 1));

        assertThat(AdminAlertEmail.subject(AdminAlertType.RECONCILIATION_FAILED, 1))
                .isEqualTo("Alerta SapiensIA: conciliação com o Asaas não terminou (1)");
        assertThat(AdminAlertEmail.body(AdminAlertType.RECONCILIATION_FAILED, 1, lines, 0, WINDOW))
                .isEqualTo("Olá!\n\n"
                        + "A conciliação noturna com o Asaas parou antes do fim. O que já tinha sido conferido continua"
                        + " valendo; o resto não foi comparado nesta noite."
                        + "\n\nOcorrências desde o último aviso deste tipo: 1\n\n"
                        + "- 15/11/2026 14:32 · ASAAS_UNAVAILABLE"
                        + "\n\nO que fazer: a próxima noite tenta de novo sozinha. Para rodar agora, ou se este aviso"
                        + " se repetir, siga o runbook conciliacao-asaas.md, seção \"A conciliação não terminou\"."
                        + "\n\n" + FOOTER);
    }

    @Test
    @DisplayName("NFR-009: todo tipo de alerta tem texto")
    void shouldHaveTextForEveryType() {
        for (AdminAlertType type : AdminAlertType.values()) {
            assertThat(AdminAlertEmail.subject(type, 1))
                    .startsWith("Alerta SapiensIA: ")
                    .endsWith(" (1)");
            assertThat(AdminAlertEmail.body(type, 1, List.of(), 0, WINDOW))
                    .contains("O que fazer: ")
                    .endsWith(FOOTER);
        }
    }

    @Test
    @DisplayName("NFR-009: a linha junta só as partes que existem e o \"… e mais\" só aparece quando falta registro")
    void shouldBuildLinesFromTheExistingPartsOnly() {
        List<Line> lines = List.of(
                new Line(WHEN, "TOKEN_INVALID", null, null, 7),
                new Line(WHEN, null, EVENT, null, 1),
                new Line(WHEN, "NOT_PAID", EVENT, ORDER, 2));

        String withoutOrder = AdminAlertEmail.body(AdminAlertType.WEBHOOK_REJECTED, 10, lines, 0, WINDOW);
        assertThat(withoutOrder)
                .contains("\n- 15/11/2026 14:32 · TOKEN_INVALID · ocorrências: 7\n")
                .contains("\n- 15/11/2026 14:32 · evento " + EVENT + "\n")
                .contains("\n- 15/11/2026 14:32 · NOT_PAID · evento " + EVENT + " · Pedido " + ORDER
                        + " · ocorrências: 2\n")
                .doesNotContain("… e mais");

        String eventType = AdminAlertEmail.body(AdminAlertType.PAYMENT_EVENT_GAVE_UP, 1, lines, 5, WINDOW);
        assertThat(eventType)
                .contains("\n- 15/11/2026 14:32 · evento " + EVENT + " · Pedido não identificado\n")
                .contains("\n… e mais 5 registros que não couberam neste e-mail.\n\nO que fazer: ");
    }
}
