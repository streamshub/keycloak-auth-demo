# Apicurio Registry + Authorino Authorization Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Apicurio Registry to the StreamsHub OAuth demo with fine-grained, per-artifact-group authorization enforced by Authorino proxy and Keycloak Authorization Services (UMA).

**Architecture:** Envoy sidecar + Authorino ext-authz in front of Apicurio Registry (backend + UI in one pod). OPA/Rego policy implements UMA RPT flow — Keycloak makes all authorization decisions. Clients use Avro serialization through the registry with auto-registration.

**Tech Stack:** Apicurio Registry 3.x, Authorino (Kuadrant), Envoy Proxy, Keycloak 26.x Authorization Services, Avro, Apicurio SerDes 3.x

**Design Spec:** `docs/superpowers/specs/2026-04-22-apicurio-registry-authorino-design.md`

---

## Important Notes

- **No test suite exists.** Verification is manual: check logs, browse UIs, confirm authorization behavior.
- **Apicurio Registry 3.x ships backend and UI as separate container images.** Both run in the same pod behind Envoy.
- **The serdes library fetches schemas by global ID** (`/apis/registry/v3/ids/globalIds/{id}`). These paths don't contain group names, so Authorino allows them for any authenticated user. This is acceptable — Bob can't discover PII schema IDs because he can't browse the `pii.orders` group or consume from the `pii.orders` topic.

---

## Task 1: Authorino Operator Component

**Files:**
- Create: `components/authorino/kustomization.yaml`
- Create: `components/authorino/namespace.yaml`

The Authorino Operator is installed via a remote install script that creates CRDs and a deployment. For kustomize integration, we wrap the install as a remote resource.

- [ ] **Step 1: Create the Authorino component directory and kustomization**

```yaml
# components/authorino/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - namespace.yaml
  - https://raw.githubusercontent.com/Kuadrant/authorino-operator/main/config/deploy/manifests.yaml
```

```yaml
# components/authorino/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: authorino-operator
```

- [ ] **Step 2: Verify kustomize renders correctly**

Run: `kubectl kustomize components/authorino/`

Expected: YAML output containing Authorino Operator CRDs (AuthConfig, Authorino), ServiceAccount, Deployment, ClusterRoles, and the namespace.

If the remote URL fails, fall back to: `curl -sL https://raw.githubusercontent.com/Kuadrant/authorino-operator/main/utils/install.sh | bash -s` integrated into Setup.java phase 1 instead. In that case, remove the remote resource from kustomization and add the install command to Setup.java.

- [ ] **Step 3: Commit**

```bash
git add components/authorino/
git commit -m "feat: add Authorino Operator kustomize component"
```

---

## Task 2: Apicurio Registry Component

**Files:**
- Create: `components/apicurio-registry/kustomization.yaml`
- Create: `components/apicurio-registry/deployment.yaml`
- Create: `components/apicurio-registry/envoy-configmap.yaml`
- Create: `components/apicurio-registry/service.yaml`
- Create: `components/apicurio-registry/ingress.yaml`
- Create: `components/apicurio-registry/networkpolicy.yaml`

### Step 1: Create kustomization.yaml

- [ ] **Create `components/apicurio-registry/kustomization.yaml`**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: kafka

resources:
  - deployment.yaml
  - envoy-configmap.yaml
  - service.yaml
  - ingress.yaml
  - networkpolicy.yaml
```

### Step 2: Create the 3-container Deployment

- [ ] **Create `components/apicurio-registry/deployment.yaml`**

Three containers in one pod: Envoy (front door, port 8443), Registry backend (localhost:8080), Registry UI (localhost:8888).

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: apicurio-registry
  namespace: kafka
  labels:
    app: apicurio-registry
spec:
  replicas: 1
  selector:
    matchLabels:
      app: apicurio-registry
  template:
    metadata:
      labels:
        app: apicurio-registry
    spec:
      containers:
        - name: envoy
          image: envoyproxy/envoy:v1.32-latest
          ports:
            - containerPort: 8443
              name: http
          volumeMounts:
            - name: envoy-config
              mountPath: /etc/envoy
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 200m
              memory: 128Mi

        - name: registry
          image: apicurio/apicurio-registry:3.0.6
          env:
            - name: APICURIO_STORAGE_KIND
              value: mem
            - name: QUARKUS_HTTP_HOST
              value: "127.0.0.1"
            - name: APICURIO_REST_MUTABILITY_ARTIFACT_VERSION_CONTENT_ENABLED
              value: "true"
          resources:
            requests:
              cpu: 200m
              memory: 512Mi
            limits:
              cpu: 500m
              memory: 512Mi

        - name: registry-ui
          image: apicurio/apicurio-registry-ui:3.0.6
          env:
            - name: REGISTRY_API_URL
              value: "http://apicurio-registry.127.0.0.1.nip.io/apis/registry/v3"
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 100m
              memory: 128Mi

      volumes:
        - name: envoy-config
          configMap:
            name: apicurio-envoy-config
```

Key decisions:
- `QUARKUS_HTTP_HOST=127.0.0.1`: Backend binds to localhost only — unreachable from outside the pod
- `REGISTRY_API_URL`: Points to the external URL (through Envoy). The UI is a React SPA — browser makes API calls to this URL. The `127.0.0.1.nip.io` placeholder is replaced by Setup.java at deploy time.
- No auth config on the Registry — Envoy + Authorino handle it externally
- Registry UI image has no auth config — OIDC login will be configured via env vars (added in Task 7)

