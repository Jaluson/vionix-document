package com.vionix.backend.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vionix.backend.common.config.VionixProperties;
import com.vionix.backend.metrics.MetricEventPublisher;
import com.vionix.backend.metrics.MetricPoint;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MqttMetricConsumer implements ApplicationRunner, MqttCallback {
    private static final Logger log = LoggerFactory.getLogger(MqttMetricConsumer.class);

    private final VionixProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MetricEventPublisher metricEventPublisher;
    private MqttClient client;

    public MqttMetricConsumer(
            VionixProperties properties,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            MetricEventPublisher metricEventPublisher
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.metricEventPublisher = metricEventPublisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getMqtt().isConsumerEnabled()) {
            return;
        }
        try {
            client = new MqttClient(
                    properties.getMqtt().getBroker(),
                    properties.getMqtt().getClientId() + "-" + System.currentTimeMillis(),
                    new MemoryPersistence()
            );
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout((int) properties.getMqtt().getConnectionTimeout().toSeconds());
            client.setCallback(this);
            client.connect(options);
            client.subscribe("vionix/+/+/metrics", 1);
            client.subscribe("sensors/+", 1);
            log.info("MQTT metric consumer subscribed to production and development topics.");
        } catch (Exception exception) {
            log.warn("MQTT metric consumer could not start: {}", exception.getMessage());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            MetricPoint point = parse(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
            if (point != null) {
                metricEventPublisher.publish(point);
            }
        } catch (Exception exception) {
            log.warn("Ignored invalid MQTT metric message on topic {}: {}", topic, exception.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    private MetricPoint parse(String topic, String payload) throws Exception {
        String[] parts = topic.split("/");
        long tenantId;
        String deviceId;
        if (parts.length == 4 && "vionix".equals(parts[0]) && "metrics".equals(parts[3])) {
            tenantId = resolveTenant(parts[1]);
            deviceId = parts[2];
        } else if (parts.length == 2 && "sensors".equals(parts[0])) {
            tenantId = resolveTenant(properties.getMqtt().getDevTenantId());
            deviceId = parts[1];
        } else {
            return null;
        }

        JsonNode root = objectMapper.readTree(payload);
        Instant time = root.hasNonNull("timestamp") ? Instant.parse(root.get("timestamp").asText()) : Instant.now();
        String source = root.hasNonNull("source") ? root.get("source").asText() : "mqtt";
        Map<String, Double> metrics = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if ("timestamp".equals(field.getKey()) || "source".equals(field.getKey())) {
                continue;
            }
            if (field.getValue().isNumber()) {
                metrics.put(field.getKey(), field.getValue().asDouble());
            }
        }
        if (metrics.isEmpty()) {
            return null;
        }
        return new MetricPoint(tenantId, deviceId, source, time, metrics);
    }

    private long resolveTenant(String tenant) {
        try {
            return Long.parseLong(tenant);
        } catch (NumberFormatException ignored) {
            Long id = jdbcTemplate.query("""
                            SELECT id FROM tenant WHERE code = ? LIMIT 1
                            """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    tenant
            );
            return id == null ? 1L : id;
        }
    }
}
