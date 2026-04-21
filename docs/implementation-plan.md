# StreamsHub OAuth Demo - Implementation Plan

## Context

This demo shows how a Strimzi-managed Kafka cluster, the StreamsHub Console, and Kafka client applications can all communicate using OAuth via Keycloak, with fine-grained authorization based on a PII (personally identifiable information) access control scenario. The demo runs on a local minikube cluster.

**Problem it solves**: Organizations using Kafka need a single identity provider (Keycloak) to control both human users and service accounts across the entire streaming platform -- from Kafka brokers to web UIs. This demo shows how Keycloak Authorization Services provide topic-level access control, using PII data as a concrete motivator.

**Key scenario**: Two users -- Alice (Data Analyst, full PII access) and Bob (Business Analyst, no PII access). PII topics are completely invisible to Bob at both the Kafka and Console layers.

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

**Token forwarding is the keystone**: When alice or bob logs into the Console via Keycloak OIDC, the Console takes their access token and uses it as a SASL/OAUTHBEARER credential when talking to Kafka (`SaslJaasConfigCredential.forOAuthLogin()`). The Kafka broker's KeycloakAuthorizer enforces per-user authorization -- alice sees all topics; bob sees only `public.*` topics.

**Two-layer authorization**:
1. **Kafka broker** (KeycloakAuthorizer): Queries Keycloak Authorization Services to check grants per user per resource. This is the real enforcement boundary.
2. **Console RBAC** (subjects/roles/rules in Console CR): Controls what the UI displays. Prevents showing users things they can't access.

## Key Design Decisions

### 1. Standalone repo with remote quickstart references
Build in `streamshub-oauth-demo/`. Reference `streamshub-developer-quickstart` components via GitHub URLs (e.g., `https://github.com/streamshub/developer-quickstart//components/core/base/strimzi-operator?ref=main`). Anyone who clones the demo repo can deploy it without needing the quickstart repo locally. The individual sub-directories in the quickstart (`strimzi-operator/`, `streamshub-console-operator/`, `kafka/`, `streamshub-console/`) are each standalone `kind: Kustomization` and can be referenced selectively -- we skip Apicurio entirely.

### 2. Simple Keycloak Deployment (not the Operator)
Use a plain Kubernetes Deployment with `quay.io/keycloak/keycloak:26.2.4` in dev mode (`start-dev --import-realm`). The Keycloak Operator would add CRDs, a PostgreSQL dependency, and another deployment phase. Dev mode uses embedded H2 and disables TLS requirements -- appropriate for a demo.

### 3. `type: custom` for both listener auth and authorization
Both `type: oauth` (listener) and `type: keycloak` (authorization) are deprecated in Strimzi v1beta2 (CHANGELOG.md: "The Keycloak authorization (type: keycloak) has been deprecated"). Use `type: custom` with explicit class names:
- Listener: `sasl.enabled.mechanisms: OAUTHBEARER` + `JaasServerOauthValidatorCallbackHandler`
- Authorization: `authorizerClass: io.strimzi.kafka.oauth.server.authorizer.KeycloakAuthorizer`

The strimzi-kafka-oauth library is already packaged in Strimzi container images -- no custom image build needed.

### 4. Ingress + minikube tunnel + nip.io (no /etc/hosts)
Use `nip.io` wildcard DNS to avoid `/etc/hosts` entries:
- Console: `console.127.0.0.1.nip.io` → resolves to `127.0.0.1`
- Keycloak: `keycloak.127.0.0.1.nip.io` → resolves to `127.0.0.1`

With `minikube tunnel`, the NGINX ingress controller listens on `127.0.0.1`, making these hostnames work from the browser with zero local DNS config.

The Console operator sets `NEXTAUTH_URL = "https://" + hostname` (hardcoded in ConsoleIngress.java:36). Minikube's NGINX ingress serves HTTPS with a default self-signed cert, so this works with a one-time browser cert warning.

