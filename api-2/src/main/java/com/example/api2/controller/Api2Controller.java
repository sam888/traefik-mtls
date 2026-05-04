package com.example.api2.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * API 2 Controller — plain HTTP only, zero TLS code.
 *
 * By the time any request arrives here, Traefik 2 has already:
 *   1. Received the mTLS connection from Traefik 1
 *   2. Verified Traefik 1 presented a cert signed by our trusted CA
 *   3. Terminated TLS and forwarded plain HTTP to this service
 *
 * This controller is completely unaware any of that happened.
 * It just processes plain HTTP requests.
 */
@Slf4j
@RestController
@RequestMapping("/api2")
public class Api2Controller {

    /**
     * Main endpoint — confirms API 2 received the request after mTLS verification.
     * The X-Forwarded-* headers are injected by Traefik 2.
     */
    @GetMapping("/hello")
    public Mono<Map<String, Object>> hello(
            @RequestHeader(value = "X-Forwarded-For",   defaultValue = "unknown") String forwardedFor,
            @RequestHeader(value = "X-Forwarded-Host",  defaultValue = "unknown") String forwardedHost,
            @RequestHeader(value = "X-Forwarded-Proto", defaultValue = "unknown") String forwardedProto) {

        log.info("GET /api2/hello — arrived after Traefik 2 mTLS verification");
        log.info("  X-Forwarded-For:   {}", forwardedFor);
        log.info("  X-Forwarded-Proto: {} (was mTLS before Traefik 2 terminated it)", forwardedProto);

        String message = null;
        if ( "traefik1".equals( forwardedFor ) ) {
           message = "Hello from API 2! You have accessed API 2 directly without mTLS by Traefik 2";
        } else {
           message = "Hello from API 2! Traefik 2 verified your mTLS client cert before letting you in";
        }

        return Mono.just(Map.of(
            "service",        "api2",
            "message",        message,
            "timestamp",      Instant.now().toString(),
            "receivedVia",    "Traefik 2 (mTLS terminated — I only see plain HTTP)",
            "forwardedFor",   forwardedFor,
            "forwardedHost",  forwardedHost,
            "forwardedProto", forwardedProto
        ));
    }

    /**
     * Info endpoint — shows API 2 identity and protocol details.
     */
    @GetMapping("/info")
    public Mono<Map<String, Object>> info() {
        log.info("GET /api2/info");
        return Mono.just(Map.of(
            "service",   "api2",
            "port",      8082,
            "protocol",  "HTTP (Traefik 2 handles mTLS externally)",
            "security",  "Enforced by Traefik 2 — RequireAndVerifyClientCert",
            "timestamp", Instant.now().toString()
        ));
    }
}
