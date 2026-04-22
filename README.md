# StreamsHub OAuth Demo

A demo showing OAuth-based authentication and authorization across a Kafka streaming platform using Keycloak, Strimzi, and the StreamsHub Console.

## Scenario

An e-commerce platform processes order data. Some topics contain **personally identifiable information (PII)** -- customer names, emails, addresses -- while others contain only aggregated, anonymized events.

Two users demonstrate the access control:

| User | Role | Can see `pii.orders` | Can see `public.order-events` |
|------|------|---------------------|------------------------------|
| **Alice** (Data Analyst) | Full PII access | Yes | Yes |
| **Bob** (Business Analyst) | Public data only | No (topic is invisible) | Yes |

Authorization is enforced at **three layers**:
1. **Kafka broker** -- Keycloak Authorization Services via `KeycloakAuthorizer` controls who can produce/consume which topics
2. **StreamsHub Console** -- RBAC rules control what the UI displays per user
3. **Apicurio Registry** -- Envoy + Authorino proxy enforces per-group schema access via Keycloak Authorization Services

## Architecture

```
                         ┌──────────────┐
                         │   Keycloak   │
                         │  (kafka-oauth│
                         │    realm)    │
                         └──────┬───────┘
                                │ OIDC / Token Validation / AuthZ Services
           ┌────────────────────┼─────────────────┐
           │                    │                  │
           ▼                    ▼                  ▼
  ┌────────────┐  ┌────────────────────┐  ┌──────────────────┐
  │  StreamsHub │  │   Kafka Broker     │  │ Apicurio Registry │
  │   Console   │  │  (OAUTHBEARER +    │  │  (Envoy sidecar + │
  │  (OIDC +   │  │   KeycloakAuthz)   │  │   Authorino proxy)│
  │   RBAC)    │  └────────────────────┘  └──────────────────┘
  └─────┬──────┘          ▲                        ▲
        │ forwards        │ SASL/OAUTHBEARER       │ Bearer token
        │ user token      │                        │
        └─────────────────┘    ┌──────────┐        │
                               │  Java    │────────┘
                               │  Clients │  Avro serdes
                               │ (OAuth)  │  + schema registry
                               └──────────┘
```

When a user logs into the Console, their OIDC access token is forwarded to Kafka as a SASL/OAUTHBEARER credential. Kafka's `KeycloakAuthorizer` checks their grants in Keycloak -- alice sees all topics, bob sees only `public.*` topics.

The Java clients use Avro serialization with Apicurio Registry for schema management. All registry API calls go through the Envoy + Authorino proxy, which delegates authorization decisions to Keycloak.

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
| Apicurio Registry | `http://apicurio-registry.<minikube-ip>.nip.io` | alice / alice-password **or** bob / bob-password |
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

## Apicurio Registry Authorization Proxy

Apicurio Registry lacks native fine-grained authorization -- it only has three global roles (`sr-admin`, `sr-developer`, `sr-readonly`) with no per-artifact or per-group granularity. To enforce the same PII/public access boundary used in Kafka, the registry sits behind an **Envoy + Authorino proxy** that delegates every authorization decision to **Keycloak Authorization Services**.

### How it works

```
Browser/Client
    │
    ▼
  Envoy (:8443)
    │── /apis/* ──► Authorino (ext-authz gRPC)
    │                   │
    │                   ├─ 1. Validate JWT (Keycloak JWKS)
    │                   ├─ 2. Extract group prefix from request path
    │                   ├─ 3. Ask Keycloak: "does this user have
    │                   │     permission group:{prefix}#{scope}?"
    │                   └─ 4. Keycloak evaluates policies ──► yes/no
    │
    ├── allowed ──► Registry backend (:8080, localhost only)
    │
    └── /ui/* ────► Registry UI (:8888, no auth -- static assets)
```

Authorino constructs a permission check from the request:
- **Path** `/apis/registry/v3/groups/pii.orders/artifacts` → prefix `pii` → resource `group:pii`
- **Method** `GET` → scope `read`
- **Result**: asks Keycloak "does this user have `group:pii#read`?"

Keycloak evaluates its role-based policies and returns a yes/no decision. Authorino enforces it. The registry itself has no auth configuration -- it trusts the proxy.

### Authorization granularity: prefix-level matching

