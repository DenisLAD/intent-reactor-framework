package com.intentreactor.core.config;

import com.intentreactor.core.event.IntentReactorEventLogger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IntentReactorPropertiesTest {

    @Test
    void loggingConfig_defaultsToEnabled() {
        IntentReactorProperties props = new IntentReactorProperties();
        assertThat(props.getLogging().isEnabled()).isTrue();
    }

    @Test
    void confirmationTimeout_defaultsToThirtyMinutes() {
        IntentReactorProperties props = new IntentReactorProperties();
        assertThat(props.getPlanning().getConfirmationTimeout()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void parallelTimeout_defaultsToSixtySeconds() {
        IntentReactorProperties props = new IntentReactorProperties();
        assertThat(props.getPlanning().getParallelTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void eventLogger_registeredByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(LoggingAutoConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(IntentReactorEventLogger.class));
    }

    @Test
    void eventLogger_notRegisteredWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(LoggingAutoConfig.class)
                .withPropertyValues("intent-reactor.logging.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IntentReactorEventLogger.class));
    }

    /**
     * Minimal config that mirrors the relevant part of IntentReactorAutoConfiguration.
     */
    @Configuration
    static class LoggingAutoConfig {
        @Bean
        @ConditionalOnMissingBean(IntentReactorEventLogger.class)
        @ConditionalOnProperty(prefix = "intent-reactor.logging", name = "enabled",
                havingValue = "true", matchIfMissing = true)
        IntentReactorEventLogger intentReactorEventLogger() {
            return new IntentReactorEventLogger();
        }
    }
}
