# Apicurio Registry with Authorino Authorization Proxy

## Context

The StreamsHub OAuth demo currently demonstrates OAuth authentication and authorization across Kafka brokers (KeycloakAuthorizer) and StreamsHub Console (OIDC + RBAC), all governed by a single Keycloak realm (`kafka-oauth`). The demo scenario has Alice (Data Analyst) seeing everything and Bob (Business Analyst) limited to `public.*` topics, with `pii.*` topics invisible at the Kafka protocol level.

Apicurio Registry was intentionally excluded from the initial demo because it lacks native fine-grained authorization — it has only 3 hard-coded roles (`sr-admin`, `sr-developer`, `sr-readonly`) applied globally with no per-artifact or per-group granularity.

This design adds Apicurio Registry to the demo with **Authorino as a reverse proxy** enforcing fine-grained authorization via **Keycloak Authorization Services (UMA)**. This extends the existing Keycloak authorization model to a third service, demonstrating how proxy-based authorization can fill gaps in applications that lack native fine-grained access control.

## Architecture

### Component Topology

```
                         Keycloak (kafka-oauth realm)
                    ┌────────────────────────────────────┐
                    │  kafka client (AuthZ Services)     │
                    │  ├── Topic resources               │
                    │  └── Policies/Permissions           │
                    │                                    │
                    │  apicurio-registry client (AuthZ)  │  ← NEW
                    │  ├── Artifact group resources       │
                    │  └── Policies/Permissions           │
                    │                                    │
                    │  apicurio-registry-ui client       │  ← NEW
                    │  └── Public OIDC for browser login  │
                    └──────────┬─────────────────────────┘
                               │
          ┌────────────────────┼─────────────────────┐
          ▼                    ▼                      ▼
   StreamsHub Console     Kafka Broker         Apicurio Registry  ← NEW
   (OIDC + RBAC)        (OAUTHBEARER +        (behind Envoy +
                         KeycloakAuthorizer)    Authorino proxy)
```

### Request Flow

For API calls (client apps or browser UI):

1. Client presents OAuth bearer token to Envoy sidecar
2. Envoy calls Authorino via ext-authz gRPC
3. Authorino validates JWT against Keycloak JWKS endpoint
4. Authorino OPA/Rego policy executes UMA flow:
   a. Acquires PAT (Protection API Token) via `apicurio-registry` client credentials
   b. Queries Keycloak protection API to find resource matching the request URI
   c. Requests UMA permission ticket for that resource + scope
   d. Exchanges ticket for RPT (Requesting Party Token) using the user's access token
   e. Checks RPT contains a permission for the resource + scope
5. If RPT contains the permission → Envoy forwards request to Registry
6. If not → Envoy returns 403

For browser access to the Registry UI:

1. User navigates to `apicurio-registry.<ip>.nip.io`
2. Registry UI redirects to Keycloak login (OIDC authorization code flow via `apicurio-registry-ui` public client)
3. After login, the UI holds the user's access token
4. All subsequent API calls include the Bearer token and go through Envoy → Authorino

### Authorization Granularity

Authorization is enforced at the **artifact group level**, aligning with Kafka topics:

| Artifact Group | Kafka Topic | Alice (Data Analyst) | Bob (Business Analyst) |
|---------------|-------------|---------------------|----------------------|
| `pii.orders` | `pii.orders` | read + write | **denied** |
| `public.order-events` | `public.order-events` | read + write | read only |

### Visibility Limitation

Unlike Kafka's KeycloakAuthorizer (which omits denied topics from metadata responses, making them invisible), the proxy pattern provides **access control without invisibility**:

- Bob sees `pii.orders` in the group listing (the listing endpoint returns all groups)
- Bob gets 403 when attempting to access anything inside `pii.orders`
- This is a fundamental limitation of proxy-based authorization for REST APIs — the proxy operates at the HTTP request level and cannot filter response body contents

This contrast is a valuable teaching point: embedded authorization (Kafka) achieves invisibility; proxy authorization (Authorino) achieves access control.

## Keycloak Configuration

### New Client: `apicurio-registry` (Confidential)

| Field | Value |
|-------|-------|
| Client ID | `apicurio-registry` |
| Type | Confidential |
| Service Account | Enabled |
| Authorization Services | Enabled |
| Client Secret | `apicurio-registry-secret` |