Resources in Keycloak are defined at the **topic name prefix level**, not per-topic:

| Keycloak Resource | Covers | Alice (Data Analyst) | Bob (Business Analyst) |
|-------------------|--------|---------------------|----------------------|
| `group:pii` | `pii.orders`, `pii.payments`, `pii.*` | read + write | **denied** |
| `group:public` | `public.order-events`, `public.*` | read + write | read only |

This means adding a new topic like `pii.payments` automatically inherits the PII access policy -- no Keycloak configuration changes needed.

### Design trade-offs

**Prefix-level vs per-topic authorization**

The proxy extracts the prefix from the artifact group name (everything before the first `.`) and checks permissions against a Keycloak resource named `group:{prefix}`. This is a deliberate simplification:

- **Pro: No config changes when adding topics.** A new `pii.customer-profiles` topic gets protected automatically.
- **Pro: Mirrors the Kafka authorization model**, which already uses `pii.*` and `public.*` wildcard patterns.
- **Con: No per-topic differentiation within a prefix.** You cannot grant access to `pii.orders` but deny `pii.payments` -- it's all-or-nothing per prefix.

To support per-topic authorization, you would need to either:
1. **Define per-topic resources in Keycloak** (e.g., `group:pii.orders`, `group:pii.payments`) and remove the `.split('.')[0]` from the Authorino CEL expression. This gives full granularity but requires a new Keycloak resource for each topic.
2. **Use Keycloak's UMA Protection API** with `matchingUri=true` for URI-based wildcard matching. This supports flexible patterns but requires multiple HTTP calls per request (PAT acquisition, resource lookup, ticket exchange) -- which cannot be done from Authorino's embedded OPA evaluator (see below).

**Proxy authorization vs embedded authorization**

| Aspect | Kafka (embedded) | Registry (proxy) |
|--------|-----------------|------------------|
| Authorization | `KeycloakAuthorizer` inside the broker | Envoy + Authorino external proxy |
| Denied topic visibility | **Invisible** -- absent from metadata | **Visible** -- listed but returns 403 on access |
| Granularity | Per-topic with wildcards | Per-prefix (configurable) |
| Latency | In-process | Extra network hop (Envoy → Authorino → Keycloak) |

The visibility difference is a fundamental limitation of proxy-based authorization: the proxy operates at the HTTP request level and cannot filter the contents of list responses. Bob can see that a `pii.orders` group exists in the registry, but gets 403 when trying to access it. In Kafka, `pii.orders` is absent from metadata responses entirely.

**Authorino OPA limitations**

Authorino embeds OPA as a Go library. The embedded OPA does not support `http.send` -- calls fail silently with no error message. This means Rego policies cannot make outbound HTTP requests (to Keycloak or anywhere else). All external calls must go through Authorino's native metadata evaluators (HTTP, UMA, UserInfo), with Rego limited to evaluating the fetched data. This constraint shaped the single-call `response_mode=decision` approach used here.

## How It Works

See [docs/implementation-plan.md](docs/implementation-plan.md) for the full design, including:
- Keycloak realm configuration (users, clients, Authorization Services)
- Kafka `type: custom` listener and authorizer setup
- Console OIDC and RBAC configuration
- Dual-URL hostname handling for minikube

## Project Structure

```
├── clients/                    # Java producer/consumer apps (Maven + Fabric8)
│   ├── order-producer/         # Writes PII + public Avro records via registry
│   ├── order-consumer/         # Reads and deserializes Avro from both topics
│   └── deploy/                 # Kubernetes Deployment manifests
├── components/
│   ├── keycloak/               # Keycloak deployment + realm JSON
│   ├── authorino/              # Authorino Operator install
│   ├── apicurio-registry/      # Registry + Envoy sidecar + NetworkPolicy
│   └── topics/                 # KafkaTopic resources
├── overlays/oauth/
│   ├── base/                   # Phase 1: operators + Keycloak + Authorino Operator
│   └── stack/                  # Phase 2: Kafka + Console + Registry + AuthConfig
├── scripts/
│   ├── Setup.java              # JBang script for full setup (all 3 phases)
│   └── Teardown.java           # JBang script for full teardown
└── docs/
    └── implementation-plan.md  # Full design document with rationale
```
