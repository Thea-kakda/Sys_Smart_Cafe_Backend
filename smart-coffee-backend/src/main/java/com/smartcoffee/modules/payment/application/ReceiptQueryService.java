package com.smartcoffee.modules.payment.application;

import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.infrastructure.storage.SignedUrlService;
import com.smartcoffee.modules.payment.api.ReceiptResponse;
import com.smartcoffee.modules.payment.domain.ReceiptStatus;
import com.smartcoffee.modules.payment.persistence.ReceiptRepository;
import com.smartcoffee.common.security.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Read composition for the GET receipt contract (Full-stack wiring §1.3).
 * Dual access path: the owning customer always sees their own receipts; staff
 * holding order:admin-read may re-print for any order inside their store
 * scope. Receipts are returned as-is — the snapshot is the immutable payload
 * captured at settlement time and is never recomputed. An order with no
 * settled payment yields an empty list (the order itself was validated).
 *
 * <p>Statuses are reported honestly: PENDING/PROCESSING receipts have no
 * download URL; ISSUED receipts carry a short-lived signed URL issued by the
 * existing private-object policy, so the internal object key never leaves the
 * backend. A FAILED receipt exposes only its bounded safe reason.
 */
@Service
public class ReceiptQueryService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptQueryService.class);

    private final PaymentOrderPort orders;
    private final ReceiptRepository receipts;
    private final StoreAuthorizationService storeAuth;
    private final SignedUrlService signedUrls;
    private final ObjectMapper json;

    public ReceiptQueryService(PaymentOrderPort o, ReceiptRepository r,
            StoreAuthorizationService s, SignedUrlService signedUrls, ObjectMapper j) {
        orders = o;
        receipts = r;
        storeAuth = s;
        this.signedUrls = signedUrls;
        json = j;
    }

    @Transactional(readOnly = true)
    public List<ReceiptResponse> receipt(CurrentActor actor, UUID orderId) {
        PaymentOrderPort.OrderPaymentView view = resolve(actor, orderId);
        return receipts.findByOrderId(view.orderId()).stream().map(this::response).toList();
    }

    /**
     * Staff-first resolution. Holders of order:admin-read re-print any order
     * inside their store scope; everyone else falls through to the customer
     * ownership path (a non-owner gets 404, not 403, matching the order-detail
     * contract). Checking the admin path before the ownership probe is
     * deliberate: catching loadOwned's exception across its transaction proxy
     * would mark this shared read-only transaction rollback-only and fail at
     * commit, so ownership is only probed when it is genuinely the customer
     * path and any 404 is allowed to propagate cleanly.
     */
    private PaymentOrderPort.OrderPaymentView resolve(CurrentActor actor, UUID orderId) {
        if (actor.hasPermission(PermissionCodes.ORDER_ADMIN_READ)) {
            var view = orders.load(orderId);
            storeAuth.requireStore(actor, view.storeId());
            return view;
        }
        return orders.loadOwned(orderId, actor.userId());
    }

    private ReceiptResponse response(com.smartcoffee.modules.payment.domain.Receipt r) {
        return new ReceiptResponse(r.id(), r.number(), r.paymentId(), r.orderId(), r.storeId(),
                r.status().name(), json.readTree(r.snapshot()), r.issuedAt(), r.createdAt(),
                downloadUrl(r), r.mimeType(), r.sizeBytes(), r.checksum(), r.failureReason(),
                r.attempts(), r.lastAttemptAt());
    }

    private String downloadUrl(com.smartcoffee.modules.payment.domain.Receipt r) {
        if (r.status() != ReceiptStatus.ISSUED || r.objectKey() == null) {
            return null; // PENDING/PROCESSING/FAILED are represented honestly
        }
        try {
            return signedUrls.privateDownload(r.objectKey()).toString();
        } catch (RuntimeException exception) {
            // Storage being briefly unavailable must not break the read; the
            // caller still gets status and metadata without a stale URL.
            log.warn("Could not sign receipt download url receiptId={} error={}", r.id(),
                    exception.getClass().getSimpleName());
            return null;
        }
    }
}
