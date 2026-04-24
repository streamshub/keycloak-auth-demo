# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

StreamsHub OAuth Demo: a self-contained demonstration of OAuth authentication and authorization across a Kafka streaming platform. Uses Keycloak as the identity provider to control access for human users and service accounts across Kafka brokers (KeycloakAuthorizer), StreamsHub Console (OIDC + RBAC), and Java client apps (client credentials flow).

**Scenario**: E-commerce platform with PII-sensitive topics. Alice (Data Analyst) sees everything; Bob (Business Analyst) sees only `public.*` topics -- `pii.*` topics are invisible at the Kafka protocol level.

## Build & Deploy Commands

```bash
# Build client container images (fat JARs + OCI images)
mvn package docker:build -f clients/pom.xml

# With Podman (rootless)
DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock mvn package docker:build -f clients/pom.xml

# Deploy full stack (requires minikube running with ingress addon)
jbang scripts/Setup.java              # All phases: operators, Keycloak, Kafka, Console, clients
jbang scripts/Setup.java --skip-infra # Skip infrastructure, only rebuild/deploy clients

# Tear down
jbang scripts/Teardown.java

# Render kustomize output (useful for debugging patches)
kubectl kustomize overlays/oauth/base
kubectl kustomize overlays/oauth/stack

# View client logs
kubectl logs -f deployment/order-producer -n kafka
kubectl logs -f deployment/order-consumer -n kafka
```

No test suite exists. Verification is manual: check logs, log in as alice/bob in Console, confirm topic visibility.

## Architecture

Three-layer authorization enforced by a single Keycloak realm (`kafka-oauth`):

1. **Kafka broker** (primary enforcement): `type: custom` OAUTHBEARER listener with `KeycloakAuthorizer`. Token validation via JWKS endpoint. Authorization grants checked against Keycloak Authorization Services resources/policies/permissions.
2. **StreamsHub Console** (UI filtering): OIDC login, group-based RBAC mapping (`/data-analysts` -> full access, `/business-analysts` -> `public.*` only). Forwards user OIDC tokens to Kafka as SASL/OAUTHBEARER credentials.
3. **Apicurio Registry** (schema access): Envoy + Authorino proxy enforces per-group schema access via Keycloak Authorization Services. Prefix-level matching (`group:pii`, `group:public`) so new topics inherit policies automatically.

Service accounts (`order-producer`, `order-consumer`) authenticate via client credentials OAuth flow using `strimzi-kafka-oauth-client`.

## Key Design Decisions

- **`type: custom` not `type: oauth`**: Uses explicit callback handler class names instead of the deprecated shorthand types.
- **Remote kustomize refs**: Base/stack overlays reference `github.com/streamshub/developer-quickstart` components directly -- no local quickstart clone needed.
- **Nip.io DNS**: `*.<ip>.nip.io` wildcard avoids `/etc/hosts` edits. The IP is auto-detected from `minikube ip` at deploy time (falls back to `127.0.0.1` for docker driver). Override with `NIP_IO_IP` env var.
- **Dual Keycloak URLs**: External hostname for browser redirects (`KC_HOSTNAME`), internal service DNS for server-side token validation (`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`).
- **Topic-level invisibility**: Bob has zero Keycloak grants on `pii.*` resources, so topics are absent from metadata responses entirely.
- **TLS via cert-manager**: Self-signed CA issuer chain provides HTTPS for all ingresses. Required because Apicurio Registry UI uses PKCE, which needs `crypto.subtle` (HTTPS-only).
- **Apicurio Registry authorization proxy**: Envoy + Authorino proxy delegates authorization to Keycloak Authorization Services since Registry lacks native fine-grained auth.
- **Prefix-level authorization**: Registry resources use topic name prefixes (`group:pii`, `group:public`) rather than per-topic resources, so new topics automatically inherit policies.

## Repository Layout

- `clients/` -- Java 21 Maven project. Parent POM at `clients/pom.xml`. Modules: `common`, `order-producer`, `order-consumer`. Uses maven-shade-plugin (fat JARs) + Fabric8 docker-maven-plugin (OCI images on `eclipse-temurin:21-jre-alpine`).
- `clients/common/` -- Shared utilities module (TopicGroupStrategy for Apicurio Registry artifact grouping).
- `clients/deploy/` -- Kubernetes Deployments for the client pods (kustomize).
- `components/keycloak/` -- Keycloak deployment manifests + realm JSON ConfigMap. The realm JSON in `realm-configmap.yaml` defines all users, clients, roles, groups, authorization resources, policies, and permissions.
- `components/authorino/` -- Authorino Operator kustomization wrapper (remote install).
- `components/apicurio-registry/` -- Registry deployment (3-container pod: Envoy + Registry + UI), ingress, Envoy config, NetworkPolicy.
- `components/cert-manager/` -- Self-signed CA issuer chain for TLS on all ingresses.
- `components/topics/` -- KafkaTopic CRs (`pii.orders`, `public.order-events`).
- `overlays/oauth/base/` -- Kustomize overlay for Phase 1 (operators + Keycloak + Authorino Operator + cert-manager).
- `overlays/oauth/stack/` -- Kustomize overlay for Phase 2 (Kafka + Console + Registry + AuthConfig + OAuth patches).
- `scripts/Setup.java` -- JBang script for full demo setup (all 3 phases). Supports `--skip-infra` to only rebuild/deploy clients.
- `scripts/Teardown.java` -- JBang script for full teardown (reverse order).
- `docs/implementation-plan.md` -- Full design document with rationale for all decisions.

## Key Versions

- Java 21, Maven 3.9+
- Kafka clients 3.9.0, Strimzi OAuth 0.16.0
- Keycloak 26.2.4
- Apicurio Registry 3.2.1, Avro 1.12.0
- cert-manager v1.20.2, Envoy v1.32
- Minikube with ingress addon, 6GB RAM, 4 CPUs
