#!/bin/bash
# =============================================================================
# generate-certs.sh
# Generates all certificates needed for Traefik-to-Traefik mTLS.
#
#   CA          → trusted root — signs both certificates below
#   traefik2    → SERVER cert — Traefik 2 presents this to Traefik 1
#   traefik1    → CLIENT cert — Traefik 1 presents this to Traefik 2
#
# Spring Boot apps need ZERO certificates — they speak plain HTTP.
# All mTLS lives entirely at the Traefik layer.
# =============================================================================

set -e
CERTS_DIR="$(dirname "$0")"
cd "$CERTS_DIR"

echo "🔐 Generating certificates for Traefik mTLS..."
echo ""

# -----------------------------------------------------------------------------
# STEP 1: Certificate Authority (CA)
# The trusted root that signs both Traefik certs.
# -----------------------------------------------------------------------------
echo "📋 Step 1: Creating Certificate Authority..."
openssl req -x509 -newkey rsa:4096 \
  -days 3650 -nodes \
  -keyout ca.key \
  -out ca.crt \
  -subj "/C=AU/ST=Victoria/L=Melbourne/O=MyOrg/CN=MyOrg-CA"
echo "   ✅ ca.crt + ca.key"

# -----------------------------------------------------------------------------
# STEP 2: Traefik 2 Server Certificate
# Traefik 2 presents this to Traefik 1 during the TLS handshake.
# Must include SAN (Subject Alternative Name) — CN alone is not enough
# for modern TLS. The SAN must match the serverName in Traefik 1's config.
# -----------------------------------------------------------------------------
echo ""
echo "📋 Step 2: Creating Traefik 2 server certificate..."
openssl req -newkey rsa:4096 -nodes \
  -keyout traefik2.key \
  -out traefik2.csr \
  -subj "/C=AU/ST=Victoria/L=Melbourne/O=MyOrg/CN=traefik2"

cat > traefik2-ext.cnf <<EOF
[v3_req]
subjectAltName = @alt_names
[alt_names]
DNS.1 = traefik2
DNS.2 = localhost
IP.1  = 127.0.0.1
EOF

openssl x509 -req -days 365 \
  -in traefik2.csr \
  -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out traefik2.crt \
  -extensions v3_req \
  -extfile traefik2-ext.cnf
echo "   ✅ traefik2.crt + traefik2.key"

# -----------------------------------------------------------------------------
# STEP 3: Traefik 1 Client Certificate
# Traefik 1 presents this to Traefik 2 as its identity during mTLS.
# Traefik 2 verifies this cert was signed by the trusted CA.
# -----------------------------------------------------------------------------
echo ""
echo "📋 Step 3: Creating Traefik 1 client certificate..."
openssl req -newkey rsa:4096 -nodes \
  -keyout traefik1.key \
  -out traefik1.csr \
  -subj "/C=AU/ST=Victoria/L=Melbourne/O=MyOrg/CN=traefik1"

openssl x509 -req -days 365 \
  -in traefik1.csr \
  -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out traefik1.crt
echo "   ✅ traefik1.crt + traefik1.key"

# Cleanup intermediate files
rm -f *.csr *.srl *-ext.cnf

echo ""
echo "🎉 All certificates generated successfully!"
echo ""
echo "Summary:"
echo "  ca.crt        → Trusted root (mounted into BOTH Traefik instances)"
echo "  traefik2.crt  → Traefik 2 server identity"
echo "  traefik1.crt  → Traefik 1 client identity"
echo ""
echo "Verify with:"
echo "  openssl verify -CAfile ca.crt traefik2.crt"
echo "  openssl verify -CAfile ca.crt traefik1.crt"
echo "  openssl x509 -in traefik2.crt -noout -text | grep -A2 'Subject Alternative'"
