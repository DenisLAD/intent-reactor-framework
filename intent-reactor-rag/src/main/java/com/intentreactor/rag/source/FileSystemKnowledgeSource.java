package com.intentreactor.rag.source;

import com.intentreactor.api.rag.KnowledgeDocument;
import com.intentreactor.api.rag.KnowledgeQuery;
import com.intentreactor.api.rag.KnowledgeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@link KnowledgeSource} that searches text files on the local filesystem.
 *
 * <p>On each {@link #search(KnowledgeQuery)} call the source walks the configured
 * {@code rootPath} recursively, reads files matching the {@code glob} pattern
 * (up to {@code maxFileSizeKb} each), and returns lines that contain the query
 * keyword together with up to 3 surrounding context lines.
 *
 * <p>Enabled via properties by {@code RagAutoConfiguration}:
 * <pre>{@code
 * intent-reactor:
 *   rag:
 *     filesystem:
 *       enabled: true
 *       path: ./knowledge
 *       glob: "**\/*.{txt,md}"
 *       max-file-size-kb: 100
 * }</pre>
 *
 * <p>Or declared as an explicit {@code @Bean}:
 * <pre>{@code
 * @Bean
 * public FileSystemKnowledgeSource docsSource() {
 *     return new FileSystemKnowledgeSource(
 *         "docs", "Internal documentation", Path.of("./docs"), "**\/*.md", 200);
 * }
 * }</pre>
 *
 * <p><strong>Binary files</strong> and files that cannot be decoded as UTF-8 are
 * silently skipped. Read errors for individual files are logged at {@code WARN} level
 * and do not abort the overall search.
 *
 * @see KnowledgeSource
 */
public class FileSystemKnowledgeSource implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(FileSystemKnowledgeSource.class);
    private static final int CONTEXT_LINES = 3;

    private final String name;
    private final String description;
    private final Path rootPath;
    private final String glob;
    private final long maxFileSizeBytes;

    /**
     * Creates a new filesystem knowledge source.
     *
     * @param name          unique snake_case identifier; never {@code null} or blank
     * @param description   short description of the knowledge; never {@code null}
     * @param rootPath      root directory to walk; must exist and be a directory
     * @param glob          glob pattern relative to rootPath (e.g., {@code "**\/*.{txt,md}"});
     *                      never {@code null}
     * @param maxFileSizeKb maximum file size in kilobytes; files larger than this are skipped;
     *                      use {@code 0} for no limit
     */
    public FileSystemKnowledgeSource(String name, String description,
                                     Path rootPath, String glob, int maxFileSizeKb) {
        this.name = name;
        this.description = description;
        this.rootPath = rootPath;
        this.glob = glob;
        this.maxFileSizeBytes = maxFileSizeKb > 0 ? (long) maxFileSizeKb * 1024 : Long.MAX_VALUE;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public List<KnowledgeDocument> search(KnowledgeQuery query) {
        if (query == null || query.getText() == null || query.getText().isBlank()) {
            return List.of();
        }
        if (!Files.isDirectory(rootPath)) {
            log.warn("FileSystemKnowledgeSource '{}': root path '{}' is not a directory", name, rootPath);
            return List.of();
        }

        String needle = query.getText().toLowerCase(Locale.ROOT);
        int limit = query.getMaxResults() > 0 ? query.getMaxResults() : 5;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<KnowledgeDocument> results = new ArrayList<>();
        try {
            Files.walk(rootPath)
                    .filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p.getFileName()) || matcher.matches(p))
                    .filter(p -> {
                        try {
                            return Files.size(p) <= maxFileSizeBytes;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(filePath -> {
                        if (results.size() >= limit * 3) return; // early exit: collected enough candidates
                        searchFile(filePath, needle, results);
                    });
        } catch (IOException e) {
            log.warn("FileSystemKnowledgeSource '{}': error walking '{}'", name, rootPath, e);
        }

        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results.stream().limit(limit).toList();
    }

    private void searchFile(Path filePath, String needle, List<KnowledgeDocument> results) {
        List<String> lines;
        try {
            lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return; // binary or non-UTF-8 file
        } catch (IOException e) {
            log.warn("FileSystemKnowledgeSource '{}': cannot read file '{}'", name, filePath, e);
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                int from = Math.max(0, i - CONTEXT_LINES);
                int to = Math.min(lines.size(), i + CONTEXT_LINES + 1);
                String snippet = String.join("\n", lines.subList(from, to));

                String relativePath = rootPath.relativize(filePath).toString();
                results.add(KnowledgeDocument.builder()
                        .id(relativePath + ":" + (i + 1))
                        .content(snippet)
                        .sourceName(name)
                        .score(0.5) // keyword match — fixed score; semantic score not available
                        .metadata(java.util.Map.of("file", relativePath, "line", i + 1))
                        .build());
            }
        }
    }
}