### 5. Dual-URL Keycloak hostname handling
The browser needs an external Keycloak URL for OIDC redirects; the Kafka broker and Console backend need internal URLs for token validation.
- Set `KC_HOSTNAME=http://keycloak.127.0.0.1.nip.io` on Keycloak (frontend URLs use this)
- Set `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` (backchannel endpoints use the request hostname)
- Console's `authServerUrl` uses the internal URL for backchannel operations
- Kafka broker uses internal URL for JWKS and sets `oauth.check.issuer=false`
- OIDC discovery response from internal URL returns: `authorization_endpoint` using KC_HOSTNAME (external, browser-accessible), `token_endpoint` using request hostname (internal, pod-accessible)

### 6. PII topics completely hidden from Bob
At the Kafka level: Bob's role has NO `Describe` permission on `pii.*` topics, so they don't appear in metadata responses. At the Console level: Bob's RBAC rules restrict `topics` and `topics/records` resources to `resourceNames: ["public.*"]`.

### 7. Exclude Apicurio Registry
Omit Apicurio to reduce resource usage (~500MB RAM saved) and complexity. Future extension: Apicurio integration with OAuth needs more thinking since it lacks native fine-grained AuthZ at the schema/topic level.

### 8. Java client apps with Fabric8 Maven Docker plugin
Producer and consumer are small Java applications built as container images using the Fabric8 Maven Docker plugin (`io.fabric8:docker-maven-plugin`). Works with both Docker and Podman.

Build flow:
1. `mvn package` builds jars + container images via Fabric8 plugin
2. Images land in local Docker/Podman image registry
3. JBang `Setup.java` script loads images into minikube (`minikube image load <image>`)
4. Apps deployed as Kubernetes Deployments inside the cluster

Clients use dedicated Keycloak service account clients with client credentials grant and talk to Kafka via the internal OAuth listener.

## Repository Structure

```
streamshub-oauth-demo/
├── clients/
│   ├── pom.xml                               # Parent POM (shared deps, Fabric8 plugin config)
│   ├── order-producer/
│   │   ├── pom.xml
│   │   └── src/main/java/.../OrderProducer.java
│   ├── order-consumer/
│   │   ├── pom.xml
│   │   └── src/main/java/.../OrderConsumer.java
│   └── deploy/                               # Kubernetes manifests for client apps
│       ├── kustomization.yaml
│       ├── producer-deployment.yaml
│       └── consumer-deployment.yaml
├── components/
│   ├── keycloak/
│   │   ├── kustomization.yaml                # Keycloak deployment component
│   │   ├── namespace.yaml                    # keycloak namespace
│   │   ├── deployment.yaml                   # Keycloak pod (dev mode + realm import)
│   │   ├── service.yaml                      # ClusterIP on port 8080
│   │   ├── ingress.yaml                      # keycloak.127.0.0.1.nip.io
│   │   ├── admin-secret.yaml                 # admin credentials
│   │   └── realm-configmap.yaml              # Realm JSON for import
│   └── topics/
│       ├── kustomization.yaml
│       ├── pii-orders.yaml                   # KafkaTopic: pii.orders
│       └── public-order-events.yaml          # KafkaTopic: public.order-events
├── overlays/
│   └── oauth/
│       ├── base/
│       │   └── kustomization.yaml            # Operators + Keycloak (Phase 1)
│       │                                     # References via GitHub URLs:
│       │                                     #   - streamshub/developer-quickstart strimzi-operator
│       │                                     #   - streamshub/developer-quickstart console-operator
│       │                                     #   - components/keycloak
│       └── stack/
│           ├── kustomization.yaml            # Operands + OAuth config (Phase 2)
│           │                                 # References via GitHub URLs:
│           │                                 #   - streamshub/developer-quickstart kafka
│           │                                 #   - streamshub/developer-quickstart console
│           │                                 # Plus local patches + topics
│           ├── kafka-oauth-patch.yaml        # Add oauth listener + KeycloakAuthorizer
│           ├── console-oauth-patch.yaml      # Add OIDC + RBAC + hostname
│           ├── console-oidc-secret.yaml      # Console OIDC client secret
│           └── kafka-authz-secret.yaml       # Kafka authorizer client secret
├── scripts/
│   ├── Setup.java                            # JBang: full setup (all 3 phases, --skip-infra flag)
│   └── Teardown.java                         # JBang: full teardown
└── README.md
```