Used by Authorino to query UMA resources (the "resource server" in UMA terms).

#### Authorization Resources

| Resource Name | URIs | Scopes |
|---------------|------|--------|
| `group:pii.orders` | `/apis/registry/v3/groups/pii.orders/*` | `read`, `write` |
| `group:public.order-events` | `/apis/registry/v3/groups/public.order-events/*` | `read`, `write` |

#### Policies (Role-Based, Reusing Existing Realm Roles)

| Policy | Type | Matches Role |
|--------|------|-------------|
| Data Analyst | Role | `Data Analyst` |
| Business Analyst | Role | `Business Analyst` |
| Producer | Role | `Producer` |
| Consumer | Role | `Consumer` |

#### Permissions

| Permission | Resources | Scopes | Policies |
|------------|-----------|--------|----------|
| Data Analysts: all schemas | both groups | read, write | Data Analyst |
| Business Analysts: public schemas | `group:public.order-events` | read | Business Analyst |
| Producers: write schemas | both groups | read, write | Producer |
| Consumers: read schemas | both groups | read | Consumer |

### New Client: `apicurio-registry-ui` (Public)

| Field | Value |
|-------|-------|
| Client ID | `apicurio-registry-ui` |
| Type | Public (no client secret) |
| Flow | Standard (Authorization Code) |
| Redirect URIs | `http://apicurio-registry.*.nip.io/*` |

Used by the Registry UI for browser-based OIDC login.

## Authorino AuthConfig

```yaml
apiVersion: authorino.kuadrant.io/v1beta3
kind: AuthConfig
metadata:
  name: apicurio-registry-protection
  namespace: kafka
spec:
  hosts:
  - apicurio-registry.<ip>.nip.io

  authentication:
    "keycloak":
      jwt:
        issuerUrl: http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth

  authorization:
    "uma-permissions":
      opa:
        rego: |
          package registry_authz

          keycloak_url := "http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth"

          # 1. Acquire PAT via apicurio-registry client credentials
          pat := http.send({
            "url": concat("", [keycloak_url, "/protocol/openid-connect/token"]),
            "method": "post",
            "headers": {"Content-Type": "application/x-www-form-urlencoded"},
            "raw_body": "grant_type=client_credentials&client_id=apicurio-registry&client_secret=apicurio-registry-secret"
          }).body.access_token

          # 2. Lookup resource by request URI
          resource_id := http.send({
            "url": concat("", [keycloak_url, "/authz/protection/resource_set?uri=", input.context.request.http.path]),
            "method": "get",
            "headers": {"Authorization": concat(" ", ["Bearer ", pat])}
          }).body[0]

          # 3. Map HTTP method to scope
          scope_map := {"GET": "read", "HEAD": "read", "POST": "write", "PUT": "write", "DELETE": "write"}
          scope := scope_map[input.context.request.http.method]

          # 4. Get UMA ticket
          access_token := trim_prefix(input.context.request.http.headers.authorization, "Bearer ")

          ticket := http.send({
            "url": concat("", [keycloak_url, "/authz/protection/permission"]),
            "method": "post",
            "headers": {"Authorization": concat(" ", ["Bearer ", pat]), "Content-Type": "application/json"},
            "raw_body": concat("", ["[{\"resource_id\":\"", resource_id, "\",\"resource_scopes\":[\"", scope, "\"]}]"])
          }).body.ticket

          # 5. Exchange ticket for RPT
          rpt := http.send({
            "url": concat("", [keycloak_url, "/protocol/openid-connect/token"]),
            "method": "post",
            "headers": {"Content-Type": "application/x-www-form-urlencoded"},
            "raw_body": concat("", ["grant_type=urn:ietf:params:oauth:grant-type:uma-ticket&ticket=", ticket, "&submit_request=true"]),
            "additional_headers": {"Authorization": concat(" ", ["Bearer ", access_token])}
          }).body.access_token

          # 6. Validate RPT contains the permission
          allow {
            permissions := io.jwt.decode(rpt)[1].authorization.permissions
            permissions[i].rsid == resource_id
            permissions[i].scopes[_] == scope
          }

          # Allow requests that don't target a specific group (listings, health, UI assets)
          allow {
            not resource_id
          }
        allValues: true

  response:
    success:
      headers:
        "x-keycloak-rpt":
          when:
          - selector: auth.authorization.uma-permissions.rpt
            operator: neq
            value: ""
          plain:
            expression: auth.authorization["uma-permissions"].rpt
```

