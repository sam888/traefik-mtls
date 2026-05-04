# Overview
This document walks through setting up a working mTLS solution using Traefik and Spring Boot. The accompanying code can be run locally with Docker Compose, demonstrating how to set up a minimal mTLS configuration to secure service-to-service communication through mutual certificate authentication — no Java code required. It is intended as a practical reference for implementing mTLS in a microservices environment.

# Motivation
While I've been aware of Mutual TLS (mTLS) for years, I never had a real reason to implement it until I realised it is widely mandated in industries like banking, healthcare, and big tech recently. So curiosity turned into a practical challenge: I wanted to see what it actually takes to implement this — and this guide is the result.

My first instinct was to implement mTLS directly in Spring Boot. But I found out quickly the effort required in using Spring Boot to implement mTLS is way too complicated for its worth, not to mention the steep learning curve and the extensive boilerplate required for each API. Just because Spring Boot can doesn't mean it should.

Just to give reader a taste what kind of learning effort would it take to do so in Spring Boot, I will list a few top links found for this purpose.

* #### 🥇 Best Overall — Hakky54's GitHub Repo
  A comprehensive tutorial covering mTLS for a Java-based web server and client with Spring Boot, including Spring WebFlux WebClient with both Jetty and Netty — plus many other client implementations.
  👉 **[https://github.com/Hakky54/mutual-tls-ssl](https://github.com/Hakky54/mutual-tls-ssl)** — This may be the most complete reference one will find.

* #### 🥈 Best Written Tutorial — grantlittle.me
  Covers specifically configuring Spring Boot WebFlux with `@EnableWebFluxSecurity`, `ServerHttpSecurity`, and `SubjectDnX509PrincipalExtractor` for extracting user identity from the certificate's Common Name (CN).
  👉 **[https://grantlittle.me/2024/08/16/mtls-client-authentication-with-spring-boot/](https://grantlittle.me/2024/08/16/mtls-client-authentication-with-spring-boot/)** — Most up to date (2024), WebFlux specific.

* #### 🥉 Best for Concepts — Baeldung
  Covers both server verification and mutual authentication, including pros and cons — for example, the private key of an X.509 client certificate is stronger than any user-defined password, but certificate management becomes costly at scale.
  👉 [Baeldung](https://www.baeldung.com/x-509-authentication-in-spring-security)

* #### Official Spring Security Sample
  The Spring Security team maintains an official WebFlux X.509 sample configuring Netty and WebClient with mutual TLS.
  👉 [Spring](https://docs.spring.io/spring-security/site/docs/5.2.5.RELEASE/reference/html/reactive-x509.html)

There had to be a simpler way to handle this, especially in an enterprise setting. After some research, I settled on Traefik — and the case for it is straightforward:
- **Platform independence** — Traefik runs on Windows, Linux, macOS, Docker, and Kubernetes with the same configuration model, unlike NGINX Ingress Controller which is Kubernetes-only.
- **Widely adopted** — 3.4+ billion downloads, 58,000+ GitHub stars, and 900+ contributors. Trusted by brands including Condé Nast, eBay Classifieds, and Mailchimp, and ranked in Docker Hub's top 10 most downloaded projects.
- **mTLS is a first-class feature** — Traefik has native support for mTLS, making configuration straightforward compared to the Java ecosystem approach.

If you'd like to dig deeper into Traefik before proceeding, here are a few resources worth bookmarking:
* **Baeldung — Introduction to Traefik** A beginner-friendly intro to Traefik concepts and configuration, consistent with Baeldung's usual style. Good for readers unfamiliar with Traefik before diving into the guide. 👉  [https://www.baeldung.com/traefik-tutorial](https://www.baeldung.com/traefik-tutorial)
- 🥇 **Best Overall — Traefik Official mTLS Docs** The canonical reference for Traefik's TLS configuration options, including `clientAuthType` and `RequireAndVerifyClientCert`. Always up to date. 👉 [https://doc.traefik.io/traefik/reference/routing-configuration/http/tls/tls-options/](https://doc.traefik.io/traefik/reference/routing-configuration/http/tls/tls-options/)
- 🥈 **Best Written Tutorial — DEV Community (badgerbadgerbadgerbadger)** An end-to-end mTLS walkthrough using Traefik as reverse proxy, starting from TLS concepts and building up to a productionisable setup. Concepts translate well beyond the Golang backend used. 👉 [https://dev.to/badgerbadgerbadgerbadger/yet-another-mtls-tutorial-10pp](https://dev.to/badgerbadgerbadgerbadger/yet-another-mtls-tutorial-10pp) — Worth reading for the conceptual depth alone.
- 🥉 **Best for Kubernetes Context — Vlad Vitan on Medium** Covers configuring mTLS with Traefik as an ingress controller in a Kubernetes cluster using Minikube. Most up to date (2025). 👉 [https://vladvitan.medium.com/configuring-mutual-tls-with-traefik-as-an-ingress-controller-c96f85fe7b6a](https://vladvitan.medium.com/configuring-mutual-tls-with-traefik-as-an-ingress-controller-c96f85fe7b6a)
- **Best GitHub Example — vahempio/PKI-Traefik-mTLS** A full working local example with a private PKI, covering per-client certificate control. Good companion to your own repo. 👉 [https://github.com/vahempio/PKI-Traefik-mTLS](https://github.com/vahempio/PKI-Traefik-mTLS)

**Note:** mTLS secures the transport layer but does not replace application-level authentication. In production, pairing mTLS with JWT tokens is considered best practice in zero-trust architectures, particularly in industries like financial services and healthcare.
# Traefik mTLS — Mutual TLS Between Two Spring Boot APIs

A complete working demonstration of **X.509 Mutual TLS authentication** between
two Spring Boot WebFlux APIs, enforced entirely at the **Traefik proxy layer**.

The Spring Boot apps speak plain HTTP — they contain **zero TLS or security code**.
All certificate management lives in Traefik configuration. This is the pattern used
in enterprise environments and is the foundation of how service meshes like Istio work.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Docker Network                                          |   
│                                                                                 | 
│  ┌──────────┐   HTTP    ┌───────────┐  mTLS   ┌───────────┐  HTTP  ┌──────────┐ │
│  │          │ :8081     │           │  :443   │           │ :8082  │          │ │
│  │  API 1   │◄─────────►│ Traefik 1 │◄═══════►│ Traefik 2 │◄──────►│  API 2   │ │
│  │ (Spring) │           │ (client)  │         │ (server)  │        │ (Spring) │ │
│  └──────────┘           └───────────┘         └───────────┘        └──────────┘ │
│                               │                     │                           │
│                         network-left          network-right                     │
│                               └──────────────────────┘                          │
│                                     network-bridge                              │
└─────────────────────────────────────────────────────────────────────────────────┘

External caller → Traefik 1 :80 (plain HTTP)
                       ↓
              [Traefik 1 adds client cert]
                       ↓
              Traefik 2 :443 (mTLS — RequireAndVerifyClientCert)
                       ↓
              [Traefik 2 verifies client cert against CA]
                       ↓
              API 2 :8082 (plain HTTP — completely unaware of TLS)
```

### Why Two Traefik Instances?

| Instance      | Role                            | What it does                                                  |
| ------------- | ------------------------------- | ------------------------------------------------------------- |
| **Traefik 1** | mTLS **client** / egress proxy  | Attaches client cert to outbound calls to Traefik 2           |
| **Traefik 2** | mTLS **server** / ingress proxy | Enforces client cert requirement; rejects uncertified callers |

This mirrors how real enterprise deployments work:

- Each service zone has its own proxy (sidecar pattern)
- The mTLS handshake happens **between proxies**, not between applications
- Applications are completely decoupled from security infrastructure

---

## Project Structure

```
traefik-mtls/
├── certs/
│   └── generate-certs.sh          # Generates all 3 certificates from scratch
│
├── traefik1/                      # Traefik 1 — mTLS CLIENT config
│   ├── traefik.yml                # Static config (entrypoints, providers)
│   └── dynamic/
│       └── config.yml             # Dynamic config (serversTransport + routes)
│
├── traefik2/                      # Traefik 2 — mTLS SERVER config
│   ├── traefik.yml                # Static config (entrypoints, providers)
│   └── dynamic/
│       └── config.yml             # Dynamic config (tls options + routes)
│
├── api-1/                          # Spring Boot API 1 — plain HTTP only
│   ├── Dockerfile
│   ├── build.gradle
│   └── src/main/java/com/example/api1/
│       ├── Api1Application.java
│       ├── controller/
│       │   └── Api1Controller.java  # Calls API 2 via plain HTTP to Traefik 1
│       └── resources/
│           └── application.yml      # No SSL config — just port 8081
│
├── api-2/                          # Spring Boot API 2 — plain HTTP only
│   ├── Dockerfile
│   ├── build.gradle
│   └── src/main/java/com/example/api2/
│       ├── Api2Application.java
│       ├── controller/
│       │   └── Api2Controller.java  # Receives requests; shows forwarded headers
│       └── resources/
│           └── application.yml      # No SSL config — just port 8082
│
├── docker-compose.yml             # Wires all 4 services + 3 isolated networks
└── README.md
```

---

## How mTLS Works — Step by Step

Before running anything, understand what is happening so you can debug it.

### The Certificates (3 files, 1 CA)

```
Certificate Authority (ca.crt)
        │
        ├── signs ──► traefik2.crt  (Traefik 2's SERVER identity)
        │
        └── signs ──► traefik1.crt  (Traefik 1's CLIENT identity)
```

Both certificates are signed by the **same CA**. This is what makes mutual
trust possible — each side trusts the CA, and therefore trusts any certificate
the CA has signed.

### The Full TLS Handshake Sequence

```
API 1            Traefik 1              Traefik 2              API 2
  │                  │                      │                    │
  │──GET /api2/hello►│                      │                    │
  │  (plain HTTP)    │                      │                    │
  │                  │                      │                    │
  │             [Traefik 1 sees PathPrefix(/api2)]               │
  │             [selects traefik2-mtls-transport]                │
  │                  │                      │                    │
  │                  │──TCP connect────────►│                    │
  │                  │◄─TCP accept──────────│                    │
  │                  │                      │                    │
  │                  │  ┌─ TLS Handshake ──────────────────────┐ │
  │                  │  │                  │                   │ │
  │                  │──┼──ClientHello────►│                   │ │
  │                  │◄─┼──ServerHello─────│                   │ │
  │                  │◄─┼──traefik2.crt────│  (server cert)    │ │
  │                  │  │                  │                   │ │
  │             [Traefik 1 verifies traefik2.crt against ca.crt ✅]
  │                  │  │                  │                   │ │
  │                  │◄─┼──CertificateRequest──────────────────│ │
  │                  │──┼──traefik1.crt───►│  (client cert)    │ │
  │                  │  │                  │                   │ │
  │                  │  │  [Traefik 2 verifies traefik1.crt against ca.crt ✅]
  │                  │  │  [mTLS HANDSHAKE COMPLETE]           │ │
  │                  │  └──────────────────────────────────────┘ │
  │                  │                      │                    │
  │                  │──GET /api2/hello────►│                    │
  │                  │  (encrypted mTLS)    │                    │
  │                  │                      │──GET /api2/hello──►│
  │                  │                      │  (plain HTTP)      │
  │                  │                      │◄─200 OK────────────│
  │                  │◄─200 OK──────────────│                    │
  │◄─200 OK──────────│                      │                    │
  │  (plain HTTP)    │                      │                    │
```

### What Happens if Traefik 1 Has No Certificate?

```
Traefik 1 → TCP connect → Traefik 2
         ← ServerHello + CertificateRequest
         → (no client cert presented)
         ← TLS ALERT: certificate_required
         CONNECTION CLOSED
```

Traefik 2 drops the connection at the TLS layer. The Spring Boot apps never
see this error — it happens entirely in the proxy layer.

---

## Quick Start

### Prerequisites

- Docker and Docker Compose
- OpenSSL (for certificate generation)
- `curl` (for testing)

Check you have them:

```bash
docker --version        # Docker version 24+
docker compose version  # v2.x
openssl version         # OpenSSL 1.1.x or 3.x
```

---

### Step 1 — Generate Certificates

```bash
# Clone the repo and navigate to it
git clone <my-repo-url>
cd traefik-mtls

# Create log directories
mkdir -p logs/traefik1 logs/traefik2

# Generate certificates
cd certs
chmod +x generate-certs.sh
./generate-certs.sh
cd ..
```

After this you should have:

```
certs/
├── ca.crt         ← Certificate Authority (trusted root)
├── ca.key         ← CA private key (keep safe — signs everything)
├── traefik1.crt   ← Traefik 1 client certificate
├── traefik1.key   ← Traefik 1 client private key
├── traefik2.crt   ← Traefik 2 server certificate
└── traefik2.key   ← Traefik 2 server private key
```

Verify the certificates are correctly signed:

```bash
# Verify traefik2.crt was signed by ca.crt
openssl verify -CAfile certs/ca.crt certs/traefik2.crt
# Expected: certs/traefik2.crt: OK

# Verify traefik1.crt was signed by ca.crt
openssl verify -CAfile certs/ca.crt certs/traefik1.crt
# Expected: certs/traefik1.crt: OK

# Inspect the server cert Subject Alternative Names
openssl x509 -in certs/traefik2.crt -noout -text | grep -A2 "Subject Alternative"
# Expected: DNS:traefik2, DNS:localhost, IP:127.0.0.1
```

Note the last command above is important for mTLS Debugging as the Subject Alternative Name (SAN) is the field that must match the serverName in Traefik 1's config:

```yaml
# traefik1/dynamic/config.yml
serversTransports:
  traefik2-mtls-transport:
    serverName: "traefik2"   # ← must exactly match a DNS entry in the SAN
```

If serverName: "traefik2" but the SAN only contains DNS:localhost, you get:
```bash
x509: certificate is valid for localhost, not traefik2
```

So the command is essentially a sanity check — confirming the cert has the right hostnames before you waste time debugging a mismatch error at runtime.



---

### Step 2 — Build and Start Everything

```bash
docker compose up --build
```

First run takes 2~3 minutes to build the Spring Boot JARs.
On subsequent runs it uses Docker layer cache and starts in seconds.

Watch for these log lines to confirm everything is healthy:

```
api-1    | Started Api1Application in 3.2 seconds
api-2    | Started Api2Application in 3.1 seconds
traefik1 | "Configuration loaded from directory, file=/etc/traefik/dynamic"
traefik2 | "Configuration loaded from directory, file=/etc/traefik/dynamic"
```

In a separate terminal, confirm all 4 containers are running:

```bash
docker compose ps

# Expected:
# NAME       STATUS          PORTS
# api-1       Up (healthy)    0.0.0.0:8081->8081/tcp
# api-2       Up (healthy)    0.0.0.0:8082->8082/tcp
# traefik1   Up              0.0.0.0:80->80/tcp, 0.0.0.0:8880->8080/tcp
# traefik2   Up              0.0.0.0:443->443/tcp, 0.0.0.0:8881->8080/tcp
```

---

### Step 3 — Explore the Traefik Dashboards

Open these in your browser before testing — they show the routing config visually:

- **Traefik 1 dashboard**: http://localhost:8880/dashboard/
- **Traefik 2 dashboard**: http://localhost:8881/dashboard/

In the dashboards look for:

- **Traefik 1** → HTTP routers `to-api1` and `to-api2` | Service `traefik2-service` with `traefik2-mtls-transport`
- **Traefik 2** → HTTP router `api2-router` with TLS option `mtls-required` | TLS options showing `RequireAndVerifyClientCert`

---

### Step 4 — Test the mTLS Chain

#### Test 1 — Direct API access (bypasses Traefik, for comparison)

```bash
# Call API 1 directly (plain HTTP — no proxy involved)
curl http://localhost:8081/api1/hello

# Expected response:
{
  "service": "API 1",
  "message": "Hello from API 1 — running behind Traefik 1",
  "tls": "none — Traefik 1 handles mTLS on my behalf"
}
```

```bash
# Call API 2 directly (bypasses Traefik 2 — no mTLS enforced here)
curl http://localhost:8082/api2/hello
```

> ⚠️  Note: In production, port 8082 would NOT be exposed. The only way
> to reach API 2 should be through Traefik 2. The direct port is only
> exposed here for debugging.

---

#### Test 2 — Call API 2 via Traefik 2 WITHOUT a client certificate

```bash
# This should FAIL — Traefik 2 requires a client cert
	curl -k https://localhost:443/api2/hello
```

Expected result:

```
curl: (56) OpenSSL SSL_read: error:0A00045C:SSL routines::tlsv13 alert certificate required
```

This is exactly what we want — Traefik 2 rejects the connection at the TLS
layer before it even reaches API 2.

---

#### Test 3 — Call API 2 via Traefik 2 WITH the client certificate

```bash
# This should SUCCEED — presenting traefik1.crt satisfies mTLS requirement
curl --cacert certs/ca.crt \
     --cert certs/traefik1.crt \
     --key certs/traefik1.key \
     https://localhost:443/api2/hello
```

Expected response:

```json
{
  "timestamp":"2026-05-04T19:17:44.728409761Z",
  "service":"api2",
  "forwardedFor":"192.168.65.1",
  "receivedVia":"Traefik 2 (mTLS terminated — I only see plain HTTP)",
  "forwardedHost":"localhost",
  "forwardedProto":"https",
  "message":"Hello from API 2! Traefik 2 verified your mTLS client cert before letting you in"
}
```

---

#### Test 4 — The Full Chain (the real demo)

This is the **money shot** — API 1 calling API 2 through the complete proxy chain,
with zero TLS code in either Spring Boot app:

```bash
# Call API 1 on Traefik 1 (plain HTTP), which internally calls API 2 on Traefik 2 via mTLS
curl http://localhost:80/api1/call-api2
```

Expected response:
```bash
{ 
  "timestamp": "2026-05-04T19:09:20.536883485Z",
  "service": "api2",
  "forwardedFor": "172.21.0.3",
  "receivedVia": "Traefik 2 (mTLS terminated — I only see plain HTTP)",
  "forwardedHost": "traefik1",
  "forwardedProto": "https",
  "message": "Hello from API 2! Traefik 2 verified your mTLS client cert before letting you in"
} 
```

What happens internally:

1. `curl` sends plain HTTP to Traefik 1 port 80
2. Traefik 1 matches `PathPrefix(/api2)` → routes to `traefik2-service`
3. Traefik 1 opens mTLS connection to Traefik 2, presenting `traefik1.crt`
4. Traefik 2 verifies `traefik1.crt` against `ca.crt` ✅
5. Traefik 2 forwards plain HTTP to API 2 port 8082
6. API 2 responds → response flows back through the chain

This exercises the full chain:

```
curl → Traefik 1 → API 1 → Traefik 1 → [mTLS] → Traefik 2 → API 2

```

---

#### Test 5 — Verify mTLS is really enforced (wrong CA)

```bash
# Generate a rogue certificate signed by a DIFFERENT CA
openssl req -x509 -newkey rsa:2048 -days 1 -nodes \
  -keyout /tmp/rogue-ca.key -out /tmp/rogue-ca.crt \
  -subj "/CN=Rogue-CA"

openssl req -newkey rsa:2048 -nodes \
  -keyout /tmp/rogue-client.key -out /tmp/rogue-client.csr \
  -subj "/CN=rogue-client"

openssl x509 -req -days 1 \
  -in /tmp/rogue-client.csr \
  -CA /tmp/rogue-ca.crt \
  -CAkey /tmp/rogue-ca.key \
  -CAcreateserial \
  -out /tmp/rogue-client.crt

# Try to connect with the rogue cert — should be rejected
curl -k \
     --cert /tmp/rogue-client.crt \
     --key /tmp/rogue-client.key \
     https://localhost:443/api2/hello
```

Expected: Connection rejected — cert is valid structurally but not signed by
our trusted CA. Traefik 2 refuses it.

---

## How the Traefik Configuration Works

### Traefik 1 — The mTLS Client Side

The key configuration is `serversTransport` in `traefik1/dynamic/config.yml`:

```yaml
http:
  serversTransports:
    traefik2-mtls-transport:
      serverName: "traefik2"          # must match CN/SAN in traefik2.crt
      certificates:
        - certFile: /certs/traefik1.crt   # OUR identity — presented to Traefik 2
          keyFile:  /certs/traefik1.key
      rootCAs:
        - /certs/ca.crt               # CA that signed Traefik 2's server cert

  services:
    traefik2-service:
      loadBalancer:
        servers:
          - url: "https://traefik2:443"
        serversTransport: traefik2-mtls-transport   # ← attach transport to service
```

`serversTransport` is Traefik's way of saying:

> "When connecting to this upstream service, use THIS TLS configuration."

Without it, Traefik would make a plain HTTP or standard TLS connection.
With it, Traefik presents the client certificate on every outbound connection.

---

### Traefik 2 — The mTLS Server Side

The key configuration is `tls.options` in `traefik2/dynamic/config.yml`:

```yaml
tls:
  options:
    default:
      minVersion: VersionTLS12
      clientAuth:
        caFiles:
          - /certs/ca.crt                    # CA that signed Traefik 1's cert
        clientAuthType: RequireAndVerifyClientCert  # ← the mTLS enforcement
```

And it's attached to the router:

```yaml
routers:
  api2-router:
    rule: "PathPrefix(`/api2`)"
    entryPoints:
      - websecure
    service: api2-service
    tls: {}     # enable TLS on this router — fallback to `default` TLS options 
                # happens implicitly because no named TLS option is specified.
      
```

`clientAuthType: RequireAndVerifyClientCert` means:

1. **Require** — reject any connection that doesn't present a client cert
2. **Verify** — reject any cert not signed by the CAs listed in `caFiles`
   Both conditions must be satisfied — otherwise the connection is dropped at the TLS handshake.

Since we're connecting to localhost, there's no domain name and therefore no SNI (Server Name Indication — the mechanism TLS uses to identify which hostname the client is connecting to). Traefik falls back to the `default` TLS options when no SNI match is found — so by naming our options `default`, we ensure mTLS is enforced for all connections regardless of hostname.

In Prod we would have a real domain name like api2.mycompany.com, so Traefik can use SNI to match TLS options. A named TLS option will be used instead:
Prod dynamic config
```yaml
tls:
  options:
    mtls-required:        # ← named, not "default"
      minVersion: VersionTLS12
      clientAuth:
        caFiles:
          - /certs/ca.crt
        clientAuthType: RequireAndVerifyClientCert
```

And the router would use a hostname-based rule:
```yaml
routers:
  api2-router:
    rule: "Host(`api2.mycompany.com`) && PathPrefix(`/api2`)"
    #         ↑ real domain = SNI available = Traefik can match TLS options correctly
```


---

### Why the Spring Boot Apps Need No TLS Code

This is the elegant part. The Spring Boot apps sit **inside** the proxy boundary:

```
External World          Proxy Layer                 Internal Network
──────────────  │  ─────────────────────────  │  ─────────────────
                │                             │
 curl (mTLS) ──►│──► Traefik 2 ─────────────►│──► API 2 (HTTP)
                │    (TLS termination here)   │
                │                             │
```

By the time a request reaches `Api2Controller.java`, it has already been:

- Verified to have a valid client certificate
- Verified that certificate was signed by a trusted CA
- Decrypted by Traefik 2

The Spring Boot app only ever sees plain HTTP. It trusts that anything
arriving was properly authenticated by Traefik 2.

This separation of concerns is exactly why Traefik (and service meshes) exist:
**security infrastructure should not be the application's responsibility.**

---

## Docker Network Isolation — The Hidden Security Layer

The Docker network setup provides an important second layer of security:

```yaml
networks:
  network-left:   # API 1 + Traefik 1 only
  network-right:  # API 2 + Traefik 2 only
  network-bridge: # Traefik 1 + Traefik 2 only (the mTLS channel)
```

**What this prevents:**

- API 1 cannot directly reach API 2 (no shared network)
- External callers cannot reach API 2 directly (no public route to network-right)
- The ONLY path from API 1 to API 2 is: Traefik 1 → network-bridge → Traefik 2

This means even if someone found a way to skip the mTLS check, they still
couldn't reach API 2 directly from outside the Docker network.

---

## Debugging Common Issues

### "unknown TLS options" error in Traefik 2 logs

```
building router handler: unknown TLS options: mtls-required@file
```

**Cause:** Traefik 2 can't find the dynamic config file.
**Fix:** Check the volume mount in docker-compose.yml:

```bash
docker exec traefik2 ls /etc/traefik/dynamic/
# Should list: config.yml
```

---

### "certificate signed by unknown authority" in Traefik 1 logs

```
x509: certificate signed by unknown authority
```

**Cause:** Traefik 1 doesn't trust Traefik 2's server certificate.
**Fix:** Verify `rootCAs` in `traefik1/dynamic/config.yml` points to `ca.crt`,
and that `ca.crt` is the same one that signed `traefik2.crt`:

```bash
openssl verify -CAfile certs/ca.crt certs/traefik2.crt
# Must output: OK
```

---

### "serverName" mismatch error

```
x509: certificate is valid for traefik2, not something-else
```

**Cause:** The `serverName` in `traefik1/dynamic/config.yml` doesn't match
the CN or SAN in `traefik2.crt`.
**Fix:** Check what's in the cert:

```bash
openssl x509 -in certs/traefik2.crt -noout -subject -text | grep -E "CN|DNS|IP"
```

The `serverName` in Traefik 1's config must exactly match one of these values.

---

### Traefik 2 returns 404 instead of forwarding to API 2

**Cause:** Router rule doesn't match the request path, or service URL is wrong.
**Fix:** Check Traefik 2 dashboard at http://localhost:8881/dashboard/ →
look at HTTP routers and verify `api2-router` is shown and its service URL
resolves to `http://api2:8082`.

---

### API 2 container not reachable from Traefik 2

**Cause:** API 2 might be on a different Docker network.
**Fix:**

```bash
docker network inspect network-right
# Should show both 'api2' and 'traefik2' as containers
```

---

## Viewing Logs

```bash
# Real-time logs from all services
docker compose logs -f

# Just Traefik access logs (shows every request + TLS status)
docker compose logs -f traefik1
docker compose logs -f traefik2

# Spring Boot app logs
docker compose logs -f api1
docker compose logs -f api2

# Check Traefik 2 access log for client cert details
docker exec traefik2 cat /var/log/traefik/access.log | tail -20
```

---

## Production Considerations

This demo intentionally simplifies some things. Here is what you would
add for a real production deployment:

| Concern                | This Demo               | Production                                   |
| ---------------------- | ----------------------- | -------------------------------------------- |
| Certificate generation | Manual script           | cert-manager (Kubernetes) or Vault PKI       |
| Certificate rotation   | Manual re-run           | Automated — Traefik watches for file changes |
| Private key storage    | Plain files in `certs/` | Kubernetes Secrets / HashiCorp Vault         |
| API 2 direct port      | Exposed (8082)          | NOT exposed — only reachable via Traefik 2   |
| Traefik dashboard      | Open, no auth           | Protected or disabled                        |
| CA key storage         | In `certs/` directory   | Air-gapped, HSM-protected                    |
| Multiple replicas      | Single container        | Traefik HA with shared cert store            |

---

## Key Concepts

**Q: Why use Traefik for mTLS instead of doing it in Spring Boot?**

> Separation of concerns. Security infrastructure (certificate management,
> TLS negotiation, CA verification) is an operational concern, not an
> application concern. When mTLS is in Traefik, you can rotate certificates,
> change CA trust, or add new services without touching or redeploying
> application code. It also means developers don't need PKI expertise to
> write business logic.

**Q: What is `RequireAndVerifyClientCert` vs just `RequireAnyClientCert`?**

> `RequireAnyClientCert` only checks that a certificate was presented — it
> doesn't check who signed it. A self-signed certificate from anyone would
> pass. `RequireAndVerifyClientCert` additionally verifies the certificate
> was signed by one of the CAs listed in `caFiles`. This is what makes it
> true mutual authentication — you're not just saying "show me a cert",
> you're saying "show me a cert I recognise and trust".

**Q: What is `serversTransport` in Traefik and why is it needed?**

> `serversTransport` defines how Traefik connects to an upstream service.
> By default, Traefik connects to upstreams using plain HTTP or standard
> TLS (no client cert). `serversTransport` lets you specify a client
> certificate to present, a custom CA to trust, or a server name to verify.
> It is the Traefik equivalent of configuring a Java `SSLContext` with a
> `KeyManager` (for presenting certs) and a `TrustManager` (for verifying certs).

**Q: How would certificate rotation work without downtime?**

> Traefik watches its dynamic configuration directory for file changes
> (`watch: true`). When you replace the certificate files on disk, Traefik
> picks up the new certificates within seconds and uses them for new
> connections — without restart. Existing connections continue using the
> old cert until they close naturally. In Kubernetes this is done by
> cert-manager updating the Secret that Traefik mounts as a volume.

---

## References

- [Traefik TLS Options Documentation](https://doc.traefik.io/traefik/https/tls/)
- [Traefik serversTransport Configuration](https://doc.traefik.io/traefik/routing/services/#serverstransport_1)
- [Traefik mTLS Blog Post](https://traefik.io/blog/traefik-2-tls-101-23b4fbee81f1)
- [RFC 5246 — TLS 1.2 Specification](https://datatracker.ietf.org/doc/html/rfc5246)

---
Author: Samuel Huang