## Keycloak Realm Configuration (`kafka-oauth`)

### Users
| Username | Password | Group | Role | Purpose |
|----------|----------|-------|------|---------|
| `alice` | `alice-password` | `/data-analysts` | `Data Analyst` | Can see ALL topics including PII |
| `bob` | `bob-password` | `/business-analysts` | `Business Analyst` | Can only see `public.*` topics |

### Clients
| Client ID | Type | Flows | Secret | Purpose |
|-----------|------|-------|--------|---------|
| `kafka` | Confidential | Service account | `kafka-broker-secret` | Kafka broker (AuthZ Services enabled) |
| `streamshub-console` | Confidential | Standard flow (browser) | `console-client-secret` | Console OIDC login |
| `order-producer` | Confidential | Service account | `order-producer-secret` | Producer app |
| `order-consumer` | Confidential | Service account | `order-consumer-secret` | Consumer app |

The `streamshub-console` client needs:
- `standardFlowEnabled: true` (browser login)
- `redirectUris: ["https://console.127.0.0.1.nip.io/*"]`
- Protocol mapper: `groups` claim mapper (type: `oidc-group-membership-mapper`) to emit `groups` claim in tokens for Console RBAC subject matching
- Reference: streamshub-console test realm at `api/src/test/resources/keycloak/console-realm.json`

The `kafka` client needs:
- `authorizationServicesEnabled: true`
- `serviceAccountsEnabled: true`

### Authorization Services (on `kafka` client)

**Resources** (scoped to `kafka-cluster:dev-cluster`):
| Resource Name | Type | Scopes |
|---|---|---|
| `kafka-cluster:dev-cluster,Topic:pii.*` | Topic | Create, Delete, Describe, Write, Read, Alter, DescribeConfigs, AlterConfigs |
| `kafka-cluster:dev-cluster,Topic:public.*` | Topic | Create, Delete, Describe, Write, Read, Alter, DescribeConfigs, AlterConfigs |
| `kafka-cluster:dev-cluster,Group:*` | Group | Describe, Read, Delete |
| `kafka-cluster:dev-cluster,Cluster:*` | Cluster | DescribeConfigs, AlterConfigs, ClusterAction, IdempotentWrite |

**Policies:**
| Policy Name | Type | Matches |
|---|---|---|
| `Data Analyst` | role | Realm role "Data Analyst" |
| `Business Analyst` | role | Realm role "Business Analyst" |
| `Producer` | role | Realm role "Producer" |
| `Consumer` | role | Realm role "Consumer" |

**Permissions:**
| Permission | Resources | Scopes | Policy |
|---|---|---|---|
| Data Analysts: full topic access | `Topic:pii.*`, `Topic:public.*` | ALL | Data Analyst |
| Data Analysts: full group access | `Group:*` | ALL | Data Analyst |
| Data Analysts: cluster access | `Cluster:*` | ALL | Data Analyst |
| Business Analysts: public topics only | `Topic:public.*` | ALL | Business Analyst |
| Business Analysts: group access | `Group:*` | ALL | Business Analyst |
| Business Analysts: basic cluster | `Cluster:*` | DescribeConfigs, IdempotentWrite | Business Analyst |
| Producers: write all topics | `Topic:pii.*`, `Topic:public.*` | Describe, Write, Create | Producer |
| Producers: idempotent write | `Cluster:*` | IdempotentWrite | Producer |
| Consumers: read all topics | `Topic:pii.*`, `Topic:public.*` | Describe, Read | Consumer |
| Consumers: group access | `Group:*` | ALL | Consumer |

**Key**: Bob (Business Analyst) has NO permission on `Topic:pii.*` -- not even `Describe`. This makes `pii.orders` completely invisible at the Kafka protocol level (not returned in metadata responses).

