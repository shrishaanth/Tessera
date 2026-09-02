package com.tessera.fleet.geocoding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Tiny HTTP-GET seam so {@link GeocodingService} can be unit-tested without a
 * network. The default bean is {@link HttpUrlFetcher}.
 */
public interface UrlFetcher {

    /** @return the response body, or throws on a non-2xx status or transport error. */
    String get(URI uri, Map<String, String> headers, int timeoutMs) throws IOException;

    /** Real implementation over {@link java.net.http.HttpClient}. */
    class HttpUrlFetcher implements UrlFetcher {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String get(URI uri, Map<String, String> headers, int timeoutMs) throws IOException {
            HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET();
            headers.forEach(req::header);
            try {
                HttpResponse<String> resp = client.send(req.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + resp.statusCode() + " from " + uri.getHost());
                }
                return resp.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }
}
