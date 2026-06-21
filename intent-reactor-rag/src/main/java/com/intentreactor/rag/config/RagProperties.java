package com.intentreactor.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the IntentReactor RAG module.
 *
 * <p>All properties are under the {@code intent-reactor.rag} prefix.
 *
 * <pre>{@code
 * intent-reactor:
 *   rag:
 *     enabled: true
 *     max-results: 5
 *     filesystem:
 *       enabled: false
 *       path: ./knowledge
 *       glob: "**\/*.{txt,md}"
 *       max-file-size-kb: 100
 *     jdbc:
 *       enabled: false
 *       table: knowledge_documents
 *       content-column: content
 *       id-column: id
 *       metadata-columns: []
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "intent-reactor.rag")
public class RagProperties {

    /**
     * Whether the RAG module is enabled. Default: {@code true}.
     */
    private boolean enabled = true;

    /**
     * Default maximum number of documents returned by {@code knowledge_search}. Default: {@code 5}.
     */
    private int maxResults = 5;

    private Filesystem filesystem = new Filesystem();
    private Jdbc jdbc = new Jdbc();

    @Getter
    @Setter
    public static class Filesystem {

        /**
         * Whether the filesystem knowledge source is enabled. Default: {@code false}.
         */
        private boolean enabled = false;

        /**
         * Root directory to scan for knowledge files. Default: {@code ./knowledge}.
         */
        private String path = "./knowledge";

        /**
         * Glob pattern for files to include. Default: {@code **\/*.{txt,md}}.
         */
        private String glob = "**/*.{txt,md}";

        /**
         * Maximum file size in kilobytes; larger files are skipped. {@code 0} = no limit. Default: {@code 100}.
         */
        private int maxFileSizeKb = 100;
    }

    @Getter
    @Setter
    public static class Jdbc {

        /**
         * Whether the JDBC knowledge source is enabled. Default: {@code false}.
         */
        private boolean enabled = false;

        /**
         * Database table that contains knowledge documents. Default: {@code knowledge_documents}.
         */
        private String table = "knowledge_documents";

        /**
         * Column containing the document text. Default: {@code content}.
         */
        private String contentColumn = "content";

        /**
         * Column containing the document identifier. Default: {@code id}.
         */
        private String idColumn = "id";

        /**
         * Additional columns to include as document metadata. Default: empty.
         */
        private List<String> metadataColumns = new ArrayList<>();
    }
}