The OPA/Rego policy is UMA protocol glue — all actual authorization decisions (who can access which groups with which scopes) are made by Keycloak when issuing the RPT.

Requests that don't match any Keycloak resource (e.g., `GET /apis/registry/v3/groups`, health checks) are allowed through for authenticated users — these are listing/browsing operations with no group-specific authorization. UI static assets and the OIDC callback paths should be excluded from Authorino checks entirely via Envoy route configuration (no auth required to load the UI shell or complete the OIDC redirect).

## Deployment Architecture

### Pod Layout

The Registry pod has two containers:

**Envoy sidecar** (port 8443 — exposed via Service):
- ext-authz filter pointing to Authorino gRPC service
- Routes authorized requests to localhost:8080 (Registry)
- `failure_mode_allow: false` — deny on Authorino failure

**Apicurio Registry** (port 8080 — localhost only):
- `quarkus.http.host=127.0.0.1` — binds to localhost, unreachable from outside the pod
- `APICURIO_STORAGE_KIND=mem` — in-memory storage
- Auth disabled on the backend — trusts the Envoy proxy
- UI OIDC enabled — handles browser login flow via `apicurio-registry-ui` Keycloak client

### Port Security

Two layers of defense to prevent bypassing the Envoy proxy:

1. **Localhost binding**: Registry listens on `127.0.0.1:8080`, only reachable from within the pod (by Envoy)
2. **NetworkPolicy**: Restricts pod ingress to port 8443 only — even if localhost binding were misconfigured, network-level enforcement blocks port 8080

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: apicurio-registry-ingress
  namespace: kafka
spec:
  podSelector:
    matchLabels:
      app: apicurio-registry
  ingress:
  - ports:
    - port: 8443
      protocol: TCP
  policyTypes:
  - Ingress
```

Note: Minikube's default CNI does not enforce NetworkPolicies. The policy documents intent and would be enforced in production (Calico/Cilium).

### Authorino Deployment

Deployed via the Authorino Operator:

```yaml
apiVersion: operator.authorino.kuadrant.io/v1beta1
kind: Authorino
metadata:
  name: authorino
  namespace: kafka
spec:
  replicas: 1
  clusterWide: false
  listener:
    ports:
      grpc: 50051
    tls:
      enabled: false
```

### Networking

| Path | Flow |
|------|------|
| External (browser) | Ingress → Envoy:8443 → Authorino → Registry:8080 |
| Internal (client pods) | Service:8443 → Envoy:8443 → Authorino → Registry:8080 |
| Direct to Registry | Blocked (localhost bind + NetworkPolicy) |

## Client App Changes

### Dependencies (clients/pom.xml)

```xml
<dependency>
  <groupId>io.apicurio</groupId>
  <artifactId>apicurio-registry-serdes-avro-serde</artifactId>
</dependency>
<dependency>
  <groupId>org.apache.avro</groupId>
  <artifactId>avro</artifactId>
</dependency>
```

### Producer Changes (order-producer)

- Serializer: `StringSerializer` → `AvroKafkaSerializer`
- Define Avro schema for Order records
- Auto-registration enabled (`apicurio.registry.auto-register=true`)
- Registry URL points through Envoy: `http://apicurio-registry.kafka.svc.cluster.local:8443`
- OAuth credentials: same client credentials used for Kafka (`order-producer` / `order-producer-secret`)

### Consumer Changes (order-consumer)

- Deserializer: `StringDeserializer` → `AvroKafkaDeserializer`
- Registry URL same as producer
- OAuth credentials: `order-consumer` / `order-consumer-secret`

### Registry Auth Configuration

The Apicurio serdes library supports OAuth via these properties:

```properties
apicurio.registry.url=http://apicurio-registry.kafka.svc.cluster.local:8443
apicurio.auth.service.url=http://keycloak.keycloak.svc.cluster.local:8080
apicurio.auth.realm=kafka-oauth
apicurio.auth.client-id=order-producer
apicurio.auth.client-secret=order-producer-secret
```