## Kafka CR Patch

**File:** `overlays/oauth/stack/kafka-oauth-patch.yaml`

Patches the existing `dev-cluster` Kafka CR from the quickstart:

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: dev-cluster
  namespace: kafka
spec:
  kafka:
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
      - name: oauth
        port: 9094
        type: internal
        tls: false
        authentication:
          type: custom
          sasl: true
          listenerConfig:
            sasl.enabled.mechanisms: OAUTHBEARER
            oauthbearer.sasl.server.callback.handler.class: io.strimzi.kafka.oauth.server.JaasServerOauthValidatorCallbackHandler
            oauthbearer.sasl.jaas.config: >-
              org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required
              oauth.jwks.endpoint.uri="http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth/protocol/openid-connect/certs"
              oauth.username.claim="preferred_username"
              oauth.check.issuer="false" ;
    authorization:
      type: custom
      authorizerClass: io.strimzi.kafka.oauth.server.authorizer.KeycloakAuthorizer
      supportsAdminApi: false
    config:
      principal.builder.class: io.strimzi.kafka.oauth.server.OAuthKafkaPrincipalBuilder
      strimzi.authorization.kafka.cluster.name: dev-cluster
      strimzi.authorization.client.id: kafka
      strimzi.authorization.token.endpoint.uri: "http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth/protocol/openid-connect/token"
      strimzi.authorization.delegate.to.kafka.acl: "true"
      strimzi.authorization.grants.refresh.period.seconds: "30"
```

Notes:
- `oauth.check.issuer=false` because tokens issued via browser flow have issuer `http://keycloak.127.0.0.1.nip.io/realms/kafka-oauth` (external) while the broker uses internal URLs
- `strimzi.authorization.delegate.to.kafka.acl=true` allows fallback to KRaft's StandardAuthorizer for inter-broker operations
- The `plain` listener is kept for internal operations / topic creation before OAuth is fully operational

## Console CR Patch

**File:** `overlays/oauth/stack/console-oauth-patch.yaml`

```yaml
apiVersion: console.streamshub.github.com/v1alpha1
kind: Console
metadata:
  name: streamshub-console
  namespace: streamshub-console
spec:
  hostname: console.127.0.0.1.nip.io
  security:
    oidc:
      authServerUrl: http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth
      clientId: streamshub-console
      clientSecret:
        valueFrom:
          secretKeyRef:
            name: console-oidc-secret
            key: client-secret
    subjects:
      - claim: groups
        include: ["/data-analysts"]
        roleNames: [data-analyst]
      - claim: groups
        include: ["/business-analysts"]
        roleNames: [business-analyst]
    roles:
      - name: data-analyst
        rules:
          - resources: [kafkas]
            privileges: [ALL]
      - name: business-analyst
        rules:
          - resources: [kafkas]
            privileges: [ALL]
  kafkaClusters:
    - name: dev-cluster
      namespace: kafka
      listener: oauth
      security:
        roles:
          - name: data-analyst
            rules:
              - resources: [topics, topics/records, topics/metrics, groups, nodes, nodes/metrics, rebalances]
                privileges: [ALL]
          - name: business-analyst
            rules:
              - resources: [topics, topics/metrics, groups, nodes/metrics, rebalances]
                resourceNames: ["public.*"]
                privileges: [GET, LIST]
              - resources: [topics/records]
                resourceNames: ["public.*"]
                privileges: [GET, LIST]
```

The Console's OIDC `authServerUrl` uses the internal Keycloak URL (backchannel). The OIDC discovery response returns the external `authorization_endpoint` (via KC_HOSTNAME) for browser redirects, and internal `token_endpoint` (via KC_HOSTNAME_BACKCHANNEL_DYNAMIC) for server-side token exchange.

## Java Client Applications

### Project Structure (`clients/`)

