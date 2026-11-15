package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.product.CatalogProduct;
import br.com.sergio.sapiensia.product.CatalogService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates and reads Orders (FR-019). The price always comes from the Catalog (SEC-012), the CPF lives only in the
 * request (SEC-033) and the Pix code is read from the provider on every view and never stored (ADR-0003).
 */
@Service
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    /** The Order the request ended with; {@code isNew} is false when a pending one was reused. */
    record Created(UUID orderId, boolean isNew) {}

    private final Orders orders;
    private final Accesses accesses;
    private final PaymentCustomers paymentCustomers;
    private final PixGateway gateway;
    private final CatalogService catalog;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final OrderExpiration.Expirer expirer;
    private final Duration pixTtl;

    OrderService(
            Orders orders,
            Accesses accesses,
            PaymentCustomers paymentCustomers,
            PixGateway gateway,
            CatalogService catalog,
            TransactionTemplate transaction,
            Clock clock,
            OrderExpiration.Expirer expirer,
            @Value("${app.orders.pix-ttl}") Duration pixTtl) {
        this.orders = orders;
        this.accesses = accesses;
        this.paymentCustomers = paymentCustomers;
        this.gateway = gateway;
        this.catalog = catalog;
        this.transaction = transaction;
        this.clock = clock;
        this.expirer = expirer;
        this.pixTtl = pixTtl;
    }

    /** Spring builds the template from the transaction manager; tests pass their own. */
    @Autowired
    OrderService(
            Orders orders,
            Accesses accesses,
            PaymentCustomers paymentCustomers,
            PixGateway gateway,
            CatalogService catalog,
            PlatformTransactionManager transactionManager,
            Clock clock,
            OrderExpiration.Expirer expirer,
            @Value("${app.orders.pix-ttl}") Duration pixTtl) {
        this(
                orders,
                accesses,
                paymentCustomers,
                gateway,
                catalog,
                new TransactionTemplate(transactionManager),
                clock,
                expirer,
                pixTtl);
    }

    Created create(UUID customerId, String customerEmail, CreateOrderRequest request) {
        AtomicReference<String> chargeId = new AtomicReference<>();
        try {
            Created result =
                    transaction.execute(status -> createInTransaction(customerId, customerEmail, request, chargeId));
            // after the commit, ids only: never the CPF, the e-mail, the price or the Pix code
            if (result.isNew()) {
                log.info("Order created: orderId={} chargeId={}", result.orderId(), chargeId.get());
            } else {
                log.info("Pending order reused: orderId={}", result.orderId());
            }
            return result;
        } catch (RuntimeException e) {
            // A2: the charge exists at the provider but no Order does. Outside the transaction, best effort; the
            // original error goes to the Customer unchanged.
            String created = chargeId.get();
            if (created != null) {
                cancelQuietly(created);
            }
            throw e;
        }
    }

    private Created createInTransaction(
            UUID customerId, String customerEmail, CreateOrderRequest request, AtomicReference<String> chargeId) {
        if (!orders.tryLockCustomer(customerId)) {
            throw new OrderInProgressException();
        }
        CatalogProduct product = catalog.get(request.productId());
        if (accesses.exists(customerId, product.id())) {
            throw new ProductAlreadyOwnedException();
        }
        var pending = orders.findPending(customerId, product.id());
        if (pending.isPresent()) {
            Orders.Row row = pending.get();
            if (row.expiresAt().isAfter(clock.instant())) {
                return new Created(row.id(), false);
            }
            // Same transaction and connection as the insert below, so the unique index sees the Order as expired.
            if (!expirer.expire(row)) {
                throw new PaymentProviderUnavailableException();
            }
        }
        String customerCode = customerCode(customerId, customerEmail, request.cpf());
        UUID orderId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(pixTtl);
        String paymentId = call(() -> gateway.createCharge(
                customerCode,
                orderId,
                product.priceCents(),
                LocalDate.ofInstant(expiresAt, BRAZIL),
                "SapiensIA: " + product.title()));
        chargeId.set(paymentId);
        try {
            orders.insert(orderId, customerId, product.id(), product.priceCents(), paymentId, createdAt, expiresAt);
        } catch (DuplicateKeyException e) {
            // the unique index let only one pending Order of this Customer and Product through
            throw new OrderInProgressException();
        }
        return new Created(orderId, true);
    }

    private String customerCode(UUID customerId, String customerEmail, String rawCpf) {
        var saved = paymentCustomers.find(customerId);
        if (saved.isPresent()) {
            return saved.get();
        }
        var known = call(() -> gateway.findCustomerCode(customerId));
        if (known.isPresent()) {
            paymentCustomers.save(customerId, known.get());
            return known.get();
        }
        if (rawCpf == null || rawCpf.isBlank()) {
            throw new CpfRequiredException();
        }
        Cpf cpf = Cpf.parse(rawCpf).orElseThrow(CpfInvalidException::new);
        String code;
        try {
            code = call(() -> gateway.createCustomer(customerId, customerEmail, cpf.digits()));
        } catch (ProviderRefusedDocumentException e) {
            throw new CpfInvalidException();
        }
        paymentCustomers.save(customerId, code);
        return code;
    }

    /** Runs a provider call and turns its failure into the one answer the Customer gets. */
    private <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (PixGatewayException e) {
            log.warn("Pix call failed: step={} kind={}", e.step(), e.kind());
            if (e.kind() == PixGatewayException.Kind.DOCUMENT_REJECTED) {
                throw new ProviderRefusedDocumentException();
            }
            throw new PaymentProviderUnavailableException();
        }
    }

    /** Internal marker of a document the provider refused; becomes {@link CpfInvalidException}. */
    private static final class ProviderRefusedDocumentException extends RuntimeException {
        ProviderRefusedDocumentException() {
            super(null, null, false, false);
        }
    }

    private void cancelQuietly(String chargeId) {
        try {
            gateway.cancelCharge(chargeId);
        } catch (RuntimeException cancelFailure) {
            log.warn("Pix charge left without an Order: chargeId={}", chargeId);
        }
    }

    /** A pending Order past its time is shown as expired even before the expiration task runs. */
    private static OrderStatus shownStatus(Orders.Row row, Instant now) {
        if (row.status() == OrderStatus.PENDING && !row.expiresAt().isAfter(now)) {
            return OrderStatus.EXPIRED;
        }
        return row.status();
    }

    /** The state of the Order, for the page's polling. Only the database is read, never the provider. */
    OrderStatus status(UUID customerId, UUID orderId) {
        Orders.Row row = orders.findForCustomer(orderId, customerId).orElseThrow(OrderNotFoundException::new);
        return shownStatus(row, clock.instant());
    }

    /** The Customer's Orders, newest first. Only the database is read: no provider call, nothing is changed. */
    OrderListResponse list(UUID customerId) {
        Instant now = clock.instant();
        return new OrderListResponse(orders.findAllForCustomer(customerId).stream()
                .map(row -> new OrderSummary(
                        row.id(),
                        row.productTitle(),
                        row.priceCents(),
                        shownStatus(row, now),
                        row.createdAt(),
                        row.expiresAt()))
                .toList());
    }

    /** Reads the Order of the Customer. No transaction is open while the provider is called. */
    OrderResponse get(UUID customerId, UUID orderId) {
        Orders.Row row = orders.findForCustomer(orderId, customerId).orElseThrow(OrderNotFoundException::new);
        Instant now = clock.instant();
        OrderStatus shown = shownStatus(row, now);
        if (shown != OrderStatus.PENDING) {
            // PAID or EXPIRED: nothing to pay, so the provider is not called and there is no Pix code
            return new OrderResponse(
                    row.id(), row.productId(), row.productTitle(), row.priceCents(), shown, row.expiresAt(), 0, null);
        }
        String code;
        try {
            code = gateway.readCopyPaste(row.providerPaymentId());
        } catch (PixGatewayException e) {
            log.warn("Pix call failed: step={} kind={} orderId={}", e.step(), e.kind(), row.id());
            throw new PaymentProviderUnavailableException();
        }
        return new OrderResponse(
                row.id(),
                row.productId(),
                row.productTitle(),
                row.priceCents(),
                OrderStatus.PENDING,
                row.expiresAt(),
                Duration.between(now, row.expiresAt()).toSeconds(),
                code);
    }
}
