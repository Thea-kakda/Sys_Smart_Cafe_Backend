package com.smartcoffee.modules.ordering.application;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class OrderNumberGenerator {
    public String next(String storeCode) {
        return storeCode + "-" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC)) + "-"
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}

