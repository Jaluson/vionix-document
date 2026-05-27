package com.vionix.backend.common.health;

import com.vionix.backend.common.config.VionixProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

@Component("mqtt")
public class MqttHealthIndicator implements HealthIndicator {
    private final VionixProperties properties;

    public MqttHealthIndicator(VionixProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        BrokerEndpoint endpoint = BrokerEndpoint.from(properties.getMqtt().getBroker());
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(endpoint.host(), endpoint.port()),
                    Math.toIntExact(properties.getMqtt().getConnectionTimeout().toMillis())
            );
            return Health.up()
                    .withDetail("broker", endpoint.redacted())
                    .build();
        } catch (IOException | RuntimeException exception) {
            return Health.down(exception)
                    .withDetail("broker", endpoint.redacted())
                    .build();
        }
    }

    private record BrokerEndpoint(String scheme, String host, int port) {
        private static BrokerEndpoint from(String broker) {
            String value = broker.contains("://") ? broker : "tcp://" + broker;
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("MQTT broker host is required");
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 1883;
            return new BrokerEndpoint(uri.getScheme(), host, port);
        }

        private String redacted() {
            return scheme + "://" + host + ":" + port;
        }
    }
}
