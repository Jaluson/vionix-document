package com.vionix.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "vionix")
public class VionixProperties {
    private final Influxdb influxdb = new Influxdb();
    private final Mqtt mqtt = new Mqtt();
    private final Security security = new Security();

    public Influxdb getInfluxdb() {
        return influxdb;
    }

    public Mqtt getMqtt() {
        return mqtt;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Influxdb {
        private URI url = URI.create("http://localhost:8086");
        private String org = "vionix";
        private String bucket = "device_raw";
        private String token = "";
        private Duration healthTimeout = Duration.ofSeconds(2);

        public URI getUrl() {
            return url;
        }

        public void setUrl(URI url) {
            this.url = url;
        }

        public String getOrg() {
            return org;
        }

        public void setOrg(String org) {
            this.org = org;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public Duration getHealthTimeout() {
            return healthTimeout;
        }

        public void setHealthTimeout(Duration healthTimeout) {
            this.healthTimeout = healthTimeout;
        }
    }

    public static class Mqtt {
        private String broker = "tcp://localhost:1883";
        private Duration connectionTimeout = Duration.ofSeconds(2);
        private boolean consumerEnabled;
        private String clientId = "vionix-backend";
        private String devTenantId = "dev";

        public String getBroker() {
            return broker;
        }

        public void setBroker(String broker) {
            this.broker = broker;
        }

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public boolean isConsumerEnabled() {
            return consumerEnabled;
        }

        public void setConsumerEnabled(boolean consumerEnabled) {
            this.consumerEnabled = consumerEnabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getDevTenantId() {
            return devTenantId;
        }

        public void setDevTenantId(String devTenantId) {
            this.devTenantId = devTenantId;
        }
    }

    public static class Security {
        private String jwtSecret = "";
        private String tokenHashSalt = "";
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        private Duration refreshTokenTtl = Duration.ofDays(7);
        private String bootstrapAdminPassword = "";

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public String getTokenHashSalt() {
            return tokenHashSalt;
        }

        public void setTokenHashSalt(String tokenHashSalt) {
            this.tokenHashSalt = tokenHashSalt;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public String getBootstrapAdminPassword() {
            return bootstrapAdminPassword;
        }

        public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
            this.bootstrapAdminPassword = bootstrapAdminPassword;
        }
    }
}