```
clients/
├── pom.xml                                    # Parent POM
│   ├── Properties: kafka.version, jackson.version, strimzi.oauth.version
│   ├── Shared deps: kafka-clients, jackson-databind, kafka-oauth-client
│   └── Fabric8 docker-maven-plugin config (shared)
├── order-producer/
│   ├── pom.xml                                # Module POM with image config
│   └── src/main/java/com/example/demo/
│       └── OrderProducer.java
└── order-consumer/
    ├── pom.xml
    └── src/main/java/com/example/demo/
        └── OrderConsumer.java
```

### Fabric8 Maven Docker Plugin Configuration

The parent POM configures `io.fabric8:docker-maven-plugin` with:
- Base image: `eclipse-temurin:21-jre-alpine` (small, Java 21)
- Assembly: copies the fat jar into the image
- Runs: `java -jar /app/app.jar`
- Image naming: `streamshub-oauth-demo/order-producer:latest`, `streamshub-oauth-demo/order-consumer:latest`

Build commands:
```bash
# With Docker
mvn package docker:build -f clients/pom.xml

# With Podman (set DOCKER_HOST to podman socket)
DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock mvn package docker:build -f clients/pom.xml
```

### OrderProducer.java
- Authenticates as `order-producer` service account via client credentials grant
- Configuration via environment variables: `BOOTSTRAP_SERVERS`, `TOKEN_ENDPOINT`, `CLIENT_ID`, `CLIENT_SECRET`
- Produces JSON to `pii.orders`: `{"orderId": "ORD-001", "customerName": "Jane Smith", "customerEmail": "jane@example.com", "customerAddress": "123 Main St", "amount": 59.99, "timestamp": "..."}`
- Produces JSON to `public.order-events`: `{"orderId": "ORD-001", "amount": 59.99, "timestamp": "...", "eventType": "ORDER_PLACED"}` (no PII)
- Loop: one record per second to each topic

### OrderConsumer.java
- Authenticates as `order-consumer` service account
- Same env var configuration pattern
- Subscribes to both `pii.orders` and `public.order-events`
- Prints records to stdout

### Kubernetes Deployments (`clients/deploy/`)

```yaml
# producer-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-producer
  namespace: kafka
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: producer
          image: streamshub-oauth-demo/order-producer:latest
          imagePullPolicy: Never  # Image loaded via minikube image load
          env:
            - name: BOOTSTRAP_SERVERS
              value: dev-cluster-kafka-oauth-bootstrap.kafka.svc.cluster.local:9094
            - name: TOKEN_ENDPOINT
              value: http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth/protocol/openid-connect/token
            - name: CLIENT_ID
              value: order-producer
            - name: CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: order-producer-secret
                  key: client-secret
```

## Keycloak Deployment

**File:** `components/keycloak/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
  namespace: keycloak
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: keycloak
          image: quay.io/keycloak/keycloak:26.2.4
          args: ["start-dev", "--import-realm"]
          env:
            - name: KC_BOOTSTRAP_ADMIN_USERNAME
              valueFrom:
                secretKeyRef:
                  name: keycloak-admin
                  key: username
            - name: KC_BOOTSTRAP_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: keycloak-admin
                  key: password
            - name: KC_HOSTNAME
              value: "http://keycloak.127.0.0.1.nip.io"
            - name: KC_HOSTNAME_BACKCHANNEL_DYNAMIC
              value: "true"
            - name: KC_HTTP_ENABLED
              value: "true"
            - name: KC_PROXY_HEADERS
              value: "xforwarded"
          ports:
            - containerPort: 8080
          volumeMounts:
            - name: realm-config
              mountPath: /opt/keycloak/data/import
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: 500m
              memory: 768Mi
      volumes:
        - name: realm-config
          configMap:
            name: keycloak-realm
```

**Ingress:** `components/keycloak/ingress.yaml`
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: keycloak
  namespace: keycloak
spec:
  rules:
    - host: keycloak.127.0.0.1.nip.io
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: keycloak
                port:
                  number: 8080
