# StreamsHub OAuth Demo

A demo showing OAuth-based authentication and authorization across a Kafka streaming platform using Keycloak, Strimzi, and the StreamsHub Console.

## Scenario

An e-commerce platform processes order data. Some topics contain **personally identifiable information (PII)** -- customer names, emails, addresses -- while others contain only aggregated, anonymized events.

Two users demonstrate the access control:

| User | Role | Can see `pii.orders` | Can see `public.order-events` |
|------|------|---------------------|------------------------------|
| **Alice** (Data Analyst) | Full PII access | Yes | Yes |
| **Bob** (Business Analyst) | Public data only | No (topic is invisible) | Yes |

Authorization is enforced at **two layers**:
1. **Kafka broker** -- Keycloak Authorization Services via `KeycloakAuthorizer` controls who can produce/consume which topics
2. **StreamsHub Console** -- RBAC rules control what the UI displays per user

## Architecture

```
                      ┌──────────────┐
                      │   Keycloak   │
                      │  (kafka-oauth│
                      │    realm)    │
                      └──────┬───────┘
                             │ OIDC / Token Validation
                ┌────────────┼────────────┐
                │            │            │
                ▼            ▼            ▼
       ┌────────────┐ ┌────────────┐ ┌──────────┐
       │  StreamsHub │ │   Kafka    │ │  Java    │
       │   Console   │ │  Broker    │ │  Clients │
       │  (OIDC +   │ │ (OAUTHBR + │ │ (OAuth   │
       │   RBAC)    │ │  KC Authz) │ │  svc acct│
       └─────┬──────┘ └────────────┘ └──────────┘
             │ forwards user token        │ in-cluster
             └──────────► Kafka ◄─────────┘
```

When a user logs into the Console, their OIDC access token is forwarded to Kafka as a SASL/OAUTHBEARER credential. Kafka's `KeycloakAuthorizer` checks their grants in Keycloak -- alice sees all topics, bob sees only `public.*` topics.

## Prerequisites

- [minikube](https://minikube.sigs.k8s.io/docs/start/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [JBang](https://www.jbang.dev/download/)
- Java 21+ and Maven 3.9+
- Docker or Podman

## Quick Start

### 1. Start minikube

```bash
minikube start --memory=6144 --cpus=4
minikube addons enable ingress
```

### 2. Deploy the full stack

```bash
jbang scripts/Setup.java
```

This runs all three phases:
- **Phase 1**: Strimzi operator, Console operator, Keycloak (with realm auto-import + groups scope)
- **Phase 2**: Kafka cluster (with OAuth listener + KeycloakAuthorizer), Console (with OIDC), topics
- **Phase 3**: Build producer/consumer Java apps, load images into minikube, deploy

To rebuild and redeploy only the client apps (skipping infrastructure):
```bash
jbang scripts/Setup.java --skip-infra
```

### 3. Start minikube tunnel

In a separate terminal:
```bash
minikube tunnel
```

### 4. Access the demo

| Service | URL | Credentials |
|---------|-----|-------------|
| Console | `https://console.<minikube-ip>.nip.io` | alice / alice-password **or** bob / bob-password |
| Keycloak Admin | `http://keycloak.<minikube-ip>.nip.io` | admin / admin |

> The IP is auto-detected from `minikube ip`. To override (e.g., for a remote cluster), set `NIP_IO_IP=<ip> jbang scripts/Setup.java`.

Accept the self-signed certificate warning on first Console visit.

**As Alice**: Log in and see both `pii.orders` (with customer names, emails, addresses) and `public.order-events`.

**As Bob**: Log in and see only `public.order-events`. The `pii.orders` topic is completely invisible.

### 5. View client logs

```bash
kubectl logs -f deployment/order-producer -n kafka
kubectl logs -f deployment/order-consumer -n kafka
```

## Cleanup

```bash
jbang scripts/Teardown.java
```

## How It Works

See [docs/implementation-plan.md](docs/implementation-plan.md) for the full design, including:
- Keycloak realm configuration (users, clients, Authorization Services)
- Kafka `type: custom` listener and authorizer setup
- Console OIDC and RBAC configuration
- Dual-URL hostname handling for minikube

## Project Structure

```
├── clients/                    # Java producer/consumer apps (Maven + Fabric8)
│   ├── order-producer/         # Writes PII + public records
│   ├── order-consumer/         # Reads from both topics
│   └── deploy/                 # Kubernetes Deployment manifests
├── components/
│   ├── keycloak/               # Keycloak deployment + realm JSON
│   └── topics/                 # KafkaTopic resources
├── overlays/oauth/
│   ├── base/                   # Phase 1: operators + Keycloak (remote quickstart refs)
│   └── stack/                  # Phase 2: Kafka + Console OAuth patches
├── scripts/
│   ├── Setup.java              # JBang script for full setup (all 3 phases)
│   └── Teardown.java           # JBang script for full teardown
└── docs/
    └── implementation-plan.md  # Full design document with rationale
```
