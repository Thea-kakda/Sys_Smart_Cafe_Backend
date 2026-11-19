package com.smartcoffee.modules.payment.application;

import tools.jackson.databind.node.ObjectNode;
import com.smartcoffee.common.exception.InfrastructureException;
import com.smartcoffee.modules.payment.domain.PaymentConversion;
import com.smartcoffee.modules.payment.domain.Receipt;
import com.smartcoffee.modules.payment.persistence.ReceiptRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptService {
    private final ReceiptRepository receipts;
    private final tools.jackson.databind.ObjectMapper json;

    public ReceiptService(ReceiptRepository r, tools.jackson.databind.ObjectMapper j) {
        receipts = r;
        json = j;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt request(PaymentOrderPort.OrderPaymentView o, com.smartcoffee.modules.payment.domain.Payment p) {
        try {
            return receipts.findByPaymentId(p.id())
                    .orElseGet(() -> receipts.save(Receipt.pending(p.id(), o.orderId(), o.storeId(),
                            snapshot(o, p).toString())));
        } catch (Exception e) {
            throw new InfrastructureException("RECEIPT_SNAPSHOT_FAILED", "Cannot create receipt snapshot");
        }
    }

    /**
     * Builds the immutable receipt payload (Checkpoint 5D). Beyond the basics it
     * embeds the full currency-conversion trail carried on the payment snapshot,
     * so a printed/stored receipt always reflects the rate, direction, settlement
     * and change that actually applied — never a later rate.
     */
    private ObjectNode snapshot(PaymentOrderPort.OrderPaymentView o, com.smartcoffee.modules.payment.domain.Payment p) {
        ObjectNode s = json.createObjectNode();
        s.put("orderNumber", o.orderNumber());
        s.put("paymentReference", p.reference());
        s.put("orderId", o.orderId().toString());
        s.put("storeId", o.storeId().toString());
        s.put("orderCurrency", p.currency());
        s.put("amount", p.amount().toPlainString());
        s.put("currency", p.currency());
        s.put("subtotal", plain(o.subtotal()));
        s.put("discountAmount", plain(o.discount()));
        s.put("total", plain(p.amount()));
        s.put("taxAmount", plain(o.tax()));
        if (o.promotionId() != null) {
            ObjectNode promotion = s.putObject("promotion");
            promotion.put("promotionId", o.promotionId().toString());
            promotion.put("code", o.promotionCode());
            promotion.put("name", o.promotionName());
            promotion.put("type", o.promotionType());
            promotion.put("value", plain(o.promotionValue()));
            promotion.put("eligibleSubtotal", plain(o.promotionEligibleSubtotal()));
            promotion.put("discountAmount", plain(o.promotionDiscountAmount()));
            promotion.put("currency", p.currency());
            promotion.put("version", o.promotionVersion());
        }
        s.put("paymentStatus", p.status().name());
        s.put("paidAt", p.paidAt() == null ? null : p.paidAt().toString());
        PaymentConversion c = p.conversionSnapshot();
        if (c != null) {
            s.put("tenderCurrency", c.tenderCurrency());
            s.put("tenderAmountReceived", plain(c.tenderAmountReceived()));
            s.put("exchangeRateId", text(c.exchangeRateId()));
            s.put("appliedExchangeRate", plain(c.appliedExchangeRate()));
            s.put("rateBaseCurrency", c.rateBaseCurrency());
            s.put("rateQuoteCurrency", c.rateQuoteCurrency());
            s.put("rateDirection", c.rateBaseCurrency() + "->" + c.rateQuoteCurrency());
            s.put("exchangeRateInverted", c.exchangeRateInverted());
            s.put("settlementAmountReceived", plain(c.settlementAmountReceived()));
            s.put("settlementChangeAmount", plain(c.settlementChangeAmount()));
            s.put("tenderChangeAmount", plain(c.tenderChangeAmount()));
            s.put("changeAmount", plain(c.settlementChangeAmount()));
            s.put("changeCurrency", c.changeCurrency());
            s.put("roundingAmount", plain(c.roundingAmount()));
        } else {
            s.put("changeAmount", plain(p.changeAmount()));
            s.put("changeCurrency", p.currency());
        }
        return s;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static String text(UUID v) {
        return v == null ? null : v.toString();
    }
}
