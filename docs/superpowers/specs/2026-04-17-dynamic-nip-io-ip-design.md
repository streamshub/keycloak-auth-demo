# Dynamic nip.io IP Detection

## Context

All hostnames in the demo are hardcoded to `*.127.0.0.1.nip.io`. This only works with minikube's `docker` driver (where the cluster shares the host network). With `kvm2`, `virtualbox`, or `hyperkit` drivers, the minikube VM runs at a different IP (e.g., `192.168.39.164`), making the console and Keycloak unreachable via browser.

The goal is to auto-detect the correct IP at deploy time so the demo works on any minikube driver, while keeping YAML files readable with `127.0.0.1` as the default.

## Design

### IP Detection Logic

Used by both `install.sh` and `SetupDemo.java`:

1. If `NIP_IO_IP` env var is set → use it (manual override for non-minikube clusters)
2. Else if minikube driver is `docker` → use `127.0.0.1`
3. Else → use output of `minikube ip`
4. If `minikube ip` fails → fall back to `127.0.0.1`

The docker-driver special case exists because `minikube ip` on docker returns a docker-network IP (e.g., `192.168.49.2`), but localhost actually works and is simpler.

### Substitution in install.sh

YAML files keep `127.0.0.1` as the readable default. At deploy time, `install.sh` renders kustomize output and pipes through `sed`:

```bash
kubectl kustomize overlays/oauth/base \
  | sed "s/127\.0\.0\.1\.nip\.io/${NIP_IO_IP}.nip.io/g" \
  | kubectl apply --server-side -f -
```

Same pattern for Phase 2 (`overlays/oauth/stack`). The sed operates on rendered output, catching all occurrences including the Keycloak realm JSON embedded inside ConfigMap YAML.

### SetupDemo.java

Add a `detectNipIoIp()` method using the same logic (check env var, then minikube driver, then minikube ip). Use the detected IP in printed URLs. No changes to client deployment — clients use internal cluster DNS.

### Documentation

Update `README.md` and `CLAUDE.md` to document auto-detection and the `NIP_IO_IP` override.

## Files Modified

| File | Change |
|------|--------|
| `install.sh` | Add IP detection function, change `kubectl apply -k` to render + sed + apply, use dynamic IP in output messages |
| `scripts/SetupDemo.java` | Add `detectNipIoIp()` method, use dynamic IP in output messages |
| `README.md` | Document auto-detection and `NIP_IO_IP` override |
| `CLAUDE.md` | Update nip.io design decision bullet |

## What Stays Unchanged

- All YAML/kustomize files keep `127.0.0.1` as the default (no placeholders)
- `uninstall.sh` — deletes by namespace/name, no hostnames
- Client deployments — use internal cluster DNS, not nip.io

## Verification

1. Deploy with docker driver: hostnames should resolve to `127.0.0.1`
2. Deploy with kvm2 driver: hostnames should use `minikube ip` output
3. Deploy with `NIP_IO_IP=10.0.0.5`: hostnames should use the override
4. `curl -sf http://keycloak.${IP}.nip.io/realms/kafka-oauth` returns 200
5. Console accessible at `http://console.${IP}.nip.io` in browser
