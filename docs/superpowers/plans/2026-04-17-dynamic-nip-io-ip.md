# Dynamic nip.io IP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-detect the minikube IP at deploy time so nip.io hostnames work on any minikube driver (docker, kvm2, virtualbox, etc.).

**Architecture:** YAML files keep `127.0.0.1` as the readable default. Deploy scripts (`install.sh`, `SetupDemo.java`) detect the correct IP and substitute it — via `sed` on rendered kustomize output in `install.sh`, and in printed URLs in `SetupDemo.java`. An env var `NIP_IO_IP` allows manual override.

**Tech Stack:** Bash, sed, Java 21 (JBang), kustomize

**Spec:** `docs/superpowers/specs/2026-04-17-dynamic-nip-io-ip-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `install.sh` | Modify | Add IP detection, change kustomize apply to render+sed+apply, dynamic output URLs |
| `scripts/SetupDemo.java` | Modify | Add IP detection, dynamic output URLs |
| `README.md` | Modify | Document `NIP_IO_IP` override, note auto-detection |
| `CLAUDE.md` | Modify | Update nip.io design decision bullet |

---

### Task 1: Add IP detection and dynamic apply to install.sh

**Files:**
- Modify: `install.sh`

- [ ] **Step 1: Add detect_nip_io_ip function after the color/logging setup (after line 14)**

Add this function between the logging helpers and the prerequisites section:

```bash
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
```

- [ ] **Step 2: Invoke the function and print the detected IP, right after the ingress webhook wait (after line 46)**

Add between the ingress wait block and Phase 1:

```bash
# --- Detect nip.io IP ---
NIP_IO_IP=$(detect_nip_io_ip)
info "Using nip.io IP: ${NIP_IO_IP}"
```

- [ ] **Step 3: Change Phase 1 apply from `kubectl apply -k` to render+sed+apply**

Replace:
```bash
kubectl apply --server-side -k "${SCRIPT_DIR}/overlays/oauth/base"
```

With:
```bash
kubectl kustomize "${SCRIPT_DIR}/overlays/oauth/base" \
    | sed "s/127\.0\.0\.1\.nip\.io/${NIP_IO_IP}.nip.io/g" \
    | kubectl apply --server-side -f -
```

- [ ] **Step 4: Change Phase 2 apply from `kubectl apply -k` to render+sed+apply**

Replace:
```bash
kubectl apply -k "${SCRIPT_DIR}/overlays/oauth/stack"
```

With:
```bash
kubectl kustomize "${SCRIPT_DIR}/overlays/oauth/stack" \
    | sed "s/127\.0\.0\.1\.nip\.io/${NIP_IO_IP}.nip.io/g" \
    | kubectl apply -f -
```

- [ ] **Step 5: Update the output messages at the end to use the detected IP**

Replace the hardcoded URLs in the "Next steps" block:
```bash
info "  3. Open Console:                  https://console.${NIP_IO_IP}.nip.io"
```
```bash
info "  4. Keycloak admin:                http://keycloak.${NIP_IO_IP}.nip.io"
```

- [ ] **Step 6: Verify install.sh changes**

Run:
```bash
bash -n install.sh
```
Expected: no syntax errors (exit 0).

Then verify the IP detection works:
```bash
source <(grep -A15 'detect_nip_io_ip()' install.sh) && detect_nip_io_ip
```
Expected: prints `192.168.39.164` (or whatever `minikube ip` returns for kvm2).

Then verify the sed substitution works on rendered output:
```bash
NIP_IO_IP=192.168.39.164 kubectl kustomize overlays/oauth/base | grep nip.io | head -5
```
Expected: shows `127.0.0.1.nip.io` (raw kustomize, no sed yet).

```bash
NIP_IO_IP=192.168.39.164 kubectl kustomize overlays/oauth/base | sed "s/127\.0\.0\.1\.nip\.io/192.168.39.164.nip.io/g" | grep nip.io | head -5
```
Expected: shows `192.168.39.164.nip.io` in all hostnames.

- [ ] **Step 7: Commit**

```bash
git add install.sh
git commit -m "feat: auto-detect minikube IP for nip.io hostnames in install.sh"
```

---

### Task 2: Add IP detection to SetupDemo.java

**Files:**
- Modify: `scripts/SetupDemo.java`

- [ ] **Step 1: Add commandOutput helper method (before the existing runCommand method, around line 195)**

```java
    static String commandOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }
