package com.smartcoffee.infrastructure.observability;
import io.micrometer.core.instrument.MeterRegistry; import org.springframework.stereotype.Service;
@Service public class MetricsService { private final MeterRegistry registry; public MetricsService(MeterRegistry registry){this.registry=registry;} public void increment(String name,String...tags){registry.counter(name,tags).increment();} }

