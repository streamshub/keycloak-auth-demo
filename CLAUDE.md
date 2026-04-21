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
./install.sh                      # Phase 1: operators + Keycloak; Phase 2: Kafka + Console + topics
jbang scripts/SetupDemo.java      # Phase 3: build client images, load into minikube, deploy

# Tear down
./uninstall.sh

# Render kustomize output (useful for debugging patches)
kubectl kustomize overlays/oauth/base
kubectl kustomize overlays/oauth/stack

# View client logs
kubectl logs -f deployment/order-producer -n kafka
kubectl logs -f deployment/order-consumer -n kafka
```

No test suite exists. Verification is manual: check logs, log in as alice/bob in Console, confirm topic visibility.

## Architecture

Two-layer authorization enforced by a single Keycloak realm (`kafka-oauth`):

1. **Kafka broker** (primary enforcement): `type: custom` OAUTHBEARER listener with `KeycloakAuthorizer`. Token validation via JWKS endpoint. Authorization grants checked against Keycloak Authorization Services resources/policies/permissions.
2. **StreamsHub Console** (UI filtering): OIDC login, group-based RBAC mapping (`/data-analysts` -> full access, `/business-analysts` -> `public.*` only). Forwards user OIDC tokens to Kafka as SASL/OAUTHBEARER credentials.

Service accounts (`order-producer`, `order-consumer`) authenticate via client credentials OAuth flow using `strimzi-kafka-oauth-client`.

## Key Design Decisions

- **`type: custom` not `type: oauth`**: Uses explicit callback handler class names instead of the deprecated shorthand types.
- **Remote kustomize refs**: Base/stack overlays reference `github.com/streamshub/developer-quickstart` components directly -- no local quickstart clone needed.
- **Nip.io DNS**: `*.<ip>.nip.io` wildcard avoids `/etc/hosts` edits. The IP is auto-detected from `minikube ip` at deploy time (falls back to `127.0.0.1` for docker driver). Override with `NIP_IO_IP` env var.
- **Dual Keycloak URLs**: External hostname for browser redirects (`KC_HOSTNAME`), internal service DNS for server-side token validation (`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`).
- **Topic-level invisibility**: Bob has zero Keycloak grants on `pii.*` resources, so topics are absent from metadata responses entirely.

## Repository Layout

- `clients/` -- Java 21 Maven project. Parent POM at `clients/pom.xml`. Modules: `order-producer`, `order-consumer`. Uses maven-shade-plugin (fat JARs) + Fabric8 docker-maven-plugin (OCI images on `eclipse-temurin:21-jre-alpine`).
- `clients/deploy/` -- Kubernetes Deployments for the client pods (kustomize).
- `components/keycloak/` -- Keycloak deployment manifests + realm JSON ConfigMap. The realm JSON in `realm-configmap.yaml` defines all users, clients, roles, groups, authorization resources, policies, and permissions.
- `components/topics/` -- KafkaTopic CRs (`pii.orders`, `public.order-events`).
- `overlays/oauth/base/` -- Kustomize overlay for Phase 1 (operators + Keycloak).
- `overlays/oauth/stack/` -- Kustomize overlay for Phase 2 (Kafka + Console operands + OAuth patches). Contains the critical `kafka-oauth-patch.yaml` and `console-oauth-patch.yaml`.
- `scripts/SetupDemo.java` -- JBang script for Phase 3 (detects Docker/Podman, builds images, loads into minikube, deploys clients).
- `docs/implementation-plan.md` -- Full design document with rationale for all decisions.

## Key Versions

- Java 21, Maven 3.9+
- Kafka clients 3.9.0, Strimzi OAuth 0.16.0
- Keycloak 26.2.4
- Minikube with ingress addon, 6GB RAM, 4 CPUs