### Step 3: Create the Envoy ConfigMap

- [ ] **Create `components/apicurio-registry/envoy-configmap.yaml`**

Envoy routes: `/apis/*` → backend (with ext-authz), `/ui/*` and `/` → UI (no ext-authz).

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: apicurio-envoy-config
  namespace: kafka
data:
  envoy.yaml: |
    static_resources:
      listeners:
        - name: main
          address:
            socket_address:
              address: 0.0.0.0
              port_value: 8443
          filter_chains:
            - filters:
                - name: envoy.filters.network.http_connection_manager
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: registry
                    codec_type: AUTO
                    route_config:
                      name: local_route
                      virtual_hosts:
                        - name: registry
                          domains: ["*"]
                          routes:
                            - match:
                                prefix: "/apis/"
                              route:
                                cluster: registry-backend
                            - match:
                                prefix: "/ui/"
                              route:
                                cluster: registry-ui
                              typed_per_filter_config:
                                envoy.filters.http.ext_authz:
                                  "@type": type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute
                                  disabled: true
                            - match:
                                prefix: "/"
                              route:
                                cluster: registry-ui
                              typed_per_filter_config:
                                envoy.filters.http.ext_authz:
                                  "@type": type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute
                                  disabled: true
                    http_filters:
                      - name: envoy.filters.http.ext_authz
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz
                          grpc_service:
                            envoy_grpc:
                              cluster_name: authorino
                            timeout: 5s
                          transport_api_version: V3
                          failure_mode_allow: false
                      - name: envoy.filters.http.router
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router

      clusters:
        - name: registry-backend
          type: STATIC
          connect_timeout: 1s
          load_assignment:
            cluster_name: registry-backend
            endpoints:
              - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: 8080

        - name: registry-ui
          type: STATIC
          connect_timeout: 1s
          load_assignment:
            cluster_name: registry-ui
            endpoints:
              - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: 8888

        - name: authorino
          type: STRICT_DNS
          connect_timeout: 1s
          typed_extension_protocol_options:
            envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
              "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
              explicit_http_version:
                http2_protocol_options: {}
          load_assignment:
            cluster_name: authorino
            endpoints:
              - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: authorino-authorino-authorization.kafka.svc.cluster.local
                          port_value: 50051
