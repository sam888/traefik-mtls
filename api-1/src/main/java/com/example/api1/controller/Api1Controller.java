package com.example.api1.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * API 1 Controller — plain HTTP only, zero TLS code.
 *
 * Outbound calls go to Traefik 1 (plain HTTP).
 * Traefik 1 transparently upgrades the connection to mTLS when
 * forwarding to Traefik 2. Neither this class nor anywhere else
 * in API 1 knows or cares about certificates.
 */
@Slf4j
@RestController
@RequestMapping("/api1")
public class Api1Controller {

    private final WebClient webClient;

    public Api1Controller(@Value("${traefik1.url}") String traefik1Url) {
        // Plain HTTP WebClient — no SSL config whatsoever
        this.webClient = WebClient.builder()
            .baseUrl( traefik1Url )
            .build();
    }

    /**
     * API 1's own health/info endpoint.
     */
    @GetMapping("/hello")
    public Mono<Map<String, Object>> hello() {
        log.info("GET /api1/hello");
        return Mono.just(Map.of(
            "service",   "api1",
            "message",   "Hello from API 1 — running behind Traefik 1",
            "timestamp", Instant.now().toString(),
            "tls",       "none — Traefik 1 handles mTLS on my behalf"
        ));
    }

    /**
     * Triggers the full proxy chain:
     *   API 1 → (plain HTTP) → Traefik 1 → (mTLS) → Traefik 2 → (plain HTTP) → API 2
     *
     * From this controller's perspective it is just a plain HTTP GET.
     * All mTLS negotiation happens invisibly inside Traefik 1.
     */
    @GetMapping("/call-api2")
    public Mono<Map> callApi2() {
        log.info("GET /api1/call-api2 → forwarding to API 2 via Traefik mTLS chain");
        return webClient.get()
            .uri("/api2/hello")   // Traefik 1 routes /api2/* → mTLS → Traefik 2 → API 2
            .retrieve()
            .bodyToMono(Map.class)
            .doOnSuccess(r  -> log.info("✅ API 2 responded: {}", r))
            .doOnError(e    -> log.error("❌ Call to API 2 failed: {}", e.getMessage()));
    }

    /**
     * Calls the /api2/info endpoint through the same proxy chain.
     */
    @GetMapping("/call-api2/info")
    public Mono<Map> callApi2Info() {
        log.info("GET /api1/call-api2/info");
        return webClient.get()
            .uri("/api2/info")
            .retrieve()
            .bodyToMono(Map.class)
            .doOnSuccess(r -> log.info("✅ API 2 /info: {}", r));
    }
}
