package com.vionix.backend.metrics;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class MetricEventPublisher {
    private final ApplicationEventPublisher publisher;

    public MetricEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(MetricPoint point) {
        publisher.publishEvent(point);
    }
}
