package br.com.sergio.sapiensia.payment;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates an Asaas webhook body, redacts any CPF/CNPJ and stores it; a repeated event id is dropped. A stored payment
 * event is scheduled for processing in the same transaction.
 */
@Service
class AsaasWebhookService {

    enum Outcome {
        STORED,
        DUPLICATE
    }

    private static final Logger log = LoggerFactory.getLogger(AsaasWebhookService.class);
    private static final Pattern EVENT_ID = Pattern.compile("^[\\x21-\\x7E]{1,255}$");
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z0-9_]{1,64}$");
    /** Only these events can pay an Order; every other type is just stored. */
    private static final Set<String> PAYMENT_TYPES = Set.of("PAYMENT_RECEIVED", "PAYMENT_CONFIRMED");

    private static final String REDACTED = "[redacted]";
    private static final char NUL = (char) 0;
    private static final char REPLACEMENT = (char) 0xFFFD;

    private final PaymentEvents events;
    private final JsonMapper mapper;
    private final Clock clock;
    private final SchedulerClient scheduler;
    private final TransactionTemplate transaction;

    AsaasWebhookService(
            PaymentEvents events,
            JsonMapper mapper,
            Clock clock,
            SchedulerClient scheduler,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.scheduler = scheduler;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    Outcome receive(String rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (JacksonException e) {
            throw new InvalidWebhookBodyException();
        }
        if (root == null || !root.isObject()) {
            throw new InvalidWebhookBodyException();
        }
        String id = text(root, "id", EVENT_ID);
        String type = text(root, "event", EVENT_TYPE);
        sanitize(root);
        String payload = mapper.writeValueAsString(root);
        // the event and its task are written together: an event without a task would never be processed
        boolean stored = Boolean.TRUE.equals(transaction.execute(status -> {
            Optional<UUID> eventId = events.insertIfAbsent(id, type, payload, OffsetDateTime.now(clock));
            if (eventId.isPresent() && PAYMENT_TYPES.contains(type)) {
                scheduler.schedule(
                        new TaskInstance<Void>(
                                PaymentEventTasks.PROCESS, eventId.get().toString()),
                        Instant.now(clock));
            }
            return eventId.isPresent();
        }));
        log.info("Asaas webhook event {}: id={} type={}", stored ? "stored" : "duplicate", id, type);
        return stored ? Outcome.STORED : Outcome.DUPLICATE;
    }

    private static String text(JsonNode root, String field, Pattern pattern) {
        JsonNode node = root.get(field);
        if (node == null
                || !node.isString()
                || !pattern.matcher(node.asString()).matches()) {
            throw new InvalidWebhookBodyException();
        }
        return node.asString();
    }

    /**
     * One walk over the tree: blanks CPF/CNPJ values and replaces NUL (which jsonb cannot hold) in text values; a
     * property name with NUL makes the body invalid.
     */
    private static void sanitize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                if (name.indexOf(NUL) >= 0) {
                    throw new InvalidWebhookBodyException();
                }
                String key = name.toLowerCase(Locale.ROOT);
                JsonNode value = object.get(name);
                if (key.equals("cpfcnpj") || key.equals("cpf")) {
                    object.put(name, REDACTED);
                } else if (value.isString()) {
                    object.put(name, withoutNul(value.asString()));
                } else {
                    sanitize(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item.isString()) {
                    array.set(i, array.stringNode(withoutNul(item.asString())));
                } else {
                    sanitize(item);
                }
            }
        }
    }

    private static String withoutNul(String text) {
        return text.replace(NUL, REPLACEMENT);
    }
}
