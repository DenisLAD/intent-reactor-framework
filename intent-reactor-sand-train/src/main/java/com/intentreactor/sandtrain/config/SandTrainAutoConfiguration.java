package com.intentreactor.sandtrain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentreactor.api.SessionStore;
import com.intentreactor.sandtrain.SandDataCollector;
import com.intentreactor.sandtrain.SandDatasetExporter;
import com.intentreactor.sandtrain.SandTrainController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for SAND training data collection.
 * Activates automatically when intent-reactor-sand-train is on the classpath and
 * strategy=sand is configured.
 */
@AutoConfiguration
@ConditionalOnClass(SessionStore.class)
@ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "sand")
public class SandTrainAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SandDataCollector sandDataCollector(SessionStore sessionStore) {
        return new SandDataCollector(sessionStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public SandDatasetExporter sandDatasetExporter(ObjectMapper objectMapper) {
        return new SandDatasetExporter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    public SandTrainController sandTrainController(SandDataCollector collector,
                                                    SandDatasetExporter exporter) {
        return new SandTrainController(collector, exporter);
    }
}
