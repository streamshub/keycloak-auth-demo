///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * JBang script to orchestrate Phase 3 of the OAuth demo:
 * 1. Build client images via Maven (Docker or Podman)
 * 2. Load images into minikube
 * 3. Deploy client apps
 * 4. Verify Keycloak is reachable
 * 5. Print access URLs and credentials
 */
public class SetupDemo {

    static final String[] IMAGES = {
        "streamshub-oauth-demo/order-producer:latest",
        "streamshub-oauth-demo/order-consumer:latest"
    };

    public static void main(String[] args) throws Exception {
        Path projectRoot = findProjectRoot();
        Path clientsDir = projectRoot.resolve("clients");
        String nipIoIp = detectNipIoIp();

        info("StreamsHub OAuth Demo - Client Setup");
        info("=====================================");

        // Step 1: Build client images
        info("");
        info("Step 1: Building client images...");
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

        int mvnResult = runCommand(mvnCmd);
        if (mvnResult != 0) {
            error("Maven build failed (exit code " + mvnResult + ")");
            System.exit(1);
        }
        info("Client images built successfully");

        // Step 2: Load images into minikube
        info("");
        info("Step 2: Loading images into minikube...");
        for (String image : IMAGES) {
            info("  Loading " + image + "...");
            int result = runCommand("minikube", "image", "load", image);
            if (result != 0) {
                error("Failed to load image: " + image);
                System.exit(1);
            }
        }
        info("Images loaded into minikube");

        // Step 3: Deploy client apps
        info("");
        info("Step 3: Deploying client applications...");
        Path deployDir = clientsDir.resolve("deploy");
        int deployResult = runCommand("kubectl", "apply", "-k", deployDir.toString());
        if (deployResult != 0) {
            error("Failed to deploy client applications");
            System.exit(1);
        }
        info("Client applications deployed");

        // Step 4: Verify Keycloak
        info("");
        info("Step 4: Verifying Keycloak is reachable...");
        verifyKeycloak();

        // Step 5: Print summary
        info("");
        info("=== Setup complete ===");
        info("");
        info("Access URLs (requires 'minikube tunnel' running):");
        info("  Console:        https://console." + nipIoIp + ".nip.io");
        info("  Keycloak admin: http://keycloak." + nipIoIp + ".nip.io");
        info("");
        info("Demo credentials:");
        info("  Alice (PII access):    alice / alice-password");
        info("  Bob (public only):     bob / bob-password");
        info("  Keycloak admin:        admin / admin");
        info("");
        info("View client logs:");
        info("  kubectl logs -f deployment/order-producer -n kafka");
        info("  kubectl logs -f deployment/order-consumer -n kafka");
    }

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

    static String detectContainerRuntime() {
        // Check if DOCKER_HOST is already set
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null) {
            return dockerHost;
        }

        // Check if podman socket exists (Linux)
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

        // Check if docker is available
        try {
            int result = runCommand("docker", "info");
            if (result == 0) return null; // Docker works, no override needed
        } catch (Exception ignored) {
        }

        // Try podman as docker replacement
        try {
            int result = runCommand("podman", "info");
            if (result == 0) {
                warn("Podman found but socket not active. Run: systemctl --user enable --now podman.socket");
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath();
        // Walk up until we find install.sh
        Path dir = current;
        while (dir != null) {
            if (dir.resolve("install.sh").toFile().exists()) {
                return dir;
            }
            dir = dir.getParent();
        }
        // Fallback: assume scripts/ is one level down from root
        return current.getParent() != null ? current.getParent() : current;
    }

    static String commandOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }

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

    static int runCommand(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                .inheritIO()
                .redirectErrorStream(false);
            Process process = pb.start();
            return process.waitFor();
        } catch (Exception e) {
            error("Failed to run command: " + String.join(" ", cmd));
            error("  " + e.getMessage());
            return 1;
        }
    }

    static void info(String msg) {
        System.out.println("\u001B[32m[INFO]\u001B[0m " + msg);
    }

    static void warn(String msg) {
        System.out.println("\u001B[33m[WARN]\u001B[0m " + msg);
    }

    static void error(String msg) {
        System.err.println("\u001B[31m[ERROR]\u001B[0m " + msg);
    }
}
