package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fetches a web page and returns its content as text, markdown, or raw HTML.
 * Respects 5 MB response limit and customizable timeout (max 120 s).
 */
@Component
public class WebFetchTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECS = 30;
    private static final int MAX_TIMEOUT_SECS = 120;
    private static final long MAX_BODY_BYTES = 5L * 1024 * 1024;

    private static final String UA_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String UA_PLAIN = "opencode";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?s)<(script|style|meta|link)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern H_TAG = Pattern.compile(
            "(?s)<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern A_TAG = Pattern.compile(
            "(?s)<a[^>]+href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG_TAG = Pattern.compile(
            "(?s)<(strong|b)[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EM_TAG = Pattern.compile(
            "(?s)<(em|i)[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE);

    // -----------------------------------------------------------------------
    // Parameter helpers
    // -----------------------------------------------------------------------
    private static final Pattern PRE_TAG = Pattern.compile(
            "(?s)<pre[^>]*>(.*?)</pre>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_TAG = Pattern.compile(
            "(?s)<code[^>]*>(.*?)</code>", Pattern.CASE_INSENSITIVE);

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?s)<(?:p|div|br|li|tr|h[1-6])[^>]*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\n{3,}");

    // -----------------------------------------------------------------------
    // Body formatting
    // -----------------------------------------------------------------------
    private static final Pattern HTML_ENTITIES = Pattern.compile(
            "&(amp|lt|gt|quot|nbsp|#\\d+|#x[0-9a-fA-F]+);");

    // -----------------------------------------------------------------------
    // HTML → Markdown (regex-based, no external deps)
    // -----------------------------------------------------------------------
    private static final Pattern NOSCRIPT_IFRAME = Pattern.compile(
            "(?s)<(noscript|iframe|object|embed)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "Fetch the content of a web page by URL. " +
                "Parameter 'url': must start with http:// or https://. " +
                "Parameter 'format': 'markdown' (default), 'text', or 'html'. " +
                "Parameter 'timeout': request timeout in seconds (default 30, max 120). " +
                "Returns the page content prefixed with '<url> (<content-type>)'.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "URL to fetch (http:// or https:// only)"),
                        "format", Map.of(
                                "type", "string",
                                "enum", List.of("text", "markdown", "html"),
                                "description", "Output format: markdown (default), text, or html"),
                        "timeout", Map.of(
                                "type", "integer",
                                "description", "Timeout in seconds (default 30, max 120)")
                ),
                "required", List.of("url")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        Object rawUrl = input.getParameters().get("url");
        if (!(rawUrl instanceof String url) || url.isBlank()) {
            return ToolResult.error("Parameter 'url' is required");
        }
        url = url.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("Invalid URL: must start with http:// or https://");
        }

        String format = resolveFormat(input.getParameters().get("format"));
        if (format == null) {
            return ToolResult.error("Parameter 'format' must be 'text', 'markdown', or 'html'");
        }

        int timeoutSecs = resolveTimeout(input.getParameters().get("timeout"));

        try {
            URI uri = URI.create(url);
            HttpResponse<byte[]> response = sendRequest(uri, format, timeoutSecs, UA_CHROME);

            // Cloudflare challenge retry
            if (response.statusCode() == 403) {
                String cfMitigated = response.headers().firstValue("cf-mitigated").orElse("");
                if ("challenge".equalsIgnoreCase(cfMitigated)) {
                    response = sendRequest(uri, format, timeoutSecs, UA_PLAIN);
                }
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ToolResult.error("HTTP " + response.statusCode() + " for " + url);
            }

            // Content-Length pre-check
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > MAX_BODY_BYTES) {
                return ToolResult.error("Response too large (Content-Length exceeds 5 MB)");
            }

            byte[] body = response.body();
            if (body.length > MAX_BODY_BYTES) {
                return ToolResult.error("Response too large (body exceeds 5 MB)");
            }

            String contentType = response.headers().firstValue("content-type").orElse("text/plain");
            String header = url + " (" + contentType + ")";

            if (isImage(contentType)) {
                String mimeType = contentType.split(";")[0].trim();
                String b64 = Base64.getEncoder().encodeToString(body);
                return ToolResult.ok(header + "\nImage fetched successfully\ndata:" + mimeType + ";base64," + b64);
            }

            String text = new String(body, StandardCharsets.UTF_8);
            boolean isHtml = contentType.contains("text/html") || contentType.contains("application/xhtml");
            String result = formatBody(text, format, isHtml);

            return ToolResult.ok(header + "\n\n" + result);

        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Request timed out after " + timeoutSecs + "s");
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid URL: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Request interrupted");
        } catch (Exception e) {
            return ToolResult.error("Failed to fetch " + url + ": " + e.getMessage());
        }
    }

    @Override
    public boolean isRisky() {
        return false;
    }

    private String resolveFormat(Object raw) {
        if (raw == null) return "markdown";
        if (!(raw instanceof String f)) return null;
        f = f.toLowerCase().strip();
        return switch (f) {
            case "text", "markdown", "html" -> f;
            default -> null;
        };
    }

    private int resolveTimeout(Object raw) {
        if (!(raw instanceof Number n)) return DEFAULT_TIMEOUT_SECS;
        return Math.min(MAX_TIMEOUT_SECS, Math.max(1, n.intValue()));
    }

    private HttpResponse<byte[]> sendRequest(URI uri, String format, int timeoutSecs, String userAgent)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSecs))
                .header("User-Agent", userAgent)
                .header("Accept", buildAcceptHeader(format))
                .header("Accept-Language", "en-US,en")
                .GET()
                .build();
        return HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private String buildAcceptHeader(String format) {
        return switch (format) {
            case "markdown" -> "text/markdown,text/html;q=0.9,text/plain;q=0.8,*/*;q=0.5";
            case "text" -> "text/plain,text/html;q=0.9,*/*;q=0.5";
            case "html" -> "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.5";
            default -> "*/*";
        };
    }

    private boolean isImage(String contentType) {
        return contentType.toLowerCase().startsWith("image/");
    }

    private String formatBody(String text, String format, boolean isHtml) {
        return switch (format) {
            case "html" -> text;
            case "text" -> isHtml ? extractText(text) : text;
            case "markdown" -> isHtml ? htmlToMarkdown(text) : text;
            default -> text;
        };
    }

    // -----------------------------------------------------------------------
    // HTML → plain text
    // -----------------------------------------------------------------------

    String htmlToMarkdown(String html) {
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = H_TAG.matcher(s).replaceAll(mr ->
                "#".repeat(Integer.parseInt(mr.group(1))) + " " + stripTags(mr.group(2)) + "\n");
        s = A_TAG.matcher(s).replaceAll(mr ->
                "[" + stripTags(mr.group(2)) + "](" + mr.group(1) + ")");
        s = STRONG_TAG.matcher(s).replaceAll(mr -> "**" + stripTags(mr.group(2)) + "**");
        s = EM_TAG.matcher(s).replaceAll(mr -> "*" + stripTags(mr.group(2)) + "*");
        s = PRE_TAG.matcher(s).replaceAll(mr -> "\n```\n" + stripTags(mr.group(1)) + "\n```\n");
        s = CODE_TAG.matcher(s).replaceAll(mr -> "`" + mr.group(1) + "`");
        s = BLOCK_TAG.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    String extractText(String html) {
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = NOSCRIPT_IFRAME.matcher(s).replaceAll(" ");
        s = BLOCK_TAG.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    private String stripTags(String html) {
        return ANY_TAG.matcher(html).replaceAll("").strip();
    }

    private String decodeEntities(String s) {
        return HTML_ENTITIES.matcher(s).replaceAll(mr -> {
            String g = mr.group(1);
            return switch (g.toLowerCase()) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "nbsp" -> " ";
                default -> {
                    if (g.startsWith("#x") || g.startsWith("#X")) {
                        yield String.valueOf((char) Integer.parseInt(g.substring(2), 16));
                    } else if (g.startsWith("#")) {
                        yield String.valueOf((char) Integer.parseInt(g.substring(1)));
                    }
                    yield mr.group(0);
                }
            };
        });
    }
}
