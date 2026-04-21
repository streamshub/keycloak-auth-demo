#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }

info "=== Removing client applications ==="
kubectl delete -k "${SCRIPT_DIR}/clients/deploy" --ignore-not-found 2>/dev/null || true

info "=== Removing operands (Kafka, Console, topics) ==="
kubectl delete -k "${SCRIPT_DIR}/overlays/oauth/stack" --ignore-not-found 2>/dev/null || true

info "Waiting for Kafka cluster to be fully removed..."
kubectl wait kafka/dev-cluster -n kafka --for=delete --timeout=120s 2>/dev/null || true

info "=== Removing operators and Keycloak ==="
kubectl delete -k "${SCRIPT_DIR}/overlays/oauth/base" --ignore-not-found 2>/dev/null || true

info "=== Cleaning up namespaces ==="
for ns in kafka streamshub-console keycloak strimzi; do
    if kubectl get namespace "${ns}" &>/dev/null; then
        info "Deleting namespace ${ns}..."
        kubectl delete namespace "${ns}" --ignore-not-found 2>/dev/null || true
    fi
done

info "=== Uninstall complete ==="