```

- [ ] **Step 2: Add detectNipIoIp method (after commandOutput)**

```java
    static String detectNipIoIp() {
        String envIp = System.getenv("NIP_IO_IP");
        if (envIp != null && !envIp.isBlank()) {
            return envIp;
        }

        try {
            String profileJson = commandOutput("minikube", "profile", "list", "-o", "json");
            if (profileJson.contains("\"Driver\":\"docker\"")) {
                return "127.0.0.1";
            }
        } catch (Exception ignored) {
        }

        try {
            String ip = commandOutput("minikube", "ip");
            if (!ip.isEmpty()) {
                return ip;
            }
        } catch (Exception ignored) {
        }

        return "127.0.0.1";
    }
```

- [ ] **Step 3: Use detected IP in main() for output URLs**

At the top of `main()`, after the existing `Path clientsDir = ...` line, add:

```java
        String nipIoIp = detectNipIoIp();
```

Then replace the two hardcoded URL lines (currently lines 93-94):

```java
        info("  Console:        https://console." + nipIoIp + ".nip.io");
        info("  Keycloak admin: http://keycloak." + nipIoIp + ".nip.io");
```

- [ ] **Step 4: Verify SetupDemo.java compiles**

Run:
```bash
jbang --quiet scripts/SetupDemo.java --help 2>&1 || echo "Compilation check (non-zero exit is OK if it runs)"
```

Or just verify no compilation errors:
```bash
javac --source 21 --enable-preview -d /tmp/setupdemo-check scripts/SetupDemo.java 2>&1 || true
```

The JBang script may fail at runtime (no minikube, etc.) but should compile without errors.

- [ ] **Step 5: Commit**

```bash
git add scripts/SetupDemo.java
git commit -m "feat: auto-detect minikube IP for nip.io hostnames in SetupDemo.java"
```

---

### Task 3: Update documentation

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update README.md URL table and add NIP_IO_IP note**

Replace the URL table in the "Access the demo" section (lines 88-91):

```markdown
| Service | URL | Credentials |
|---------|-----|-------------|
| Console | `https://console.<minikube-ip>.nip.io` | alice / alice-password **or** bob / bob-password |
| Keycloak Admin | `http://keycloak.<minikube-ip>.nip.io` | admin / admin |
```

Add a note after the table (before "Accept the self-signed certificate" line):

```markdown
> The IP is auto-detected from `minikube ip`. To override (e.g., for a remote cluster), set `NIP_IO_IP=<ip> ./install.sh`.
```

- [ ] **Step 2: Update CLAUDE.md nip.io bullet**

Replace the existing nip.io bullet (line 51):

```
- **Nip.io DNS**: `*.127.0.0.1.nip.io` wildcard avoids `/etc/hosts` edits. Console at `console.127.0.0.1.nip.io`, Keycloak at `keycloak.127.0.0.1.nip.io`.
```

With:

```
- **Nip.io DNS**: `*.<ip>.nip.io` wildcard avoids `/etc/hosts` edits. The IP is auto-detected from `minikube ip` at deploy time (falls back to `127.0.0.1` for docker driver). Override with `NIP_IO_IP` env var.
```

- [ ] **Step 3: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document dynamic nip.io IP detection and NIP_IO_IP override"
```

---

## Verification

After all tasks are complete, run a full end-to-end check:

1. Tear down existing deployment:
   ```bash
   ./uninstall.sh
   ```

2. Redeploy with the updated install.sh:
   ```bash
   ./install.sh
   ```
   Expected: output shows `Using nip.io IP: 192.168.39.164` (or current minikube IP) and all URLs use that IP.

3. Verify Keycloak is reachable at the dynamic hostname:
   ```bash
   curl -sf http://keycloak.$(minikube ip).nip.io/realms/kafka-oauth/.well-known/openid-configuration | head -c 100
   ```
   Expected: JSON response from Keycloak OIDC discovery.

4. Verify Console ingress uses the dynamic hostname:
   ```bash
   kubectl get ingress -n streamshub-console -o jsonpath='{.items[0].spec.rules[0].host}'
   ```
   Expected: `console.<minikube-ip>.nip.io`

5. Open Console in browser at `http://console.<minikube-ip>.nip.io` (with `minikube tunnel` running).
