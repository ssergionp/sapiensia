package br.com.sergio.sapiensia.alert;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The subject and body of an alert e-mail (pt-BR, plain text). The only variable content is a closed code, UUIDs, dates
 * and counts: never personal data (ADR-0006, SEC-036).
 */
class AdminAlertEmail {

    /** One pending record, as listed in the e-mail. */
    record Line(Instant occurredAt, String detail, UUID paymentEventId, UUID orderId, int occurrences) {}

    private record Text(String title, String intro, String action) {}

    private static final DateTimeFormatter BRASILIA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));

    private AdminAlertEmail() {}

    private static Text text(AdminAlertType type) {
        return switch (type) {
            case PAYMENT_EVENT_GAVE_UP ->
                new Text(
                        "evento de pagamento não processado",
                        "O sistema esgotou as tentativas de processar um evento de pagamento e desistiu. Pode haver"
                                + " Cliente que pagou e não recebeu o Acesso.",
                        "reprocesse o evento pelo runbook webhook-asaas.md, seção \"Reprocessar um evento de"
                                + " pagamento\".");
            case PAYMENT_EVENT_NEEDS_REVIEW ->
                new Text(
                        "evento de pagamento para revisar",
                        "Um evento de pagamento terminou com um resultado que precisa da sua conferência.",
                        "confira o Pedido no painel e a cobrança no Asaas. O significado de cada resultado está no"
                                + " runbook webhook-asaas.md, seções \"Conferir um pagamento\" e \"Reembolso e"
                                + " MED\".");
            case RECONCILIATION_DIVERGENCE ->
                new Text(
                        "divergência na conciliação com o Asaas",
                        "A conciliação noturna comparou os Pedidos com as cobranças do Asaas e encontrou diferenças"
                                + " que o sistema não corrige sozinho.",
                        "veja o que cada código quer dizer e como achar a cobrança no runbook conciliacao-asaas.md,"
                                + " seção \"Divergências\". Cada divergência é avisada uma vez só.");
            case REFUND_REQUESTED ->
                new Text(
                        "Reembolso solicitado",
                        "Um Cliente pediu o Reembolso de um Pedido pelo site. O Acesso daquele Pedido já está"
                                + " suspenso.",
                        "veja o motivo no painel, em Reembolsos, e devolva o valor inteiro no painel do Asaas, pelo"
                                + " runbook suporte-cliente.md, seção \"Reembolso\".");
            case REFUND_REQUESTS_REPEATED ->
                new Text(
                        "Cliente com 3 ou mais Reembolsos em 12 meses",
                        "Um Cliente pediu um Reembolso e, com ele, soma 3 ou mais em 12 meses.",
                        "o Reembolso dentro do prazo continua devido e novas compras não são bloqueadas. Veja o"
                                + " histórico no painel, em Reembolsos, e siga o runbook suporte-cliente.md, seção"
                                + " \"Reembolsos repetidos\".");
            case RECONCILIATION_FAILED ->
                new Text(
                        "conciliação com o Asaas não terminou",
                        "A conciliação noturna com o Asaas parou antes do fim. O que já tinha sido conferido continua"
                                + " valendo; o resto não foi comparado nesta noite.",
                        "a próxima noite tenta de novo sozinha. Para rodar agora, ou se este aviso se repetir, siga o"
                                + " runbook conciliacao-asaas.md, seção \"A conciliação não terminou\".");
            case ORDER_PAID_EMAIL_GAVE_UP ->
                new Text(
                        "e-mail de pagamento não enviado",
                        "O e-mail de pagamento confirmado não foi enviado ao Cliente depois de todas as tentativas."
                                + " O Pedido está pago e o Acesso foi concedido; só o e-mail falhou.",
                        "reenvie pelo runbook webhook-asaas.md, seção \"Reenviar o e-mail de pagamento"
                                + " confirmado\".");
            case ORDER_REFUNDED_EMAIL_GAVE_UP ->
                new Text(
                        "e-mail de reembolso não enviado",
                        "O e-mail que avisa o Cliente do Reembolso não foi enviado depois de todas as tentativas."
                                + " O Pedido está reembolsado e o Acesso foi cancelado; só o e-mail falhou.",
                        "avise o Cliente do Reembolso pelo e-mail de suporte, seguindo o runbook suporte-cliente.md."
                                + " O id do Pedido está em cada linha.");
            case WEBHOOK_REJECTED ->
                new Text(
                        "webhook do Asaas recusado",
                        "O sistema recusou chamadas ao webhook do Asaas. Enquanto o Asaas for recusado, os"
                                + " pagamentos não são confirmados.",
                        "confira o token e a lista de IPs pelo runbook webhook-asaas.md, seção \"Alertas por"
                                + " e-mail\". O IP de origem aparece só no log da aplicação.");
            case TASK_GAVE_UP ->
                new Text(
                        "tarefa em segundo plano descartada",
                        "Uma tarefa em segundo plano falhou em todas as tentativas e foi descartada.",
                        "procure o nome da tarefa no log da aplicação para ver o motivo. Se for uma tarefa de"
                                + " e-mail, o Cliente pode pedir o e-mail de novo pelo site.");
            case RECURRING_TASK_FAILING -> {
                int first = RecurringTaskFailureAlerts.CONSECUTIVE_FAILURES_TO_ALERT;
                yield new Text(
                        "tarefa recorrente falhando",
                        "Uma tarefa em segundo plano que roda de tempos em tempos falhou pelo menos " + first
                                + " vezes seguidas. Ela continua tentando sozinha no próximo horário.",
                        "procure o nome da tarefa no log da aplicação para ver o motivo. Enquanto a tarefa continuar"
                                + " falhando, este aviso se repete com intervalos cada vez maiores: na " + first
                                + "ª, na " + 2 * first + "ª, na " + 4 * first + "ª falha seguida e assim por diante.");
            }
        };
    }

    static String subject(AdminAlertType type, int total) {
        return "Alerta SapiensIA: " + text(type).title() + " (" + total + ")";
    }

    static String body(AdminAlertType type, int total, List<Line> newestFirst, int notListed, Duration groupWindow) {
        Text text = text(type);
        boolean eventType = type == AdminAlertType.PAYMENT_EVENT_GAVE_UP
                || type == AdminAlertType.PAYMENT_EVENT_NEEDS_REVIEW
                || type == AdminAlertType.RECONCILIATION_DIVERGENCE;
        StringBuilder body = new StringBuilder();
        body.append("Olá!\n\n")
                .append(text.intro())
                .append("\n\nOcorrências desde o último aviso deste tipo: ")
                .append(total)
                .append("\n\n");
        for (Line line : newestFirst) {
            body.append("- ").append(line(line, eventType)).append('\n');
        }
        if (notListed > 0) {
            body.append("… e mais ").append(notListed).append(" registros que não couberam neste e-mail.\n");
        }
        body.append("\nO que fazer: ")
                .append(text.action())
                .append("\n\nEste aviso é automático. Avisos do mesmo tipo são agrupados: no máximo um e-mail a cada ")
                .append(groupWindow.toMinutes())
                .append(" minutos.");
        return body.toString();
    }

    private static String line(Line line, boolean eventType) {
        List<String> parts = new ArrayList<>();
        parts.add(BRASILIA.format(line.occurredAt()));
        if (line.detail() != null) {
            parts.add(line.detail());
        }
        if (line.paymentEventId() != null) {
            parts.add("evento " + line.paymentEventId());
        }
        if (line.orderId() != null) {
            parts.add("Pedido " + line.orderId());
        } else if (eventType) {
            parts.add("Pedido não identificado");
        }
        if (line.occurrences() > 1) {
            parts.add("ocorrências: " + line.occurrences());
        }
        return String.join(" · ", parts);
    }
}
