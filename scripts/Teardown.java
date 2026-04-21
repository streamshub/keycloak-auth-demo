///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS info.picocli:picocli:4.7.6

import java.nio.file.Path;
import java.util.List;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

@Command(name = "teardown", mixinStandardHelpOptions = true,
    description = "Tear down the StreamsHub OAuth demo.")
public class Teardown implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Teardown()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        Path projectRoot = findProjectRoot();

        info("StreamsHub OAuth Demo - Teardown");
        info("=================================");

        info("=== Removing client applications ===");
        runCommand("kubectl", "delete", "-k",
            projectRoot.resolve("clients/deploy").toString(), "--ignore-not-found");

        info("=== Removing operands (Kafka, Console, topics) ===");
        runCommand("kubectl", "delete", "-k",
            projectRoot.resolve("overlays/oauth/stack").toString(), "--ignore-not-found");

        info("Waiting for Kafka cluster to be fully removed...");
        runSilent("kubectl", "wait", "kafka/dev-cluster", "-n", "kafka",
            "--for=delete", "--timeout=120s");

        info("=== Removing operators and Keycloak ===");
        runCommand("kubectl", "delete", "-k",
            projectRoot.resolve("overlays/oauth/base").toString(), "--ignore-not-found");

        info("=== Cleaning up namespaces ===");
        for (String ns : List.of("kafka", "streamshub-console", "keycloak", "strimzi")) {
            if (runSilent("kubectl", "get", "namespace", ns) == 0) {
                info("Deleting namespace " + ns + "...");
                runCommand("kubectl", "delete", "namespace", ns, "--ignore-not-found");
            }
        }

        info("=== Uninstall complete ===");
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

    static void info(String msg) {
        System.out.println(Ansi.AUTO.string("@|green [INFO]|@ " + msg));
    }

    static void error(String msg) {
        System.err.println(Ansi.AUTO.string("@|red [ERROR]|@ " + msg));
    }
}
