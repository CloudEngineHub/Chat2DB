package ai.chat2db.community.jcef.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceUtilTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temporary;
    private final List<Child> children = new ArrayList<>();

    @AfterEach
    void stopChildren() throws Exception {
        for (Child child : children) {
            if (child.process.isAlive()) {
                child.process.destroyForcibly();
            }
            assertTrue(child.process.waitFor(10, TimeUnit.SECONDS), "Test process did not exit");
        }
    }

    @Test
    void secondaryNeverStartsServicesWithMcpEnabledOrDisabled() throws Exception {
        for (boolean mcp : List.of(false, true)) {
            Path state = temporary.resolve("state-" + mcp);
            Child primary = start(state, mcp ? 0 : -1);
            primary.awaitPrimary();
            Child secondary = start(state, primary.port());
            secondary.awaitSecondary();
            assertFalse(Files.exists(secondary.directory.resolve("initialized")));
            assertTrue(primary.process.isAlive());
        }
    }

    @Test
    void simultaneousLaunchesInitializeOnlyOneProcess() throws Exception {
        Path state = temporary.resolve("state");
        Child first = start(state, -1);
        Child second = start(state, -1);
        await(() -> Files.exists(first.status()) && Files.exists(second.status()));
        List<String> statuses = List.of(Files.readString(first.status()), Files.readString(second.status()));
        assertEquals(1, statuses.stream().filter("PRIMARY"::equals).count());
        assertEquals(1, statuses.stream().filter("SECONDARY"::equals).count());
    }

    @Test
    void launchRequestsWaitForReadinessAndPreserveEveryFile() throws Exception {
        Path state = temporary.resolve("state");
        Path firstFile = Files.writeString(temporary.resolve("first file.sql"), "select 1");
        Path secondFile = Files.writeString(temporary.resolve("second file.sql"), "select 2");
        String uri = "chat2db-community://open?console=test";
        Child primary = start(state, -1, firstFile.toString());
        primary.awaitPrimary();
        List<Child> requests = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            requests.add(start(state, -1, secondFile.toString(), uri));
        }
        for (Child request : requests) {
            request.awaitSecondary();
        }
        assertFalse(Files.exists(primary.received()));

        primary.command("READY");
        await(() -> lineCount(primary.received()) == 6);
        List<String[]> received = readRequests(primary.received());
        assertArrayEquals(new String[]{firstFile.toString()}, received.get(0));
        for (String[] request : received.subList(1, received.size())) {
            assertArrayEquals(new String[]{secondFile.toString(), uri}, request);
        }
    }

    @Test
    void doubleClickWithoutArgumentsStillRequestsActivation() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        assertTrue(readRequests(primary.received()).stream().allMatch(args -> args.length == 0));
    }

    @Test
    void lockRemainsHeldUntilShutdownHooksAndServicesFinish() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, 0);
        primary.awaitPrimary();
        int port = primary.port();
        primary.command("EXIT");
        await(() -> Files.exists(primary.directory.resolve("stopping")));
        start(state, port).awaitSecondary();
        Files.createFile(primary.directory.resolve("allow-exit"));
        assertTrue(primary.process.waitFor(10, TimeUnit.SECONDS));
        assertTrue(Files.exists(state.resolve("app.lock")));

        Child restarted = start(state, port);
        restarted.awaitPrimary();
        assertEquals(port, restarted.port());
    }

    @Test
    void operatingSystemReleasesLockAfterCrash() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("CRASH");
        assertTrue(primary.process.waitFor(10, TimeUnit.SECONDS));
        start(state, -1).awaitPrimary();
    }

    @Test
    void invalidLockLocationDoesNotStartAnUnprotectedInstance() throws Exception {
        Path state = Files.writeString(temporary.resolve("not-a-directory"), "test");
        Child child = start(state, -1);
        assertTrue(child.process.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, child.process.exitValue());
        assertFalse(Files.exists(child.directory.resolve("initialized")));
    }

    @Test
    void onlyDesktopGuiRuntimeUsesTheInstanceGate() {
        assertTrue(SingleInstanceUtil.requiresInstanceLock("DESKTOP", null, "community", false, false));
        assertTrue(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "pro", false, false));
        assertTrue(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "local", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "false", "pro", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "cli", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "community", true, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock(null, null, "pro", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "pro", false, true));
    }

    private Child start(Path state, int port, String... arguments) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "process-");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                InstanceProcess.class.getName(), state.toString(), directory.toString(), String.valueOf(port)));
        command.addAll(Arrays.asList(arguments));
        Path argumentFile = directory.resolve("java.args");
        Files.write(argumentFile, command.subList(1, command.size()).stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
        Process process = new ProcessBuilder(command.get(0), "@" + argumentFile).redirectErrorStream(true)
                .redirectOutput(directory.resolve("output.log").toFile()).start();
        Child child = new Child(process, directory);
        children.add(child);
        return child;
    }

    private static long lineCount(Path path) {
        try {
            return Files.readAllLines(path).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static List<String[]> readRequests(Path path) throws Exception {
        List<String[]> result = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            result.add(MAPPER.readValue(line, String[].class));
        }
        return result;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for subprocess");
    }

    private record Child(Process process, Path directory) {
        Path status() { return directory.resolve("status"); }
        Path received() { return directory.resolve("received.jsonl"); }
        int port() throws Exception { return Integer.parseInt(Files.readString(directory.resolve("initialized"))); }

        void awaitPrimary() throws Exception {
            await(() -> Files.exists(status()) || !process.isAlive());
            assertTrue(Files.exists(status()), () -> "Child failed: " + output());
            assertEquals("PRIMARY", Files.readString(status()), this::output);
        }

        void awaitSecondary() throws Exception {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), this::output);
            assertEquals(0, process.exitValue(), this::output);
            assertEquals("SECONDARY", Files.readString(status()), this::output);
        }

        void command(String command) throws Exception {
            process.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        String output() {
            try { return Files.readString(directory.resolve("output.log")); }
            catch (Exception exception) { return exception.toString(); }
        }
    }

    public static final class InstanceProcess {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[1]);
            SingleInstanceUtil.Instance instance = new SingleInstanceUtil.Instance(Path.of(args[0]));
            if (!instance.acquire(Arrays.copyOfRange(args, 3, args.length))) {
                instance.close();
                Files.writeString(directory.resolve("status"), "SECONDARY");
                return;
            }
            int port = Integer.parseInt(args[2]);
            ServerSocket server = port < 0 ? null : new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
            Files.writeString(directory.resolve("initialized"), String.valueOf(server == null ? -1 : server.getLocalPort()));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.createFile(directory.resolve("stopping"));
                    await(() -> Files.exists(directory.resolve("allow-exit")));
                    if (server != null) { server.close(); }
                } catch (Exception exception) { throw new RuntimeException(exception); }
            }));
            Files.writeString(directory.resolve("status"), "PRIMARY");
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String command;
            while ((command = input.readLine()) != null) {
                switch (command) {
                    case "READY" -> instance.onReady(arguments -> {
                        try {
                            Files.writeString(directory.resolve("received.jsonl"),
                                    MAPPER.writeValueAsString(arguments) + "\n", CREATE, APPEND);
                        } catch (Exception exception) { throw new RuntimeException(exception); }
                    });
                    case "EXIT" -> System.exit(0);
                    case "CRASH" -> Runtime.getRuntime().halt(0);
                    default -> throw new IllegalArgumentException(command);
                }
            }
        }
    }
}
