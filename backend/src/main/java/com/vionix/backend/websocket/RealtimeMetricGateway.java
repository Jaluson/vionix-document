package com.vionix.backend.websocket;

import com.vionix.backend.alert.AlertEvent;
import com.vionix.backend.metrics.MetricPoint;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RealtimeMetricGateway {
    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeMetricGateway(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onMetricPoint(MetricPoint point) {
        MetricMessage message = new MetricMessage(
                "METRIC_POINT",
                point.tenantId(),
                point.deviceId(),
                point.source(),
                point.time().toString(),
                point.metrics()
        );
        messagingTemplate.convertAndSend("/topic/tenant/" + point.tenantId() + "/metrics", message);
        messagingTemplate.convertAndSend(
                "/topic/tenant/" + point.tenantId() + "/device/" + point.deviceId() + "/metrics",
                message
        );
    }

    @EventListener
    public void onAlert(AlertEvent event) {
        AlertMessage message = new AlertMessage(
                "ALERT_FIRING",
                event.tenantId(),
                Map.of(
                        "alertId", event.alertId(),
                        "ruleName", event.ruleName(),
                        "severity", event.severity(),
                        "deviceId", event.deviceId(),
                        "metric", event.metric(),
                        "triggerValue", event.triggerValue(),
                        "firedAt", event.firedAt().toString()
                )
        );
        messagingTemplate.convertAndSend("/topic/tenant/" + event.tenantId() + "/alerts", message);
        messagingTemplate.convertAndSend(
                "/topic/tenant/" + event.tenantId() + "/device/" + event.deviceId() + "/alerts",
                message
        );
    }

    public record MetricMessage(
            String type,
            long tenantId,
            String deviceId,
            String source,
            String time,
            Map<String, Double> metrics
    ) {
    }

    public record AlertMessage(String type, long tenantId, Map<String, Object> data) {
    }
}
