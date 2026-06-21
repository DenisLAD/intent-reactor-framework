package com.intentreactor.rag.source;

import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeQuery;
import com.intentreactor.api.rag.KnowledgeSource;
import com.intentreactor.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link KnowledgeSource} that executes a {@code LIKE}-based SQL query against
 * a relational database table.
 *
 * <p>Enabled by setting {@code intent-reactor.rag.jdbc.enabled=true} when a
 * {@code JdbcTemplate} bean is present on the classpath. Configure the target
 * table and columns:
 *
 * <pre>{@code
 * intent-reactor:
 *   rag:
 *     jdbc:
 *       enabled: true
 *       table: knowledge_documents
 *       content-column: content
 *       id-column: id
 *       metadata-columns: [category, author, created_at]
 * }</pre>
 *
 * <h2>Expected table schema</h2>
 * <pre>{@code
 * CREATE TABLE knowledge_documents (
 *     id      VARCHAR(255) PRIMARY KEY,
 *     content TEXT         NOT NULL,
 *     -- any additional metadata columns
 *     category VARCHAR(100),
 *     author   VARCHAR(100)
 * );
 * }</pre>
 *
 * <p>The executed query is:
 * <pre>{@code
 * SELECT {id}, {content}, [{metadataColumns}...]
 * FROM {table}
 * WHERE LOWER({content}) LIKE LOWER(?)
 * LIMIT {maxResults}
 * }</pre>
 *
 * <p><strong>Note:</strong> {@code LIKE}-based search is case-insensitive on most databases
 * but does not support semantic/vector similarity. For full-text or vector search, implement
 * a custom {@link KnowledgeSource} using your database's native capabilities.
 *
 * <p><strong>Field injection:</strong> {@link JdbcTemplate} is injected via {@code @Autowired}
 * field injection following the pattern of {@code JdbcScriptRepository}.
 *
 * @see KnowledgeSource
 * @see RagProperties.Jdbc
 */
public class JdbcKnowledgeSource implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(JdbcKnowledgeSource.class);
    private final RagProperties.Jdbc config;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Creates a new JDBC knowledge source with the given configuration.
     *
     * @param config the JDBC configuration; must not be {@code null}
     */
    public JdbcKnowledgeSource(RagProperties.Jdbc config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "jdbc_knowledge";
    }

    @Override
    public String getDescription() {
        return "Knowledge stored in database table '" + config.getTable() + "'";
    }

    @Override
    public List<KnowledgeDocument> search(KnowledgeQuery query) {
        if (query == null || query.getText() == null || query.getText().isBlank()) {
            return List.of();
        }
        int limit = query.getMaxResults() > 0 ? query.getMaxResults() : 5;

        String sql = buildSql(limit);
        String param = "%" + query.getText() + "%";

        try {
            return jdbcTemplate.query(sql, rs -> {
                List<KnowledgeDocument> docs = new ArrayList<>();
                while (rs.next() && docs.size() < limit) {
                    String id = rs.getString(config.getIdColumn());
                    String content = rs.getString(config.getContentColumn());

                    Map<String, Object> metadata = new HashMap<>();
                    for (String col : config.getMetadataColumns()) {
                        Object val = rs.getObject(col);
                        if (val != null) metadata.put(col, val);
                    }

                    docs.add(KnowledgeDocument.builder()
                            .id(id)
                            .content(content)
                            .sourceName(getName())
                            .score(-1.0)
                            .metadata(metadata)
                            .build());
                }
                return docs;
            }, param);
        } catch (Exception e) {
            log.warn("JdbcKnowledgeSource: query failed for table '{}': {}", config.getTable(), e.getMessage());
            return List.of();
        }
    }

    private String buildSql(int limit) {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(config.getIdColumn()).append(", ").append(config.getContentColumn());
        for (String col : config.getMetadataColumns()) {
            sb.append(", ").append(col);
        }
        sb.append(" FROM ").append(config.getTable());
        sb.append(" WHERE LOWER(").append(config.getContentColumn()).append(") LIKE LOWER(?)");
        sb.append(" LIMIT ").append(limit);
        return sb.toString();
    }
}
