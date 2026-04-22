///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS info.picocli:picocli:4.7.6

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

@Command(name = "setup", mixinStandardHelpOptions = true,
    description = "Set up the full StreamsHub OAuth demo (operators, Keycloak, Kafka, Console, clients).")
public class Setup implements Callable<Integer> {

    static final String[] IMAGES = {
        "streamshub-oauth-demo/order-producer:latest",
        "streamshub-oauth-demo/order-consumer:latest"
    };

    static final String KC_POD = "deployment/keycloak";
    static final String KC_NS = "keycloak";

    static String TIMEOUT = "300s";

    @Option(names = "--skip-infra",
        description = "Skip infrastructure (Phase 1+2), only build and deploy clients.")
    boolean skipInfra;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Setup()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        TIMEOUT = envOrDefault("TIMEOUT", "300s");

        Path projectRoot = findProjectRoot();
        String nipIoIp = detectNipIoIp();

        info("StreamsHub OAuth Demo - Setup");
        info("==============================");
        info("Using nip.io IP: " + nipIoIp);

        if (!skipInfra) {
            checkPrerequisites();
            waitForIngressWebhook();

            phase1(projectRoot, nipIoIp);
            configureKeycloakGroupsScope();
            phase2(projectRoot, nipIoIp);
        } else {
            info("Skipping infrastructure (Phase 1+2) -- --skip-infra specified");
        }