```

The realm JSON is mounted as a ConfigMap and auto-imported on first Keycloak boot via `--import-realm`.

## Deployment Sequence

### Phase 1: Operators + Keycloak (`overlays/oauth/base`)
```
1. kubectl apply --server-side -k overlays/oauth/base
2. Wait: Strimzi operator ready (strimzi namespace)
3. Wait: Console operator ready (streamshub-console namespace)
4. Wait: Keycloak deployment ready (keycloak namespace)
```

### Phase 2: Operands with OAuth (`overlays/oauth/stack`)
```
5. kubectl apply -k overlays/oauth/stack
6. Wait: Kafka cluster ready (kafka namespace)
7. Wait: Console ready (streamshub-console namespace)
```

### Phase 3: Client apps + Demo (Setup.java Phase 3, or --skip-infra)
```
8. Build client images: mvn package docker:build -f clients/pom.xml
9. Load images into minikube: minikube image load streamshub-oauth-demo/order-producer:latest
10. Deploy clients: kubectl apply -k clients/deploy/
11. Start minikube tunnel (background)
12. Open browser: https://console.127.0.0.1.nip.io (accept cert warning)
    - Login as alice → sees pii.orders + public.order-events
    - Login as bob → sees only public.order-events
```

## JBang Setup Script (`scripts/Setup.java`)

Orchestrates all three phases in a single invocation:
1. Checks prerequisites (kubectl, cluster connectivity)
2. Phase 1: Deploys operators + Keycloak via kustomize, configures groups scope via kcadm.sh
3. Phase 2: Deploys Kafka + Console + topics via kustomize, annotates ingress for OIDC buffer size
4. Phase 3: Detects container runtime (Docker/Podman), builds client images, loads into minikube, deploys clients
5. Verifies Keycloak is reachable
6. Prints access URLs and credentials

Use `--skip-infra` to skip Phases 1+2 and only rebuild/deploy clients.

## Minikube Requirements

```bash
minikube start --memory=6144 --cpus=4
minikube addons enable ingress
```

In a separate terminal (or backgrounded):
```bash
minikube tunnel
```

Access URLs (no /etc/hosts needed):
- Console: `https://console.127.0.0.1.nip.io` (accept self-signed cert warning)
- Keycloak admin: `http://keycloak.127.0.0.1.nip.io`

## Implementation Steps

### Step 1: Project scaffolding
Create the repo structure, top-level scripts, and kustomization wiring.
- Reference: `github.com/streamshub/developer-quickstart` install.sh for install script pattern

### Step 2: Keycloak deployment manifests
Create `components/keycloak/` with namespace, deployment, service, ingress, admin secret, realm ConfigMap.
- **Key files**: namespace.yaml, deployment.yaml, service.yaml, ingress.yaml, admin-secret.yaml, realm-configmap.yaml, kustomization.yaml

### Step 3: Keycloak realm JSON
Create the `kafka-oauth` realm with users, groups, clients, roles, authorization services resources/policies/permissions.
- **Base reference**: `strimzi-kafka-oauth/examples/docker/keycloak/realms/kafka-authz-realm.json`

### Step 4: Kafka OAuth patch
Create the Kafka CR patch adding the oauth listener and KeycloakAuthorizer.
- **Base reference**: Strimzi system test `OauthPlainST.java` lines 325-355 for `type: custom` listener config
- **File**: overlays/oauth/stack/kafka-oauth-patch.yaml

### Step 5: Console OAuth patch
Create the Console CR patch adding OIDC config, subjects, roles, and per-cluster RBAC.
- **Base reference**: `streamshub-console/examples/console/console-security-oidc.yaml`
- **Files**: overlays/oauth/stack/console-oauth-patch.yaml, console-oidc-secret.yaml

### Step 6: KafkaTopic resources
Create topic manifests for `pii.orders` and `public.order-events`.
- **Files**: components/topics/

### Step 7: Kustomize overlays
Wire up the overlay kustomization.yaml files with remote GitHub references.