```

Routing logic:
- `/apis/*` → backend (ext-authz active — Authorino checks every API call)
- `/ui/*` → UI container (ext-authz disabled — static assets, no auth needed)
- `/` → UI container (ext-authz disabled — serves the SPA shell)

The Authorino cluster uses HTTP/2 (`http2_protocol_options`) because ext-authz uses gRPC.

### Step 4: Create Service, Ingress, NetworkPolicy

- [ ] **Create `components/apicurio-registry/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: apicurio-registry
  namespace: kafka
spec:
  selector:
    app: apicurio-registry
  ports:
    - name: http
      port: 8443
      targetPort: 8443
```

Only port 8443 (Envoy) is exposed. Port 8080 (backend) and 8888 (UI) are not reachable via the Service.

- [ ] **Create `components/apicurio-registry/ingress.yaml`**

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: apicurio-registry
  namespace: kafka
spec:
  ingressClassName: nginx
  rules:
    - host: apicurio-registry.127.0.0.1.nip.io
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: apicurio-registry
                port:
                  number: 8443
```

The `127.0.0.1.nip.io` placeholder is replaced by Setup.java at deploy time (same pattern as Console and Keycloak ingresses).

- [ ] **Create `components/apicurio-registry/networkpolicy.yaml`**

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

- [ ] **Step 5: Verify kustomize renders correctly**

Run: `kubectl kustomize components/apicurio-registry/`

Expected: YAML output with Deployment (3 containers), ConfigMap, Service (port 8443), Ingress, NetworkPolicy. All resources in `kafka` namespace.

- [ ] **Step 6: Commit**

```bash
git add components/apicurio-registry/
git commit -m "feat: add Apicurio Registry component with Envoy sidecar"
```

---

## Task 3: Keycloak Realm Configuration

**Files:**
- Modify: `components/keycloak/realm-configmap.yaml`

Add two new clients (`apicurio-registry` with Authorization Services, `apicurio-registry-ui` public OIDC), a service account user, and a UMA protection client role.

### Step 1: Add UMA protection client role for apicurio-registry

- [ ] **Add `apicurio-registry` to the `client` roles section (after line 42)**

In the `"roles"` → `"client"` section, add a new entry after the `"kafka"` client roles:

```json
"apicurio-registry": [
  {
    "name": "uma_protection",
    "clientRole": true
  }
]
```

### Step 2: Add service account user for apicurio-registry

- [ ] **Add service account user to the `users` array (after the `service-account-streamshub-console` entry, around line 112)**

```json
{
  "username": "service-account-apicurio-registry",
  "enabled": true,
  "serviceAccountClientId": "apicurio-registry",
  "realmRoles": ["offline_access"]
}
```

### Step 3: Add the apicurio-registry client with Authorization Services

- [ ] **Add new client to the `clients` array (after the `order-consumer` client, around line 406)**

```json
{
  "clientId": "apicurio-registry",
  "enabled": true,
  "clientAuthenticatorType": "client-secret",
  "secret": "apicurio-registry-secret",
  "bearerOnly": false,
  "consentRequired": false,
  "standardFlowEnabled": false,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": true,
  "serviceAccountsEnabled": true,
  "authorizationServicesEnabled": true,
  "publicClient": false,
  "fullScopeAllowed": true,
  "authorizationSettings": {
    "allowRemoteResourceManagement": true,
    "policyEnforcementMode": "ENFORCING",
    "decisionStrategy": "AFFIRMATIVE",
    "scopes": [
      { "name": "read" },
      { "name": "write" }
    ],
    "resources": [
      {
        "name": "group:pii.orders",
        "type": "registry-group",
        "ownerManagedAccess": false,
        "displayName": "PII orders schema group",
        "uris": ["/apis/registry/v3/groups/pii.orders/*"],
        "scopes": [
          { "name": "read" },
          { "name": "write" }
        ]
      },
      {
        "name": "group:public.order-events",
        "type": "registry-group",
        "ownerManagedAccess": false,
        "displayName": "Public order events schema group",
        "uris": ["/apis/registry/v3/groups/public.order-events/*"],
        "scopes": [
          { "name": "read" },
          { "name": "write" }
        ]
      }
    ],
    "policies": [
      {
        "name": "Data Analyst",
        "type": "role",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "roles": "[{\"id\":\"Data Analyst\",\"required\":true}]"
        }
      },
      {
        "name": "Business Analyst",
        "type": "role",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "roles": "[{\"id\":\"Business Analyst\",\"required\":true}]"
        }
      },
      {
        "name": "Producer",
        "type": "role",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "roles": "[{\"id\":\"Producer\",\"required\":true}]"
        }
      },
      {
        "name": "Consumer",
        "type": "role",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "roles": "[{\"id\":\"Consumer\",\"required\":true}]"
        }
      },
      {
        "name": "Data Analysts: all schema groups",
        "type": "resource",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "resources": "[\"group:pii.orders\",\"group:public.order-events\"]",
          "applyPolicies": "[\"Data Analyst\"]"
        }
      },
      {
        "name": "Business Analysts: public schemas only",
        "type": "scope",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "resources": "[\"group:public.order-events\"]",
          "scopes": "[\"read\"]",
          "applyPolicies": "[\"Business Analyst\"]"
        }
      },
      {
        "name": "Producers: write all schema groups",
        "type": "resource",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "resources": "[\"group:pii.orders\",\"group:public.order-events\"]",
          "applyPolicies": "[\"Producer\"]"
        }
      },
      {
        "name": "Consumers: read all schema groups",
        "type": "scope",
        "logic": "POSITIVE",
        "decisionStrategy": "UNANIMOUS",
        "config": {
          "resources": "[\"group:pii.orders\",\"group:public.order-events\"]",
          "scopes": "[\"read\"]",
          "applyPolicies": "[\"Consumer\"]"
        }
      }
    ]
  }
}
```

### Step 4: Add the apicurio-registry-ui public client

- [ ] **Add the UI client after the `apicurio-registry` client**

```json
{
  "clientId": "apicurio-registry-ui",
  "enabled": true,
  "clientAuthenticatorType": "client-secret",
  "bearerOnly": false,
  "consentRequired": false,
  "standardFlowEnabled": true,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": true,
  "serviceAccountsEnabled": false,
  "publicClient": true,
  "fullScopeAllowed": true,
  "redirectUris": ["http://apicurio-registry.127.0.0.1.nip.io/*"],
  "webOrigins": ["http://apicurio-registry.127.0.0.1.nip.io"],
  "protocolMappers": [
    {
      "name": "Groups Mapper",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-group-membership-mapper",
      "consentRequired": false,
      "config": {
        "full.path": "true",
        "userinfo.token.claim": "true",
        "id.token.claim": "true",
        "access.token.claim": "true",
        "claim.name": "groups"
      }
    }
  ]
}
```

Note: `publicClient: true` — no client secret needed (browser SPA). The `127.0.0.1.nip.io` in redirectUris/webOrigins is replaced at deploy time by Setup.java.

- [ ] **Step 5: Verify the JSON is valid**

Run: `python3 -c "import json, yaml; data = yaml.safe_load(open('components/keycloak/realm-configmap.yaml')); json.loads(data['data']['kafka-oauth-realm.json']); print('Valid JSON')"`

Expected: `Valid JSON`

- [ ] **Step 6: Commit**

```bash
git add components/keycloak/realm-configmap.yaml
git commit -m "feat: add apicurio-registry and apicurio-registry-ui Keycloak clients with AuthZ Services"
```

---

## Task 4: Authorino CR + AuthConfig + Credentials

**Files:**
- Create: `overlays/oauth/stack/authorino-cr.yaml`
- Create: `overlays/oauth/stack/authconfig.yaml`
- Create: `overlays/oauth/stack/uma-credentials-secret.yaml`

### Step 1: Create the Authorino instance CR

- [ ] **Create `overlays/oauth/stack/authorino-cr.yaml`**

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
  oidcServer:
    tls:
      enabled: false
```

Namespace-scoped (only watches `kafka` namespace). TLS disabled for demo simplicity.

### Step 2: Create the AuthConfig with UMA/Rego policy

- [ ] **Create `overlays/oauth/stack/authconfig.yaml`**

```yaml
apiVersion: authorino.kuadrant.io/v1beta3
kind: AuthConfig
metadata:
  name: apicurio-registry-protection
  namespace: kafka
spec:
  hosts:
    - apicurio-registry.127.0.0.1.nip.io

  authentication:
    "keycloak":
      jwt:
        issuerUrl: http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth
      overrides:
        "access_token":
          expression: context.request.http.headers["authorization"].split(" ")[1]

  authorization:
    "uma-permissions":
      opa:
        rego: |
          package registry_authz

          import rego.v1

          keycloak_url := "http://keycloak.keycloak.svc.cluster.local:8080/realms/kafka-oauth"

          # Acquire PAT via apicurio-registry client credentials
          pat := http.send({
            "url": concat("", [keycloak_url, "/protocol/openid-connect/token"]),
            "method": "POST",
            "headers": {"Content-Type": "application/x-www-form-urlencoded"},
            "raw_body": "grant_type=client_credentials&client_id=apicurio-registry&client_secret=apicurio-registry-secret",
            "cache": true
          }).body.access_token

          # Lookup resource by request URI
          request_path := input.context.request.http.path
          resource_ids := http.send({
            "url": concat("", [keycloak_url, "/authz/protection/resource_set?matchingUri=true&uri=", request_path]),
            "method": "GET",
            "headers": {"Authorization": concat(" ", ["Bearer ", pat])},
            "cache": true
          }).body

          resource_id := resource_ids[0]

          # Map HTTP method to scope
          scope_map := {"GET": "read", "HEAD": "read", "POST": "write", "PUT": "write", "DELETE": "write"}
          scope := scope_map[input.context.request.http.method]

          # Get user's access token
          access_token := input.auth.identity.access_token

          # Request UMA ticket for the resource + scope
          ticket := http.send({
            "url": concat("", [keycloak_url, "/authz/protection/permission"]),
            "method": "POST",
            "headers": {
              "Authorization": concat(" ", ["Bearer ", pat]),
              "Content-Type": "application/json"
            },
            "raw_body": concat("", ["[{\"resource_id\":\"", resource_id, "\",\"resource_scopes\":[\"", scope, "\"]}]"])
          }).body.ticket

          # Exchange ticket for RPT using user's access token
          rpt_response := http.send({
            "url": concat("", [keycloak_url, "/protocol/openid-connect/token"]),
            "method": "POST",
            "headers": {
              "Authorization": concat(" ", ["Bearer ", access_token]),
              "Content-Type": "application/x-www-form-urlencoded"
            },
            "raw_body": concat("", ["grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Auma-ticket&ticket=", ticket])
          })

          rpt := rpt_response.body.access_token

          # Validate RPT contains the required permission
          allow if {
            rpt_response.status_code == 200
            permissions := io.jwt.decode(rpt)[1].authorization.permissions
            some i
            permissions[i].rsid == resource_id
            some j
            permissions[i].scopes[j] == scope
          }

          # Allow requests that don't match any registered resource (listings, health, IDs)
          allow if {
            count(resource_ids) == 0
          }
        allValues: true

  response:
    success:
      headers:
        "x-registry-rpt":
          when:
            - selector: auth.authorization.uma-permissions.rpt
              operator: neq
              value: ""
          plain:
            expression: auth.authorization["uma-permissions"].rpt
```

The `127.0.0.1.nip.io` in `hosts` is replaced by Setup.java. The `matchingUri=true` parameter on the resource lookup tells Keycloak to use URI pattern matching (wildcard support).

### Step 3: Create UMA credentials secret

- [ ] **Create `overlays/oauth/stack/uma-credentials-secret.yaml`**

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: apicurio-registry-uma-credentials
  namespace: kafka
stringData:
  clientID: apicurio-registry
  clientSecret: apicurio-registry-secret
```

### Step 4: Commit

- [ ] **Commit**

```bash
git add overlays/oauth/stack/authorino-cr.yaml overlays/oauth/stack/authconfig.yaml overlays/oauth/stack/uma-credentials-secret.yaml
git commit -m "feat: add Authorino CR, AuthConfig with UMA policy, and UMA credentials"
```

---

## Task 5: Update Kustomize Overlays

**Files:**
- Modify: `overlays/oauth/base/kustomization.yaml`
- Modify: `overlays/oauth/stack/kustomization.yaml`

### Step 1: Add Authorino Operator to the base overlay

- [ ] **Edit `overlays/oauth/base/kustomization.yaml`**

Add the Authorino component to the `resources` list (after the Keycloak line):

```yaml
resources:
  # Strimzi operator from developer-quickstart
  - https://github.com/streamshub/developer-quickstart//components/core/base/strimzi-operator?ref=main
  # Console operator from developer-quickstart
  - https://github.com/streamshub/developer-quickstart//components/core/base/streamshub-console-operator?ref=main
  # Keycloak
  - ../../../components/keycloak
  # Authorino operator
  - ../../../components/authorino
```

### Step 2: Add Registry + Authorino to the stack overlay

- [ ] **Edit `overlays/oauth/stack/kustomization.yaml`**

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
  # Apicurio Registry
  - ../../../components/apicurio-registry
  # Secrets
  - console-oidc-secret.yaml
  - uma-credentials-secret.yaml
  # Authorino instance + AuthConfig
  - authorino-cr.yaml
  - authconfig.yaml

patches:
  - path: kafka-oauth-patch.yaml
  - path: console-oauth-patch.yaml
```

### Step 3: Verify both overlays render

- [ ] **Verify base overlay**

Run: `kubectl kustomize overlays/oauth/base`

Expected: Output includes Authorino Operator resources alongside Strimzi, Console, and Keycloak.

- [ ] **Verify stack overlay**

Run: `kubectl kustomize overlays/oauth/stack`

Expected: Output includes Apicurio Registry Deployment (3 containers), Envoy ConfigMap, Service, Ingress, NetworkPolicy, Authorino CR, AuthConfig, UMA credentials Secret, alongside Kafka, Console, and Topics.

- [ ] **Step 4: Commit**

```bash
git add overlays/oauth/base/kustomization.yaml overlays/oauth/stack/kustomization.yaml
git commit -m "feat: wire Authorino and Apicurio Registry into kustomize overlays"
```

---

## Task 6: Maven Dependencies

**Files:**
- Modify: `clients/pom.xml`
- Modify: `clients/order-producer/pom.xml`
- Modify: `clients/order-consumer/pom.xml`

### Step 1: Add Avro and Apicurio SerDes to parent POM

- [ ] **Edit `clients/pom.xml`**

Add version properties (after `<slf4j.version>2.0.16</slf4j.version>`, around line 26):

```xml
<avro.version>1.12.0</avro.version>
<apicurio.registry.version>3.0.6</apicurio.registry.version>
```

Add managed dependencies (after the `slf4j-simple` dependency, around line 51):

```xml
<dependency>
    <groupId>io.apicurio</groupId>
    <artifactId>apicurio-registry-avro-serde-kafka</artifactId>
    <version>${apicurio.registry.version}</version>
</dependency>
<dependency>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro</artifactId>
    <version>${avro.version}</version>
</dependency>
```

### Step 2: Add dependencies to producer POM

- [ ] **Edit `clients/order-producer/pom.xml`**

Add after the existing `slf4j-simple` dependency (around line 32):

```xml
<dependency>
    <groupId>io.apicurio</groupId>
    <artifactId>apicurio-registry-avro-serde-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro</artifactId>
</dependency>
```

### Step 3: Add dependencies to consumer POM

- [ ] **Edit `clients/order-consumer/pom.xml`**

Add after the existing `slf4j-simple` dependency (around line 32):

```xml
<dependency>
    <groupId>io.apicurio</groupId>
    <artifactId>apicurio-registry-avro-serde-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro</artifactId>
</dependency>
```

### Step 4: Verify Maven resolves

- [ ] **Verify dependencies resolve**

Run: `mvn dependency:resolve -f clients/pom.xml -q`

Expected: No errors. All Apicurio and Avro dependencies resolved.

- [ ] **Step 5: Commit**

```bash
git add clients/pom.xml clients/order-producer/pom.xml clients/order-consumer/pom.xml
git commit -m "feat: add Avro and Apicurio Registry serdes dependencies"
```

---

## Task 7: Avro Schemas

**Files:**
- Create: `clients/order-producer/src/main/resources/avro/pii-order.avsc`
- Create: `clients/order-producer/src/main/resources/avro/public-order-event.avsc`

### Step 1: Create PII Order schema

- [ ] **Create `clients/order-producer/src/main/resources/avro/pii-order.avsc`**

```json
{
  "type": "record",
  "name": "PiiOrder",
  "namespace": "com.github.streamshub.demo",
  "fields": [
    { "name": "orderId", "type": "string" },
    { "name": "customerName", "type": "string" },
    { "name": "customerEmail", "type": "string" },
    { "name": "customerAddress", "type": "string" },
    { "name": "amount", "type": "double" },
    { "name": "timestamp", "type": "string" }
  ]
}
```

### Step 2: Create Public Order Event schema

- [ ] **Create `clients/order-producer/src/main/resources/avro/public-order-event.avsc`**

```json
{
  "type": "record",
  "name": "PublicOrderEvent",
  "namespace": "com.github.streamshub.demo",
  "fields": [
    { "name": "orderId", "type": "string" },
    { "name": "amount", "type": "double" },
    { "name": "timestamp", "type": "string" },
    { "name": "eventType", "type": "string" }
  ]
}
```

### Step 3: Copy schemas to consumer resources

- [ ] **Copy schemas to consumer module**

```bash
mkdir -p clients/order-consumer/src/main/resources/avro
cp clients/order-producer/src/main/resources/avro/*.avsc clients/order-consumer/src/main/resources/avro/
```

The consumer needs the same schemas for deserialization.

- [ ] **Step 4: Commit**

```bash
git add clients/order-producer/src/main/resources/avro/ clients/order-consumer/src/main/resources/avro/
git commit -m "feat: add Avro schemas for PII orders and public order events"
```

---

## Task 8: Update OrderProducer for Avro + Registry

**Files:**
- Modify: `clients/order-producer/src/main/java/com/github/streamshub/demo/OrderProducer.java`

Replace the full file. Key changes:
- `StringSerializer` → `AvroKafkaSerializer` for values
- Build `GenericRecord` from Avro schema instead of JSON `ObjectNode`
- Add Registry URL + OAuth config properties
- Configure artifact group ID to use topic name

- [ ] **Step 1: Rewrite OrderProducer.java**

```java
package com.github.streamshub.demo;

import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderProducer {

    static final String PII_TOPIC = "pii.orders";
    static final String PUBLIC_TOPIC = "public.order-events";

    static final String[] NAMES = {
        "Jane Smith", "Carlos Rivera", "Aisha Patel", "Liam O'Brien",
        "Mei Chen", "Fatima Al-Rashid", "Dmitri Volkov", "Priya Sharma"
    };
    static final String[] EMAILS = {
        "jane.smith@example.com", "carlos.r@example.com", "aisha.p@example.com",
        "liam.ob@example.com", "mei.chen@example.com", "fatima.ar@example.com",
        "dmitri.v@example.com", "priya.s@example.com"
    };
    static final String[] ADDRESSES = {
        "123 Main St, Springfield, IL 62701",
        "456 Oak Ave, Portland, OR 97201",
        "789 Pine Rd, Austin, TX 78701",
        "321 Elm St, Denver, CO 80201",
        "654 Maple Dr, Seattle, WA 98101",
        "987 Cedar Ln, Boston, MA 02101",
        "246 Birch Ct, Miami, FL 33101",
        "135 Willow Way, Chicago, IL 60601"
    };
    static final String[] EVENT_TYPES = {
        "ORDER_PLACED", "ORDER_CONFIRMED", "ORDER_SHIPPED", "ORDER_DELIVERED"
    };

    public static void main(String[] args) throws Exception {
        String bootstrapServers = env("BOOTSTRAP_SERVERS", "localhost:9094");
        String tokenEndpoint = env("TOKEN_ENDPOINT", "http://localhost:8080/realms/kafka-oauth/protocol/openid-connect/token");
        String clientId = env("CLIENT_ID", "order-producer");
        String clientSecret = env("CLIENT_SECRET", "order-producer-secret");
        String registryUrl = env("REGISTRY_URL", "http://localhost:8443");

        Schema piiSchema = loadSchema("avro/pii-order.avsc");
        Schema publicSchema = loadSchema("avro/public-order-event.avsc");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, AvroKafkaSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        // OAuth / SASL configuration (for Kafka)
        props.put("security.protocol", "SASL_PLAINTEXT");
        props.put("sasl.mechanism", "OAUTHBEARER");
        props.put("sasl.jaas.config",
            "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required"
            + " oauth.token.endpoint.uri=\"" + tokenEndpoint + "\""
            + " oauth.client.id=\"" + clientId + "\""
            + " oauth.client.secret=\"" + clientSecret + "\" ;");
        props.put("sasl.login.callback.handler.class",
            "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler");

        // Apicurio Registry configuration
        props.put("apicurio.registry.url", registryUrl);
        props.put("apicurio.registry.auto-register", "true");
        props.put("apicurio.registry.artifact.group-id", "${topic}");
        props.put("apicurio.auth.service.token.endpoint", tokenEndpoint);
        props.put("apicurio.auth.client.id", clientId);
        props.put("apicurio.auth.client.secret", clientSecret);

        Random random = new Random();
        AtomicInteger orderCounter = new AtomicInteger(1);

        System.out.println("Starting OrderProducer (Avro)...");
        System.out.println("  Bootstrap: " + bootstrapServers);
        System.out.println("  Registry: " + registryUrl);
        System.out.println("  Token endpoint: " + tokenEndpoint);
        System.out.println("  Client ID: " + clientId);

        try (KafkaProducer<String, GenericRecord> producer = new KafkaProducer<>(props)) {
            while (true) {
                int idx = random.nextInt(NAMES.length);
                String orderId = String.format("ORD-%05d", orderCounter.getAndIncrement());
                double amount = Math.round((10 + random.nextDouble() * 490) * 100.0) / 100.0;
                String timestamp = Instant.now().toString();
                String eventType = EVENT_TYPES[random.nextInt(EVENT_TYPES.length)];

                GenericRecord piiRecord = new GenericData.Record(piiSchema);
                piiRecord.put("orderId", orderId);
                piiRecord.put("customerName", NAMES[idx]);
                piiRecord.put("customerEmail", EMAILS[idx]);
                piiRecord.put("customerAddress", ADDRESSES[idx]);
                piiRecord.put("amount", amount);
                piiRecord.put("timestamp", timestamp);

                producer.send(new ProducerRecord<>(PII_TOPIC, orderId, piiRecord),
                    (metadata, exception) -> {
                        if (exception != null) {
                            System.err.println("Failed to send PII record: " + exception.getMessage());
                        } else {
                            System.out.println("[pii.orders] " + orderId + " -> partition " + metadata.partition());
                        }
                    });

                GenericRecord publicRecord = new GenericData.Record(publicSchema);
                publicRecord.put("orderId", orderId);
                publicRecord.put("amount", amount);
                publicRecord.put("timestamp", timestamp);
                publicRecord.put("eventType", eventType);

                producer.send(new ProducerRecord<>(PUBLIC_TOPIC, orderId, publicRecord),
                    (metadata, exception) -> {
                        if (exception != null) {
                            System.err.println("Failed to send public record: " + exception.getMessage());
                        } else {
                            System.out.println("[public.order-events] " + orderId + " -> partition " + metadata.partition());
                        }
                    });

                Thread.sleep(1000);
            }
        }
    }

    static Schema loadSchema(String resourcePath) throws Exception {
        try (InputStream is = OrderProducer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new RuntimeException("Schema not found: " + resourcePath);
            return new Schema.Parser().parse(is);
        }
    }

    static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
```

Key config: `apicurio.registry.artifact.group-id=${topic}` tells the serdes to use the Kafka topic name as the artifact group ID. This maps `pii.orders` topic → `pii.orders` artifact group, matching the Keycloak resources.

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -f clients/order-producer/pom.xml -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add clients/order-producer/
git commit -m "feat: switch OrderProducer to Avro serialization with Apicurio Registry"
```

---

## Task 9: Update OrderConsumer for Avro + Registry

**Files:**
- Modify: `clients/order-consumer/src/main/java/com/github/streamshub/demo/OrderConsumer.java`

- [ ] **Step 1: Rewrite OrderConsumer.java**

```java
package com.github.streamshub.demo;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

public class OrderConsumer {

    static final String PII_TOPIC = "pii.orders";
    static final String PUBLIC_TOPIC = "public.order-events";

    public static void main(String[] args) {
        String bootstrapServers = env("BOOTSTRAP_SERVERS", "localhost:9094");
        String tokenEndpoint = env("TOKEN_ENDPOINT", "http://localhost:8080/realms/kafka-oauth/protocol/openid-connect/token");
        String clientId = env("CLIENT_ID", "order-consumer");
        String clientSecret = env("CLIENT_SECRET", "order-consumer-secret");
        String groupId = env("GROUP_ID", "order-consumer-group");
        String registryUrl = env("REGISTRY_URL", "http://localhost:8443");

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, AvroKafkaDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // OAuth / SASL configuration (for Kafka)
        props.put("security.protocol", "SASL_PLAINTEXT");
        props.put("sasl.mechanism", "OAUTHBEARER");
        props.put("sasl.jaas.config",
            "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required"
            + " oauth.token.endpoint.uri=\"" + tokenEndpoint + "\""
            + " oauth.client.id=\"" + clientId + "\""
            + " oauth.client.secret=\"" + clientSecret + "\" ;");
        props.put("sasl.login.callback.handler.class",
            "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler");

        // Apicurio Registry configuration
        props.put("apicurio.registry.url", registryUrl);
        props.put("apicurio.registry.artifact.group-id", "${topic}");
        props.put("apicurio.auth.service.token.endpoint", tokenEndpoint);
        props.put("apicurio.auth.client.id", clientId);
        props.put("apicurio.auth.client.secret", clientSecret);

        System.out.println("Starting OrderConsumer (Avro)...");
        System.out.println("  Bootstrap: " + bootstrapServers);
        System.out.println("  Registry: " + registryUrl);
        System.out.println("  Token endpoint: " + tokenEndpoint);
        System.out.println("  Client ID: " + clientId);
        System.out.println("  Group ID: " + groupId);

        try (KafkaConsumer<String, GenericRecord> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(PII_TOPIC, PUBLIC_TOPIC));

            while (true) {
                ConsumerRecords<String, GenericRecord> records = consumer.poll(Duration.ofSeconds(1));
                records.forEach(record -> {
                    System.out.printf("[%s] key=%s value=%s%n",
                        record.topic(), record.key(), record.value());
                });
            }
        }
    }

    static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -f clients/order-consumer/pom.xml -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add clients/order-consumer/
git commit -m "feat: switch OrderConsumer to Avro deserialization with Apicurio Registry"
```

---

## Task 10: Update Client Kubernetes Deployments

**Files:**
- Modify: `clients/deploy/producer-deployment.yaml`
- Modify: `clients/deploy/consumer-deployment.yaml`

### Step 1: Add REGISTRY_URL to producer deployment

- [ ] **Edit `clients/deploy/producer-deployment.yaml`**

Add the `REGISTRY_URL` env var after `CLIENT_SECRET` (line 30):

```yaml
            - name: REGISTRY_URL
              value: http://apicurio-registry.kafka.svc.cluster.local:8443
```

### Step 2: Add REGISTRY_URL to consumer deployment

- [ ] **Edit `clients/deploy/consumer-deployment.yaml`**

Add the `REGISTRY_URL` env var after `GROUP_ID` (line 32):

```yaml
            - name: REGISTRY_URL
              value: http://apicurio-registry.kafka.svc.cluster.local:8443
```

Both clients use the internal Service DNS name (port 8443 = Envoy). Traffic goes through Authorino for authorization.

- [ ] **Step 3: Commit**

```bash
git add clients/deploy/
git commit -m "feat: add REGISTRY_URL to client deployment env vars"
```

---

## Task 11: Update Setup.java

**Files:**
- Modify: `scripts/Setup.java`

### Step 1: Add Authorino Operator wait to Phase 1

- [ ] **Edit `scripts/Setup.java`**

Add after the Keycloak rollout wait (after line 88):

```java
        runChecked("authorino operator rollout",
            "kubectl", "rollout", "status", "deployment/authorino-operator",
            "-n", "authorino-operator", "--timeout=" + TIMEOUT);
```

### Step 2: Add Authorino + Registry waits to Phase 2

- [ ] **Edit `scripts/Setup.java`**

Add after the Console readiness wait and before the ingress annotation (after line 162):

```java
        info("Waiting for Authorino to be ready...");
        runChecked("authorino rollout",
            "kubectl", "rollout", "status", "deployment/authorino", "-n", "kafka",
            "--timeout=" + TIMEOUT);

        info("Waiting for Apicurio Registry to be ready...");
        runChecked("apicurio-registry rollout",
            "kubectl", "rollout", "status", "deployment/apicurio-registry", "-n", "kafka",
            "--timeout=" + TIMEOUT);
```

### Step 3: Update printSummary

- [ ] **Edit `scripts/Setup.java`**

Add Registry URL to the summary output (around line 453, after the Keycloak admin line):

```java
        info("  4. Apicurio Registry:      http://apicurio-registry." + nipIoIp + ".nip.io");
        info("     - Uses same alice/bob credentials");
```

- [ ] **Step 4: Commit**

```bash
git add scripts/Setup.java
git commit -m "feat: add Authorino and Registry wait conditions to Setup.java"
```

---

## Task 12: Registry UI OIDC Configuration

**Files:**
- Modify: `components/apicurio-registry/deployment.yaml`

The Registry UI container needs OIDC env vars so users see a login screen.

- [ ] **Step 1: Add OIDC env vars to the registry-ui container**

Update the `registry-ui` container env section in `components/apicurio-registry/deployment.yaml`:

```yaml
        - name: registry-ui
          image: apicurio/apicurio-registry-ui:3.0.6
          env:
            - name: REGISTRY_API_URL
              value: "http://apicurio-registry.127.0.0.1.nip.io/apis/registry/v3"
            - name: REGISTRY_AUTH_TYPE
              value: "oidc"
            - name: REGISTRY_AUTH_URL
              value: "http://keycloak.127.0.0.1.nip.io/realms/kafka-oauth"
            - name: REGISTRY_AUTH_CLIENT_ID
              value: "apicurio-registry-ui"
            - name: REGISTRY_AUTH_REDIRECT_URL
              value: "http://apicurio-registry.127.0.0.1.nip.io"
```

All `127.0.0.1.nip.io` values are replaced by Setup.java at deploy time. `REGISTRY_AUTH_URL` uses the external Keycloak hostname (for browser redirects), unlike the internal URL used by backend services.

- [ ] **Step 2: Commit**

```bash
git add components/apicurio-registry/deployment.yaml
git commit -m "feat: configure Registry UI with OIDC authentication"
```

---

## Task 13: End-to-End Verification

This task describes manual verification steps. No code changes.

- [ ] **Step 1: Deploy the full stack**

```bash
jbang scripts/Setup.java
```

Wait for all phases to complete. Watch for errors in Authorino or Registry rollout.

- [ ] **Step 2: Start minikube tunnel**

```bash
minikube tunnel
```

- [ ] **Step 3: Verify Registry pod is running with 3 containers**

```bash
kubectl get pods -n kafka -l app=apicurio-registry
```

Expected: `apicurio-registry-xxxx   3/3   Running`

- [ ] **Step 4: Verify producer logs show schema auto-registration**

```bash
kubectl logs -f deployment/order-producer -n kafka
```

Expected: Initial log lines showing schema registration, then periodic `[pii.orders] ORD-xxxxx -> partition N` messages.

- [ ] **Step 5: Verify consumer logs show Avro deserialization**

```bash
kubectl logs -f deployment/order-consumer -n kafka
```

Expected: Records printed with Avro `GenericRecord` format (field names and values visible).

- [ ] **Step 6: Test Alice's access (full access)**

Open browser: `http://apicurio-registry.<ip>.nip.io`

1. Click login → Keycloak login page → alice / alice-password
2. Should see both artifact groups: `pii.orders` and `public.order-events`
3. Click into `pii.orders` → should see the PiiOrder schema
4. Click into `public.order-events` → should see the PublicOrderEvent schema

- [ ] **Step 7: Test Bob's access (restricted)**

Open incognito window: `http://apicurio-registry.<ip>.nip.io`

1. Click login → bob / bob-password
2. Should see both groups listed (proxy limitation — visible but inaccessible)
3. Click into `public.order-events` → should see schemas (read access)
4. Click into `pii.orders` → should get 403 / error (access denied by Authorino)

- [ ] **Step 8: Test direct port bypass is blocked**

```bash
kubectl exec -n kafka deployment/order-producer -- curl -s -o /dev/null -w "%{http_code}" http://apicurio-registry.kafka.svc.cluster.local:8080 2>/dev/null
```

Expected: Connection refused or empty response (port 8080 not exposed via Service, and Registry binds to localhost).

- [ ] **Step 9: Test unauthenticated API access is denied**

```bash
curl -s -o /dev/null -w "%{http_code}" http://apicurio-registry.<ip>.nip.io/apis/registry/v3/groups
```

Expected: `401` (Authorino rejects unauthenticated requests).