        phase3(projectRoot, nipIoIp);
        printSummary(nipIoIp);
        return 0;
    }

    // ── Phase 1: Operators + Keycloak ──────────────────────────────────

    static void phase1(Path projectRoot, String nipIoIp) throws Exception {
        info("");
        info("=== Phase 1: Deploying operators and Keycloak ===");
        kustomizeAndApply(projectRoot.resolve("overlays/oauth/base"), nipIoIp, true);

        info("Waiting for deployments...");
        runChecked("strimzi operator rollout",
            "kubectl", "rollout", "status", "deployment/strimzi-cluster-operator",
            "-n", "strimzi", "--timeout=" + TIMEOUT);
        runChecked("console operator rollout",
            "kubectl", "rollout", "status", "deployment/streamshub-console-operator",
            "-n", "streamshub-console", "--timeout=" + TIMEOUT);
        runChecked("keycloak rollout",
            "kubectl", "rollout", "status", "deployment/keycloak",
            "-n", "keycloak", "--timeout=" + TIMEOUT);
        runChecked("authorino operator rollout",
            "kubectl", "rollout", "status", "deployment/authorino-operator",
            "-n", "authorino-operator", "--timeout=" + TIMEOUT);

        info("Phase 1 complete: operators and Keycloak are ready");
    }

    // ── Keycloak post-import configuration ─────────────────────────────

    static void configureKeycloakGroupsScope() throws Exception {
        info("");
        info("Configuring Keycloak 'groups' client scope...");

        commandOutputDiscardStderr(kcadm("config", "credentials",
            "--server", "http://localhost:8080",
            "--realm", "master", "--user", "admin", "--password", "admin"));

        String scopesOutput = commandOutputDiscardStderr(kcadm("get", "client-scopes",
            "-r", "kafka-oauth", "--fields", "id,name", "--format", "csv", "--noquotes"));

        String scopeId = null;
        for (String line : scopesOutput.split("\n")) {
            if (line.endsWith(",groups")) {
                scopeId = line.split(",")[0];
                break;
            }
        }

        if (scopeId == null) {
            scopeId = commandOutputDiscardStderr(kcadm("create", "client-scopes",
                "-r", "kafka-oauth",
                "-s", "name=groups", "-s", "protocol=openid-connect",
                "-s", "attributes={\"include.in.token.scope\":\"true\",\"display.on.consent.screen\":\"false\"}",
                "-i")).trim();

            commandOutputDiscardStderr(kcadm("create",
                "client-scopes/" + scopeId + "/protocol-mappers/models",
                "-r", "kafka-oauth",
                "-s", "name=groups", "-s", "protocol=openid-connect",
                "-s", "protocolMapper=oidc-group-membership-mapper",
                "-s", "consentRequired=false",
                "-s", "config={\"full.path\":\"true\",\"id.token.claim\":\"true\",\"access.token.claim\":\"true\",\"claim.name\":\"groups\",\"userinfo.token.claim\":\"true\"}"));

            info("Created 'groups' client scope");
        } else {
            info("'groups' client scope already exists");
        }

        String clientUuid = commandOutputDiscardStderr(kcadm("get", "clients",
            "-r", "kafka-oauth", "-q", "clientId=streamshub-console",
            "--fields", "id", "--format", "csv", "--noquotes")).trim();

        commandOutputDiscardStderr(kcadm("update",
            "clients/" + clientUuid + "/default-client-scopes/" + scopeId,
            "-r", "kafka-oauth"));

        info("Assigned 'groups' scope to streamshub-console client");
    }

    // ── Phase 2: Kafka + Console + topics ──────────────────────────────

    static void phase2(Path projectRoot, String nipIoIp) throws Exception {
        info("");
        info("=== Phase 2: Deploying Kafka, Console, and topics ===");
        kustomizeAndApply(projectRoot.resolve("overlays/oauth/stack"), nipIoIp, false);

        info("Waiting for Kafka cluster to be ready (this may take a few minutes)...");
        if (runCommand("kubectl", "wait", "kafka/dev-cluster", "-n", "kafka",
                "--for=condition=Ready", "--timeout=" + TIMEOUT) != 0) {
            warn("Kafka readiness check timed out - it may still be starting");
        }

        info("Waiting for Console to be ready...");
        if (runCommand("kubectl", "wait", "console/streamshub-console",
                "-n", "streamshub-console", "--for=condition=Ready", "--timeout=" + TIMEOUT) != 0) {
            warn("Console readiness check timed out - it may still be starting");
        }

        info("Waiting for Authorino to be ready...");
        runChecked("authorino rollout",
            "kubectl", "rollout", "status", "deployment/authorino", "-n", "kafka",
            "--timeout=" + TIMEOUT);

        info("Waiting for Apicurio Registry to be ready...");
        runChecked("apicurio-registry rollout",
            "kubectl", "rollout", "status", "deployment/apicurio-registry", "-n", "kafka",
            "--timeout=" + TIMEOUT);

        // OIDC tokens with Keycloak Authorization Services grants exceed nginx's default 4KB proxy buffer
        runCommand("kubectl", "annotate", "ingress",
            "streamshub-console-console-ingress", "-n", "streamshub-console",
            "nginx.ingress.kubernetes.io/proxy-buffer-size=16k", "--overwrite");

        info("Phase 2 complete");
    }

    // ── Phase 3: Build + deploy clients ────────────────────────────────

    static void phase3(Path projectRoot, String nipIoIp) throws Exception {
        Path clientsDir = projectRoot.resolve("clients");

        info("");
        info("=== Phase 3: Building and deploying client applications ===");

        String dockerHost = detectContainerRuntime();
        if (dockerHost != null) {
            info("Using Podman via DOCKER_HOST=" + dockerHost);
        } else {
            info("Using Docker");
        }

        String[] mvnCmd = dockerHost != null
            ? new String[]{"mvn", "package", "docker:build", "-f", clientsDir.toString(),
                           "-Ddocker.host=" + dockerHost, "-DskipTests"}
            : new String[]{"mvn", "package", "docker:build", "-f", clientsDir.toString(), "-DskipTests"};

        runChecked("Maven build", mvnCmd);
        info("Client images built successfully");

        info("Loading images into minikube...");
        for (String image : IMAGES) {
            info("  Loading " + image + "...");
            runChecked("minikube image load " + image,
                "minikube", "image", "load", image);
        }
        info("Images loaded into minikube");

        info("Deploying client applications...");
        runChecked("client deployment",
            "kubectl", "apply", "-k", clientsDir.resolve("deploy").toString());
        info("Client applications deployed");

        info("Verifying Keycloak is reachable...");
        verifyKeycloak();
    }

    // ── Prerequisites ──────────────────────────────────────────────────

    static void checkPrerequisites() {
        info("Checking prerequisites...");
        if (runSilent("kubectl", "version", "--client") != 0) {
            error("kubectl is required but not found");
            System.exit(1);
        }
        if (runSilent("kubectl", "cluster-info") != 0) {
            error("Cannot connect to Kubernetes cluster");
            System.exit(1);
        }
    }

    static void waitForIngressWebhook() {
        if (runSilent("kubectl", "get", "validatingwebhookconfiguration",
                "ingress-nginx-admission") == 0) {
            info("Waiting for ingress controller webhook to be ready...");
            runCommand("kubectl", "wait", "pod", "-n", "ingress-nginx",
                "-l", "app.kubernetes.io/component=controller",
                "--for=condition=Ready", "--timeout=" + TIMEOUT);
        }
    }

    // ── Kustomize + apply pipeline ─────────────────────────────────────

    static void kustomizeAndApply(Path overlay, String nipIoIp, boolean serverSide) throws Exception {
        ProcessBuilder kustomizePb = new ProcessBuilder("kubectl", "kustomize", overlay.toString());
        kustomizePb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process kustomize = kustomizePb.start();
        String yaml = new String(kustomize.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int kustomizeRc = kustomize.waitFor();
        if (kustomizeRc != 0) {
            error("kubectl kustomize failed for " + overlay);
            System.exit(1);
        }

        yaml = yaml.replace("127.0.0.1.nip.io", nipIoIp + ".nip.io");

        List<String> applyCmd = new ArrayList<>(List.of("kubectl", "apply"));
        if (serverSide) applyCmd.add("--server-side");
        applyCmd.addAll(List.of("-f", "-"));

        ProcessBuilder applyPb = new ProcessBuilder(applyCmd);
        applyPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        applyPb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process apply = applyPb.start();
        try (OutputStream os = apply.getOutputStream()) {
            os.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        int applyRc = apply.waitFor();
        if (applyRc != 0) {
            error("kubectl apply failed for " + overlay);
            System.exit(1);
        }
    }

    // ── kcadm.sh helper ───────────────────────────────────────────────

    static String[] kcadm(String... args) {
        List<String> cmd = new ArrayList<>(List.of(
            "kubectl", "exec", "-n", KC_NS, KC_POD, "--",
            "/opt/keycloak/bin/kcadm.sh"));
        cmd.addAll(List.of(args));
        return cmd.toArray(String[]::new);
    }

    // ── Keycloak verification ──────────────────────────────────────────

    static void verifyKeycloak() {
        Process portForward = null;
        try {
            portForward = new ProcessBuilder(
                    "kubectl", "port-forward", "-n", "keycloak", "svc/keycloak", "18080:8080")
                .redirectErrorStream(true)
                .start();

            Thread.sleep(2000);

            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:18080/realms/kafka-oauth/.well-known/openid-configuration"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() == 200) {
                info("Keycloak realm 'kafka-oauth' is accessible");
            } else {
                warn("Keycloak returned HTTP " + response.statusCode() + " - realm may not be configured");
            }
        } catch (Exception e) {
            warn("Could not verify Keycloak - it may still be starting");
        } finally {
            if (portForward != null) {
                portForward.destroyForcibly();
            }
        }
    }

    // ── Detection methods ──────────────────────────────────────────────

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

    static String detectContainerRuntime() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null) {
            return dockerHost;
        }

        String uid = System.getProperty("user.name");
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            uid = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
        } catch (Exception ignored) {
        }

        String podmanSocket = "unix:///run/user/" + uid + "/podman/podman.sock";
        Path socketPath = Path.of("/run/user/" + uid + "/podman/podman.sock");
        if (socketPath.toFile().exists()) {
            return podmanSocket;
        }

        try {
            int result = runSilent("docker", "info");
            if (result == 0) return null;
        } catch (Exception ignored) {
        }

        try {
            int result = runSilent("podman", "info");
            if (result == 0) {
                warn("Podman found but socket not active. Run: systemctl --user enable --now podman.socket");
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    static Path findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (dir.resolve("overlays").toFile().isDirectory()) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    // ── Process utilities ──────────────────────────────────────────────

    static String commandOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }

    static String commandOutputDiscardStderr(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }

    static int runCommand(String... cmd) {
        try {
            return new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (Exception e) {
            error("Failed to run: " + String.join(" ", cmd));
            return 1;
        }
    }

    static int runSilent(String... cmd) {
        try {
            return new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor();
        } catch (Exception e) {
            return 1;
        }
    }

    static void runChecked(String description, String... cmd) {
        int rc = runCommand(cmd);
        if (rc != 0) {
            error(description + " failed (exit code " + rc + ")");
            System.exit(1);
        }
    }

    static String envOrDefault(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    // ── Output helpers ─────────────────────────────────────────────────

    static void printSummary(String nipIoIp) {
        info("");
        info("=== Setup complete ===");
        info("");
        info("Next steps:");
        info("  1. Start minikube tunnel:  minikube tunnel");
        info("  2. Open Console:           https://console." + nipIoIp + ".nip.io");
        info("     - Alice (PII access):   alice / alice-password");
        info("     - Bob (public only):    bob / bob-password");
        info("  3. Keycloak admin:         http://keycloak." + nipIoIp + ".nip.io");
        info("     - Admin credentials:    admin / admin");
        info("  4. Apicurio Registry:      http://apicurio-registry." + nipIoIp + ".nip.io");
        info("     - Uses same alice/bob credentials");
        info("");
        info("View client logs:");
        info("  kubectl logs -f deployment/order-producer -n kafka");
        info("  kubectl logs -f deployment/order-consumer -n kafka");
    }

    static void info(String msg) {
        System.out.println(Ansi.AUTO.string("@|green [INFO]|@ " + msg));
    }

    static void warn(String msg) {
        System.out.println(Ansi.AUTO.string("@|yellow [WARN]|@ " + msg));
    }

    static void error(String msg) {
        System.err.println(Ansi.AUTO.string("@|red [ERROR]|@ " + msg));
    }
}
