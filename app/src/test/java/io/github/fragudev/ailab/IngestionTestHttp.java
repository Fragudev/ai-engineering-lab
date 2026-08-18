package io.github.fragudev.ailab;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared, framework-free HTTP helpers for the ingestion integration tests — see their javadoc. */
final class IngestionTestHttp {

    private IngestionTestHttp() {}

    static HttpResponse<String> upload(
            HttpClient httpClient, int port, String filename, String contentType, String content, String title)
            throws IOException, InterruptedException {
        String boundary = "----ailab-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, filename, contentType, content, "title", title);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/documents".formatted(port)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static String awaitTerminalJob(HttpClient httpClient, int port, String jobLocation) {
        var holder = new Object() {
            String body = "";
        };
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .until(() -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:%d%s".formatted(port, jobLocation)))
                            .GET()
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    holder.body = response.body();
                    return holder.body.contains("\"stage\":\"INDEXED\"")
                            || holder.body.contains("\"stage\":\"FAILED\"");
                });
        return holder.body;
    }

    private static byte[] multipartBody(
            String boundary, String filename, String contentType, String fileContent, String... extraFields) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n"
                        .formatted(boundary, filename, contentType))
                .getBytes(StandardCharsets.UTF_8));
        parts.add(fileContent.getBytes(StandardCharsets.UTF_8));
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i + 1 < extraFields.length; i += 2) {
            parts.add(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                            .formatted(boundary, extraFields[i], extraFields[i + 1]))
                    .getBytes(StandardCharsets.UTF_8));
        }
        parts.add(("--%s--\r\n".formatted(boundary)).getBytes(StandardCharsets.UTF_8));

        int total = parts.stream().mapToInt(p -> p.length).sum();
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
