package com.vionix.backend.common.health;

import com.vionix.backend.common.config.VionixProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component("influxdb")
public class InfluxDbHealthIndicator implements HealthIndicator {
    private final VionixProperties properties;
    private final HttpClient httpClient;

    public InfluxDbHealthIndicator(VionixProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getInfluxdb().getHealthTimeout())
                .build();
    }

    @Override
    public Health health() {
        URI healthUri = healthUri();
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(properties.getInfluxdb().getHealthTimeout())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Health.up()
                        .withDetail("url", healthUri.toString())
                        .withDetail("org", properties.getInfluxdb().getOrg())
                        .withDetail("bucket", properties.getInfluxdb().getBucket())
                        .build();
            }
            return Health.down()
                    .withDetail("url", healthUri.toString())
                    .withDetail("status", response.statusCode())
                    .build();
        } catch (IOException exception) {
            return Health.down(exception)
                    .withDetail("url", healthUri.toString())
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down(exception)
                    .withDetail("url", healthUri.toString())
                    .build();
        }
    }

    private URI healthUri() {
        String base = properties.getInfluxdb().getUrl().toString();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalizedBase + "/health");
    }
}
