package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.GoodsReceipt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    @Query("select g from GoodsReceipt g left join fetch g.lines where g.purchaseOrderId = :po order by g.receivedAt")
    List<GoodsReceipt> findByPurchaseOrder(@Param("po") UUID purchaseOrderId);
}
