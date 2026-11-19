package com.smartcoffee.modules.payment.application;

public interface ReceiptDocumentRenderer {
    byte[] render(String receiptSnapshot);
}

