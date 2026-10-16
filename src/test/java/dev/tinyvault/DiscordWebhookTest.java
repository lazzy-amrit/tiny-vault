package dev.tinyvault;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiscordWebhookTest {
    @Test
    void postsBackupAnnouncementWithoutBlockingOnARealDiscordConnection() throws Exception {
        AtomicReference<HttpRequest> received = new AtomicReference<>();
        CountDownLatch posted = new CountDownLatch(1);
        HttpClient mock = new HttpClient() {
            @Override
            public Optional<CookieHandler> cookieHandler() {
                return Optional.empty();
            }

            @Override
            public Optional<Duration> connectTimeout() {
                return Optional.empty();
            }

            @Override
            public Redirect followRedirects() {
                return Redirect.NEVER;
            }

            @Override
            public Optional<ProxySelector> proxy() {
                return Optional.empty();
            }

            @Override
            public SSLContext sslContext() {
                return null;
            }

            @Override
            public SSLParameters sslParameters() {
                return new SSLParameters();
            }

            @Override
            public Optional<Authenticator> authenticator() {
                return Optional.empty();
            }

            @Override
            public Version version() {
                return Version.HTTP_1_1;
            }

            @Override
            public Optional<Executor> executor() {
                return Optional.empty();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                    throws IOException {
                received.set(request);
                posted.countDown();
                return (HttpResponse<T>) new MockResponse(request);
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
        };
        List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        DiscordWebhook webhook = new DiscordWebhook(
                "https://discord.com/api/webhooks/123/test-token", warnings::add, mock);
        try {
            webhook.success(Instant.parse("2025-01-02T03:04:05Z"), 1000,
                    Duration.ofSeconds(5), "in 24h");
            assertTrue(posted.await(2, TimeUnit.SECONDS));
            HttpRequest request = received.get();
            assertNotNull(request);
            assertEquals("POST", request.method());
            assertEquals(URI.create("https://discord.com/api/webhooks/123/test-token"), request.uri());
            assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(warnings.isEmpty());
        } finally {
            webhook.close();
        }
    }

    private record MockResponse(HttpRequest request) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 204;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return "";
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}