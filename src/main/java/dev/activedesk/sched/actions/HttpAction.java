package dev.activedesk.sched.actions;

import dev.activedesk.sched.TaskAction;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP request / lightweight web scraper.
 * params: url, method (GET), body, header.Name, expectStatus (any 2xx), extract (regex), saveTo (file).
 */
public final class HttpAction implements TaskAction {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String run(Context ctx) throws Exception {
        String url = ctx.task().param("url", "");
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new IllegalArgumentException("http task needs params.url starting with http:// or https://");
        String method = ctx.task().param("method", "GET").toUpperCase();
        String body = ctx.task().param("body");

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(ctx.timeout())
                .header("User-Agent", "ActiveDesk/1.0");
        for (Map.Entry<String, String> e : ctx.task().params().entrySet()) {
            if (e.getKey().startsWith("header.")) rb.header(e.getKey().substring(7), e.getValue());
        }
        rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        HttpResponse<byte[]> resp = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        String expect = ctx.task().param("expectStatus");
        boolean ok = expect == null || expect.isBlank() ? status / 100 == 2 : Integer.parseInt(expect.trim()) == status;
        if (!ok) throw new RuntimeException("HTTP " + status + " (expected " + (expect == null ? "2xx" : expect) + ")");

        byte[] bytes = resp.body();
        String saveTo = ctx.task().param("saveTo");
        if (saveTo != null && !saveTo.isBlank()) {
            Path p = Path.of(saveTo);
            if (p.toAbsolutePath().getParent() != null) Files.createDirectories(p.toAbsolutePath().getParent());
            Files.write(p, bytes);
        }

        StringBuilder out = new StringBuilder().append(status).append(' ').append(bytes.length).append(" bytes");
        String extract = ctx.task().param("extract");
        if (extract != null && !extract.isBlank()) {
            Matcher m = Pattern.compile(extract).matcher(new String(bytes, StandardCharsets.UTF_8));
            int n = 0;
            while (m.find()) {
                out.append('\n').append(m.groupCount() >= 1 ? m.group(1) : m.group());
                n++;
            }
            out.append("\n(").append(n).append(" matches)");
        }
        return out.toString();
    }
}
