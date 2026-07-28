package com.example.regression.config;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    private final DataSource dataSource;
    private Flyway flyway;

    public FlywayConfig(DataSource dataSource) {
        this.dataSource = dataSource;
        this.flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }

    @PostConstruct
    public void migrate() {
        flyway.migrate();
    }

    @Bean
    public Flyway flyway() {
        return flyway;
    }
}
