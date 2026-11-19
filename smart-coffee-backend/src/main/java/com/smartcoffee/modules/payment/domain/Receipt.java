package com.smartcoffee.modules.payment.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "receipts")
public class Receipt {

    @Id
    @Column(name = "receipt_id")
    private UUID id;

    @Column(name = "receipt_number", nullable = false, unique = true)
    private String number;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceiptStatus status;

    // Immutable receipt payload JSON; bound as jsonb (see AuditLog, Checkpoint 2C).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "receipt_snapshot",
            nullable = false,
            columnDefinition = "jsonb"
    )
    private String snapshot;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "mime_type", length = 120)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 128)
    private String checksum;

    @Version
    private long version;

    protected Receipt() {
    }

    public static Receipt pending(
            UUID payment,
            UUID order,
            UUID store,
            String snapshot) {

        if (payment == null) {
            throw new IllegalArgumentException(
                    "Payment ID must not be null");
        }

        if (order == null) {
            throw new IllegalArgumentException(
                    "Order ID must not be null");
        }

        if (store == null) {
            throw new IllegalArgumentException(
                    "Store ID must not be null");
        }

        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalArgumentException(
                    "Receipt snapshot must not be blank");
        }

        var receipt = new Receipt();

        receipt.id = UUID.randomUUID();
        receipt.number = "RCP-" + receipt.id;
        receipt.paymentId = payment;
        receipt.orderId = order;
        receipt.storeId = store;
        receipt.status = ReceiptStatus.PENDING;
        receipt.snapshot = snapshot;
        receipt.createdAt = Instant.now();
        receipt.attempts = 0;

        return receipt;
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID storeId() {
        return storeId;
    }

    public ReceiptStatus status() {
        return status;
    }

    public String snapshot() {
        return snapshot;
    }

    public String objectKey() {
        return objectKey;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    public int attempts() {
        return attempts;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public String failureReason() {
        return failureReason;
    }

    public String mimeType() {
        return mimeType;
    }

    public Long sizeBytes() {
        return sizeBytes;
    }

    public String checksum() {
        return checksum;
    }

    /**
     * Claims the row for one issuance attempt. PENDING, FAILED and a stale
     * PROCESSING claim may all be (re)claimed; an ISSUED receipt is terminal and
     * must be checked by the caller before calling this.
     */
    public void beginAttempt(Instant now) {
        if (status == ReceiptStatus.ISSUED) {
            throw new ConflictException(
                    "RECEIPT_STATE_CONFLICT",
                    "An issued receipt cannot be reprocessed");
        }

        status = ReceiptStatus.PROCESSING;
        attempts++;
        lastAttemptAt = now;
        failureReason = null;
    }

    public void issued(String objectKey, String mimeType, Long sizeBytes, String checksum) {
        if (status == ReceiptStatus.ISSUED
                && Objects.equals(this.objectKey, objectKey)) {
            return;
        }

        if (status != ReceiptStatus.PENDING
                && status != ReceiptStatus.PROCESSING) {
            throw new ConflictException(
                    "RECEIPT_STATE_CONFLICT",
                    "Only a pending or processing receipt can be issued");
        }

        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Receipt object key must not be blank");
        }

        this.objectKey = objectKey;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.status = ReceiptStatus.ISSUED;
        this.issuedAt = Instant.now();
        this.failureReason = null;
    }

    /**
     * Records a bounded, customer-safe failure code (never an exception message,
     * storage path or credential). FAILED stays retryable until the configured
     * attempt ceiling is reached.
     */
    public void failed(String reason) {
        if (status == ReceiptStatus.ISSUED) {
            throw new ConflictException(
                    "RECEIPT_STATE_CONFLICT",
                    "An issued receipt cannot be marked as failed");
        }

        status = ReceiptStatus.FAILED;
        this.failureReason = bounded(reason);
    }

    private static String bounded(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}

