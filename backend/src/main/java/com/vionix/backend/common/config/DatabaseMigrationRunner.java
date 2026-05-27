package com.vionix.backend.common.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Arrays;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseMigrationRunner implements ApplicationRunner {
    private final DataSource dataSource;
    private final boolean enabled;
    private final String locations;

    public DatabaseMigrationRunner(
            DataSource dataSource,
            @Value("${spring.flyway.enabled:false}") boolean enabled,
            @Value("${spring.flyway.locations:filesystem:../database/migrations,filesystem:database/migrations}") String locations
    ) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.locations = locations;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        String[] configuredLocations = Arrays.stream(locations.split(","))
                .map(String::trim)
                .filter(location -> !location.isBlank())
                .toArray(String[]::new);
        Flyway.configure()
                .dataSource(dataSource)
                .locations(configuredLocations)
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }
}
