package com.intentreactor.rag.config;

import com.intentreactor.api.rag.KnowledgeSource;
import com.intentreactor.core.config.IntentReactorAutoConfiguration;
import com.intentreactor.rag.source.FileSystemKnowledgeSource;
import com.intentreactor.rag.source.JdbcKnowledgeSource;
import com.intentreactor.rag.tool.KnowledgeSearchTool;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;

/**
 * Auto-configuration for the IntentReactor RAG module.
 *
 * <p>Registers {@link KnowledgeSearchTool} as a Spring bean when at least one
 * {@link KnowledgeSource} bean is present in the application context.
 *
 * <p>Optionally registers built-in knowledge source backends based on properties:
 * <ul>
 *   <li>{@code intent-reactor.rag.filesystem.enabled=true} → {@link FileSystemKnowledgeSource}</li>
 *   <li>{@code intent-reactor.rag.jdbc.enabled=true} + {@code JdbcTemplate} on classpath
 *       → {@link JdbcKnowledgeSource}</li>
 * </ul>
 *
 * <p>{@link com.intentreactor.rag.source.InMemoryKnowledgeSource} is not registered
 * automatically — declare it as an explicit {@code @Bean} and populate it with documents.
 */
@AutoConfiguration
@AutoConfigureAfter(IntentReactorAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "intent-reactor.rag",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(RagProperties.class)
public class RagAutoConfiguration {

    @Bean
    @ConditionalOnBean(KnowledgeSource.class)
    public KnowledgeSearchTool knowledgeSearchTool(List<KnowledgeSource> sources) {
        return new KnowledgeSearchTool(sources);
    }

    @Bean("ragFileSystemSource")
    @ConditionalOnProperty(
            prefix = "intent-reactor.rag.filesystem",
            name = "enabled",
            havingValue = "true"
    )
    public FileSystemKnowledgeSource fileSystemKnowledgeSource(RagProperties props) {
        RagProperties.Filesystem cfg = props.getFilesystem();
        return new FileSystemKnowledgeSource(
                "filesystem",
                "Text and Markdown files in '" + cfg.getPath() + "'",
                Path.of(cfg.getPath()),
                cfg.getGlob(),
                cfg.getMaxFileSizeKb()
        );
    }

    @Bean("ragJdbcSource")
    @ConditionalOnProperty(
            prefix = "intent-reactor.rag.jdbc",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnBean(type = "org.springframework.jdbc.core.JdbcTemplate")
    public JdbcKnowledgeSource jdbcKnowledgeSource(RagProperties props) {
        return new JdbcKnowledgeSource(props.getJdbc());
    }
}
