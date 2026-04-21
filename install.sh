#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIMEOUT="${TIMEOUT:-300s}"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

detect_nip_io_ip() {
    if [ -n "${NIP_IO_IP:-}" ]; then
        echo "$NIP_IO_IP"
        return
    fi

    local driver
    driver=$(minikube profile list -o json 2>/dev/null \
        | grep -o '"Driver":"[^"]*"' | head -1 | cut -d'"' -f4) || true

    if [ "$driver" = "docker" ]; then
        echo "127.0.0.1"
    else
        minikube ip 2>/dev/null || echo "127.0.0.1"
    fi
}

wait_for_deployment() {
    local ns="$1" name="$2"
    info "Waiting for deployment ${name} in ${ns}..."
    kubectl rollout status deployment/"${name}" -n "${ns}" --timeout="${TIMEOUT}"
}

wait_for_condition() {
    local ns="$1" kind="$2" name="$3" condition="${4:-Ready}"
    info "Waiting for ${kind}/${name} in ${ns} to be ${condition}..."
    kubectl wait "${kind}/${name}" -n "${ns}" --for="condition=${condition}" --timeout="${TIMEOUT}"
}

# --- Prerequisites ---
info "Checking prerequisites..."

if ! command -v kubectl &>/dev/null; then
    error "kubectl is required but not found"
    exit 1
fi

if ! kubectl cluster-info &>/dev/null; then
    error "Cannot connect to Kubernetes cluster"
    exit 1
fi

# --- Wait for ingress controller webhook ---
if kubectl get validatingwebhookconfiguration ingress-nginx-admission &>/dev/null; then
    info "Waiting for ingress controller webhook to be ready..."
    kubectl wait pod -n ingress-nginx -l app.kubernetes.io/component=controller \
        --for=condition=Ready --timeout="${TIMEOUT}"
fi

# --- Detect nip.io IP ---
NIP_IO_IP=$(detect_nip_io_ip)
info "Using nip.io IP: ${NIP_IO_IP}"

# --- Phase 1: Operators + Keycloak ---
info "=== Phase 1: Deploying operators and Keycloak ==="
kubectl kustomize "${SCRIPT_DIR}/overlays/oauth/base" \
    | sed "s/127\.0\.0\.1\.nip\.io/${NIP_IO_IP}.nip.io/g" \
    | kubectl apply --server-side -f -

wait_for_deployment strimzi strimzi-cluster-operator
wait_for_deployment streamshub-console streamshub-console-operator
wait_for_deployment keycloak keycloak

info "Phase 1 complete: operators and Keycloak are ready"

# --- Configure Keycloak: add 'groups' client scope ---
# The realm JSON intentionally omits 'clientScopes' so Keycloak creates the
# built-in defaults (email, profile, roles, etc.). The custom 'groups' scope
# must be added post-import via the admin API.
info "Configuring Keycloak 'groups' client scope..."

KC_POD="deployment/keycloak"
KC_NS="keycloak"
KCADM="kubectl exec -n ${KC_NS} ${KC_POD} -- /opt/keycloak/bin/kcadm.sh"

${KCADM} config credentials --server http://localhost:8080 \
    --realm master --user admin --password admin 2>/dev/null

SCOPE_ID=$(${KCADM} get client-scopes -r kafka-oauth \
    --fields id,name --format csv --noquotes 2>/dev/null \
    | grep ",groups$" | cut -d',' -f1) || true

if [ -z "${SCOPE_ID}" ]; then
    SCOPE_ID=$(${KCADM} create client-scopes -r kafka-oauth \
        -s name=groups -s protocol=openid-connect \
        -s 'attributes={"include.in.token.scope":"true","display.on.consent.screen":"false"}' \
        -i 2>/dev/null)

    ${KCADM} create "client-scopes/${SCOPE_ID}/protocol-mappers/models" -r kafka-oauth \
        -s name=groups -s protocol=openid-connect \
        -s protocolMapper=oidc-group-membership-mapper \
        -s consentRequired=false \
        -s 'config={"full.path":"true","id.token.claim":"true","access.token.claim":"true","claim.name":"groups","userinfo.token.claim":"true"}' \
        2>/dev/null
    info "Created 'groups' client scope"
else
    info "'groups' client scope already exists"
fi

CLIENT_UUID=$(${KCADM} get clients -r kafka-oauth \
    -q clientId=streamshub-console --fields id --format csv --noquotes 2>/dev/null)

${KCADM} update "clients/${CLIENT_UUID}/default-client-scopes/${SCOPE_ID}" \
    -r kafka-oauth 2>/dev/null

info "Assigned 'groups' scope to streamshub-console client"

# --- Phase 2: Operands with OAuth ---
info "=== Phase 2: Deploying Kafka, Console, and topics ==="
kubectl kustomize "${SCRIPT_DIR}/overlays/oauth/stack" \
    | sed "s/127\.0\.0\.1\.nip\.io/${NIP_IO_IP}.nip.io/g" \
    | kubectl apply -f -

info "Waiting for Kafka cluster to be ready (this may take a few minutes)..."
kubectl wait kafka/dev-cluster -n kafka --for="condition=Ready" --timeout="${TIMEOUT}" 2>/dev/null || \
    warn "Kafka readiness check timed out - it may still be starting"

info "Waiting for Console to be ready..."
kubectl wait console/streamshub-console -n streamshub-console --for="condition=Ready" --timeout="${TIMEOUT}" 2>/dev/null || \
    warn "Console readiness check timed out - it may still be starting"

# OIDC tokens (with Keycloak Authorization Services grants) exceed nginx's
# default 4KB proxy buffer, causing 502 on the auth callback.
kubectl annotate ingress streamshub-console-console-ingress -n streamshub-console \
    nginx.ingress.kubernetes.io/proxy-buffer-size=16k \
    --overwrite 2>/dev/null || true

info "=== Deployment complete ==="
echo ""
info "Next steps:"
info "  1. Build and deploy client apps:  jbang scripts/SetupDemo.java"
info "  2. Start minikube tunnel:         minikube tunnel"
info "  3. Open Console:                  https://console.${NIP_IO_IP}.nip.io"
info "     - Alice (PII access):          alice / alice-password"
info "     - Bob (public only):           bob / bob-password"
info "  4. Keycloak admin:                http://keycloak.${NIP_IO_IP}.nip.io"
info "     - Admin credentials:           admin / admin"
