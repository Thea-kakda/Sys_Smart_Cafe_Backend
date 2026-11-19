package com.smartcoffee.modules.payment.application;

import tools.jackson.databind.ObjectMapper;
import com.smartcoffee.common.exception.*;
import com.smartcoffee.infrastructure.idempotency.*;
import com.smartcoffee.infrastructure.messaging.*;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.infrastructure.payment.PaymentGateway;

import com.smartcoffee.modules.payment.api.*;
import com.smartcoffee.modules.payment.domain.Currency;
import com.smartcoffee.modules.payment.domain.*;
import com.smartcoffee.modules.payment.persistence.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentApplicationService {
    /** Relative tolerance for the deprecated client-supplied expected rate. */
    private static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.001");

    private final PaymentRepository payments;
    private final PaymentMethodRepository methods;
    private final PaymentOrderPort orders;
    private final List<PaymentGateway> gateways;
    private final PaymentMapper mapper;
    private final ReceiptService receipts;
    private final IdempotencyService idem;
    private final CanonicalRequestHasher hasher;
    private final OutboxEventWriter outbox;
    private final ObjectMapper json;
    private final CurrencyConversionService conversions;

    public PaymentApplicationService(PaymentRepository p, PaymentMethodRepository m, PaymentOrderPort o,
            List<PaymentGateway> g, PaymentMapper map, ReceiptService r, IdempotencyService i, CanonicalRequestHasher h,
            OutboxEventWriter out, ObjectMapper j, CurrencyConversionService conversions) {
        payments = p;
        methods = m;
        orders = o;
        gateways = g;
        mapper = map;
        receipts = r;
        idem = i;
        hasher = h;
        outbox = out;
        json = j;
        this.conversions = conversions;
    }

    @Transactional
    public PaymentResponse cash(UUID user, UUID key, CashPaymentRequest req) {
        var claim = idem.claim("PAYMENT_CASH", "USER", user, key, hasher.hash(req), Duration.ofDays(30));
        if (claim.status() == IdempotencyStatus.COMPLETED)
            return replay(claim);
        var order = orders.loadOwned(req.orderId(), user);
        if (!order.storeId().equals(req.storeId()))
            throw new ConflictException("STORE_MISMATCH", "Order belongs to another store");
        if (payments.existsByOrderIdAndStatus(order.orderId(), PaymentStatus.PAID))
            throw new ConflictException("ORDER_ALREADY_PAID", "Order already paid");
        // Checkpoint 5C: the settlement rate is chosen AUTHORITATIVELY by the
        // backend conversion engine from the active rate table. The client-supplied
        // CashPaymentRequest.exchangeRate is deprecated and can never control the
        // calculation; it is only compared against the backend rate to detect a
        // stale preview (RATE_STALE). This closes the P0 defect where a fabricated
        // rate let a cashier settle an order with an insufficient tender.
        var conversion = conversions.convert(order.storeId(), req.tenderCurrency(), order.currency(),
                req.amountReceived(), Instant.now());
        BigDecimal settlement = conversion.targetAmount();
        rejectStaleExpectedRate(req.exchangeRate(), req.amountReceived(), settlement);
        if (settlement.compareTo(order.total()) < 0)
            throw new ConflictException("INSUFFICIENT_CASH_RECEIVED", "Cash received is insufficient");
        // Change rule (Checkpoint 5C/5D): the order is settled in its own currency,
        // but physical change is handed back in the currency the customer tendered.
        Currency tender = Currency.parse(req.tenderCurrency());
        Currency settle = Currency.parse(order.currency());
        BigDecimal settlementChange = settlement.subtract(order.total()).setScale(settle.minorUnits(), RoundingMode.HALF_UP);
        BigDecimal tenderChange = conversion.inversionUsed()
                ? settlementChange.multiply(conversion.appliedRate()).setScale(tender.minorUnits(), RoundingMode.HALF_UP)
                : settlementChange.divide(conversion.appliedRate(), tender.minorUnits(), RoundingMode.HALF_UP);
        var method = method("CASH");
        var p = payments.save(Payment.pending(order.orderId(), order.storeId(), method.id(), order.currency(),
                order.total(), Instant.now().plusSeconds(60)));
        p.paid(settlement, settlementChange);
        // Persist the immutable conversion trail so the transaction can be
        // reconstructed even after the rate table changes (Checkpoint 5D).
        p.applyConversion(new PaymentConversion(req.tenderCurrency(), req.amountReceived(), conversion.exchangeRateId(),
                conversion.appliedRate(), conversion.baseCurrency().name(), conversion.quoteCurrency().name(),
                conversion.inversionUsed(), settlement, settlementChange, tenderChange, req.tenderCurrency(),
                conversion.roundingAmount()));
        orders.confirm(order.orderId(), user);
        var receipt = receipts.request(order, p);
        events(order, p, receipt.id());
        return complete(claim, mapper.response(p, method.code(), null, false));
    }

    @Transactional
    public PaymentResponse createKhqr(UUID user, UUID key, KhqrPaymentRequest req) {
        var claim = idem.claim("PAYMENT_KHQR_CREATE", "USER", user, key, hasher.hash(req), Duration.ofDays(7));
        if (claim.status() == IdempotencyStatus.COMPLETED)
            return replay(claim);
        var order = orders.loadOwned(req.orderId(), user);
        if (payments.existsByOrderIdAndStatus(order.orderId(), PaymentStatus.PAID))
            throw new ConflictException("ORDER_ALREADY_PAID", "Order already paid");
        var method = method("KHQR");
        var p = payments.save(Payment.pending(order.orderId(), order.storeId(), method.id(), order.currency(),
                order.total(), Instant.now().plus(15, ChronoUnit.MINUTES)));
        PaymentGateway gateway = gateway("KHQR");
        PaymentGateway.PaymentInitiation init = gateway.initiate(p.id(), p.amount(), p.currency());
        p.processing(gateway.code(), init.providerReference());
        return complete(claim, mapper.response(p, method.code(), init.qrPayload(), false));
    }

    @Transactional
    public PaymentResponse verifyKhqr(UUID user, UUID paymentId) {
        var p = payments.lock(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        var order = orders.loadOwned(p.orderId(), user);
        if (p.status() == PaymentStatus.PAID)
            return mapper.response(p, "KHQR", null, false);
        var result = gateway("KHQR").verify(p.providerReference());
        if (!result.paid()) {
            p.failed("PROVIDER_REJECTED");
            failureEvent(order, p);
            return mapper.response(p, "KHQR", null, false);
        }
        if (payments.existsByOrderIdAndStatus(p.orderId(), PaymentStatus.PAID))
            throw new ConflictException("ORDER_ALREADY_PAID", "Order already paid");
        p.paid(p.amount(), BigDecimal.ZERO);
        orders.confirm(p.orderId(), user);
        var receipt = receipts.request(order, p);
        events(order, p, receipt.id());
        return mapper.response(p, "KHQR", null, false);
    }

    /**
     * Deprecated-compatibility guard (Checkpoint 5C). If a client still sends
     * {@code exchangeRate}, it is treated purely as an expected preview value and
     * compared to the effective multiplier the backend actually applied
     * (settlement / received). A deviation beyond {@link #RATE_TOLERANCE}
     * signals a stale or manipulated preview and is rejected with RATE_STALE;
     * the settlement itself always uses the backend rate, never this value.
     */
    private void rejectStaleExpectedRate(BigDecimal expected, BigDecimal received, BigDecimal settlement) {
        if (expected == null || expected.signum() <= 0 || received.signum() <= 0) {
            return;
        }
        BigDecimal applied = settlement.divide(received, 10, RoundingMode.HALF_UP);
        BigDecimal diff = applied.subtract(expected).abs();
        if (diff.compareTo(applied.multiply(RATE_TOLERANCE)) > 0) {
            throw new ConflictException("RATE_STALE",
                    "Expected rate " + expected + " differs from authoritative rate " + applied + "; refresh and retry");
        }
    }

    private PaymentMethod method(String code) {
        return methods.findByCodeAndActiveTrue(code).orElseThrow(
                () -> new InfrastructureException("PAYMENT_METHOD_UNAVAILABLE", "Payment method unavailable"));
    }

    private PaymentGateway gateway(String code) {
        return gateways.stream().filter(g -> g.code().equalsIgnoreCase(code)).findFirst().orElseThrow(
                () -> new InfrastructureException("PAYMENT_GATEWAY_UNAVAILABLE", "Payment gateway unavailable"));
    }

    private void events(PaymentOrderPort.OrderPaymentView o, Payment p, UUID receiptId) {
        var payload = json.createObjectNode().put("paymentId", p.id().toString()).put("orderId", p.orderId().toString())
                .put("storeId", p.storeId().toString())
                // The receipt identity is part of the durable request so the
                // consumer never has to infer which row to issue.
                .put("receiptId", receiptId.toString())
                .put("amount", p.amount().toPlainString())
                .put("currency", p.currency());
        outbox.append(new EventEnvelope(UUID.randomUUID(), EventTypes.PAYMENT_COMPLETED, 1, "PAYMENT", p.id(),
                p.storeId(), Instant.now(), null, null, payload), "payment.completed.v1");
        outbox.append(new EventEnvelope(UUID.randomUUID(), "ReceiptRequested", 1, "PAYMENT", p.id(), p.storeId(),
                Instant.now(), null, null, payload), "receipt.requested.v1");
    }

    private void failureEvent(PaymentOrderPort.OrderPaymentView o, Payment p) {
        var payload = json.createObjectNode().put("paymentId", p.id().toString()).put("orderId", p.orderId().toString())
                .put("failureCode", p.failureCode());
        outbox.append(new EventEnvelope(UUID.randomUUID(), "PaymentFailed", 1, "PAYMENT", p.id(), p.storeId(),
                Instant.now(), null, null, payload), "payment.failed.v1");
    }

    private PaymentResponse complete(ApiIdempotencyRecord c, PaymentResponse r) {
        try {
            c.complete(200, json.writeValueAsString(r));
            return r;
        } catch (Exception e) {
            throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot store response");
        }
    }

    private PaymentResponse replay(ApiIdempotencyRecord c) {
        try {
            return json.readValue(c.responseBody(), PaymentResponse.class);
        } catch (Exception e) {
            throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot replay response");
        }
    }
}