**`overlays/oauth/base/kustomization.yaml`** (Phase 1: Operators + Keycloak):
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  # Strimzi operator from developer-quickstart (pinned)
  - https://github.com/streamshub/developer-quickstart//components/core/base/strimzi-operator?ref=main
  # Console operator from developer-quickstart (pinned)
  - https://github.com/streamshub/developer-quickstart//components/core/base/streamshub-console-operator?ref=main
  # Keycloak (local component)
  - ../../../components/keycloak
patches:
  # Remove Console ServiceMonitor (requires Prometheus CRD not installed in base)
  - target:
      group: monitoring.coreos.com
      version: v1
      kind: ServiceMonitor
      name: streamshub-console-operator
    patch: |-
      $patch: delete
      apiVersion: monitoring.coreos.com/v1
      kind: ServiceMonitor
      metadata:
        name: streamshub-console-operator
```

**`overlays/oauth/stack/kustomization.yaml`** (Phase 2: Operands + OAuth):
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  # Kafka cluster from developer-quickstart
  - https://github.com/streamshub/developer-quickstart//components/core/stack/kafka?ref=main
  # Console from developer-quickstart
  - https://github.com/streamshub/developer-quickstart//components/core/stack/streamshub-console?ref=main
  # Topics
  - ../../../components/topics
  # Secrets
  - console-oidc-secret.yaml
  - kafka-authz-secret.yaml
patches:
  - path: kafka-oauth-patch.yaml
  - path: console-oauth-patch.yaml
```

Note: Pin `?ref=main` to a specific release tag (e.g., `?ref=v1.0.0`) for stability once the quickstart has a suitable release.

### Step 8: Java client applications
Create the Maven multi-module project with Fabric8 Docker plugin, OrderProducer.java, OrderConsumer.java, and Kubernetes deployment manifests.
- **Files**: clients/

### Step 9: JBang setup and teardown scripts
Unified JBang scripts for setup (all 3 phases) and teardown.
- **Files**: scripts/Setup.java, scripts/Teardown.java

### Step 10: README
Document the demo setup, architecture, and walkthrough with screenshots.

## Verification

1. `curl http://keycloak.127.0.0.1.nip.io/realms/kafka-oauth/.well-known/openid-configuration` returns OIDC discovery
2. Token acquisition for alice via password grant returns a valid JWT
3. Producer pod logs show successful writes to both topics
4. Consumer pod logs show successful reads from both topics
5. Console login as alice: sees both `pii.orders` and `public.order-events`, can browse records in both
6. Console login as bob: sees only `public.order-events`, `pii.orders` is not visible at all
7. Keycloak admin UI shows realm config, clients, and authorization policies

## Critical References (for implementation)

These are local paths to reference repos for understanding patterns during implementation:

| Purpose | Local Path |
|---|---|
| Example Keycloak AuthZ realm | `strimzi-kafka-oauth/examples/docker/keycloak/realms/kafka-authz-realm.json` |
| Console OIDC example CR | `streamshub-console/examples/console/console-security-oidc.yaml` |
| Console token forwarding | `streamshub-console/api/src/main/java/.../SaslJaasConfigCredential.java` |
| Strimzi custom auth pattern | `strimzi-kafka-operator/systemtest/.../OauthPlainST.java` (lines 325-355) |
| KeycloakAuthorizer config | `strimzi-kafka-oauth/examples/docker/kafka-oauth-strimzi/compose-authz.yml` |
| Quickstart Kafka kustomization | `github.com/streamshub/developer-quickstart//components/core/stack/kafka` |
| Quickstart Console CR | `github.com/streamshub/developer-quickstart//components/core/stack/streamshub-console` |
| Quickstart install script | `github.com/streamshub/developer-quickstart/install.sh` |

## Future Extensions

- **Apicurio Registry with OAuth**: Integrate schema registry with OAuth authentication. Needs more thinking since Apicurio lacks native fine-grained AuthZ at the schema/topic level.
- **Kafka Connect**: Add a connector that moves data between PII and non-PII topics with appropriate service account permissions.
- **TLS**: Add TLS between all components for a production-like setup.
- **Metrics overlay**: Combine with the quickstart's metrics overlay for Prometheus monitoring of the OAuth-enabled stack.
