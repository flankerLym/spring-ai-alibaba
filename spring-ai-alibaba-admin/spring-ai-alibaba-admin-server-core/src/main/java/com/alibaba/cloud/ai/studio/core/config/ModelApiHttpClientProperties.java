package com.alibaba.cloud.ai.studio.core.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.model.http-client")
public class ModelApiHttpClientProperties {

    // Getters and Setters omitted for brevity...
    @NotNull
    private Pool pool = new Pool();
    @NotNull
    private Timeout timeout = new Timeout();
    @NotNull
    private Retry retry = new Retry();

    @Setter
    @Getter
    public static class Pool {
        // Getters and Setters...
        @Min(1) private int maxConnections = 200;
        @NotNull private Duration maxIdleTime = Duration.ofSeconds(20);
        @NotNull private Duration maxLifeTime = Duration.ofSeconds(60);
        @NotNull private Duration evictInterval = Duration.ofSeconds(10);
        @NotNull private Duration acquireTimeout = Duration.ofSeconds(5);

    }

    @Setter
    @Getter
    public static class Timeout {
        // Getters and Setters...
        @NotNull private Duration connect = Duration.ofSeconds(5);
        @NotNull private Duration response = Duration.ofSeconds(120);

    }

    @Setter
    @Getter
    public static class Retry {
        // Getters and Setters...
        @Min(0) private int maxAttempts = 3;
        @NotNull private Duration backoffDelay = Duration.ofMillis(500);
        private double backoffMultiplier = 2.0;

    }
}