Service accounts authenticate to Keycloak via client credentials, then include the bearer token when calling the Registry API through Envoy.

## Setup Script Changes

### Phase Structure (Updated)

```
Phase 1 (overlays/oauth/base)
  ├── Strimzi Operator         (existing)
  ├── Console Operator         (existing)
  ├── Keycloak                 (existing, realm JSON extended)
  └── Authorino Operator       ← NEW

Phase 2 (overlays/oauth/stack)
  ├── Kafka cluster + OAuth    (existing)
  ├── Console + OIDC/RBAC      (existing)
  ├── Topics                   (existing)
  ├── Authorino CR             ← NEW
  ├── Apicurio Registry + Envoy sidecar  ← NEW
  ├── Envoy ConfigMap          ← NEW
  ├── AuthConfig CR            ← NEW
  ├── UMA credentials Secret   ← NEW
  ├── NetworkPolicy            ← NEW
  └── Registry Ingress         ← NEW

Phase 3 (clients)
  ├── Build clients            (modified: Avro + Apicurio serdes)
  └── Deploy clients           (modified: registry URL + auth env vars)
```

### Setup.java Modifications

- Phase 1: Add wait for Authorino Operator deployment
- Phase 2: Add wait for Authorino CR readiness, Registry deployment rollout
- Phase 3: No script changes needed (Maven build pulls new dependencies automatically)

## New Files

| File | Purpose |
|------|---------|
| `components/authorino/operator.yaml` | Authorino Operator deployment |
| `components/authorino/kustomization.yaml` | Kustomize component |
| `components/apicurio-registry/deployment.yaml` | Registry + Envoy sidecar pod |
| `components/apicurio-registry/envoy-configmap.yaml` | Envoy config with ext-authz filter |
| `components/apicurio-registry/service.yaml` | Service (port 8443 only) |
| `components/apicurio-registry/ingress.yaml` | Ingress for browser access |
| `components/apicurio-registry/networkpolicy.yaml` | Port security |
| `components/apicurio-registry/kustomization.yaml` | Kustomize component |
| `overlays/oauth/stack/authorino-cr.yaml` | Authorino instance CR |
| `overlays/oauth/stack/authconfig.yaml` | AuthConfig with UMA/Rego policy |
| `overlays/oauth/stack/uma-credentials-secret.yaml` | Registry client creds for UMA |

## Modified Files

| File | Changes |
|------|---------|
| `components/keycloak/realm-configmap.yaml` | Add `apicurio-registry` + `apicurio-registry-ui` clients, resources, policies, permissions |
| `overlays/oauth/base/kustomization.yaml` | Add Authorino Operator resource |
| `overlays/oauth/stack/kustomization.yaml` | Add Registry components, Authorino CR, AuthConfig, secrets |
| `clients/pom.xml` | Add Avro + Apicurio serdes dependencies |
| `clients/order-producer/` | Avro serialization, schema auto-registration, registry auth config |
| `clients/order-consumer/` | Avro deserialization, registry auth config |
| `clients/deploy/` | Add registry URL + auth env vars to Deployments |
| `scripts/Setup.java` | Add Authorino + Registry wait conditions |

## Verification

Manual verification (no test suite):

1. **Authorino + Registry running**: `kubectl get pods -n kafka` shows registry pod with 2/2 containers ready
2. **Alice UI access**: Log in as alice at `apicurio-registry.<ip>.nip.io`, browse all groups, view schemas in both `pii.orders` and `public.order-events`
3. **Bob UI access**: Log in as bob, see both groups listed, confirm 403 when clicking into `pii.orders`, confirm `public.order-events` schemas are readable
4. **Producer auto-registration**: Check producer logs for successful schema registration, verify schemas appear in Registry UI
5. **Consumer schema fetch**: Check consumer logs for successful Avro deserialization
6. **Direct port bypass blocked**: `kubectl exec` into a pod in the kafka namespace, confirm `curl apicurio-registry:8080` fails (no service on that port) and `curl apicurio-registry:8443` without a token returns 401
7. **End-to-end data flow**: Producer → Kafka (with Avro + schema) → Consumer (deserializes via registry) — all authenticated and authorized
