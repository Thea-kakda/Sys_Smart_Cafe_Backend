package com.smartcoffee.modules.payment.application;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class SimpleReceiptDocumentRenderer implements ReceiptDocumentRenderer {
    public byte[] render(String snapshot) {
        return ("SMART Coffee & Bakery Receipt\n" + snapshot).getBytes(StandardCharsets.UTF_8);
    }
}

