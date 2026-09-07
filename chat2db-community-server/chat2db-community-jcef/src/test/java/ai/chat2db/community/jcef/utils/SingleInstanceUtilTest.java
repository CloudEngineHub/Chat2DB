package ai.chat2db.community.jcef.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceUtilTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temporary;
    private final List<Child> children = new ArrayList<>();

    @AfterEach
    void stopChildren() throws Exception {
        for (Child child : children) {
            if (child.process.isAlive()) {
                try {
                    child.command("STOP");
                } catch (IOException ignored) {
                    child.process.destroy();
                }
            }
        }
        for (Child child : children) {
            if (!child.process.waitFor(10, TimeUnit.SECONDS)) {
                child.process.destroyForcibly();
            }
            assertTrue(child.process.waitFor(10, TimeUnit.SECONDS), "Test process did not exit");
            child.outputReader.join(10000);
            assertFalse(child.outputReader.isAlive(), "Process output reader did not finish");
            assertNull(child.outputFailure, "Process output collection failed");
            child.process.getOutputStream().close();
            child.process.getInputStream().close();
            child.process.getErrorStream().close();
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
    void receivedLaunchRequestsWaitForWindowReadiness() throws Exception {
        Path state = temporary.resolve("state");
        Path firstFile = Files.writeString(temporary.resolve("first file.sql"), "select 1");
        Path secondFile = Files.writeString(temporary.resolve("second file.sql"), "select 2");
        Child primary = start(state, -1, firstFile.toString());
        primary.awaitPrimary();
        start(state, -1, secondFile.toString()).awaitSecondary();
        assertFalse(Files.exists(primary.received()));

        primary.command("READY");
        await(() -> lineCount(primary.received()) == 2);
        List<String> received = readRequests(primary.received());
        assertEquals(firstFile.toString(), received.get(0));
        assertEquals(secondFile.toString(), received.get(1));
    }

    @Test
    void subsequentFileAndProtocolRequestsKeepWorking() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("query.sql"), "select 1");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        List<String> arguments = List.of(sql.toString(), "chat2db-community://open?console=test", sql.toString());
        for (int index = 0; index < arguments.size(); index++) {
            start(state, -1, arguments.get(index)).awaitSecondary();
            int expected = index + 2;
            await(() -> lineCount(primary.received()) == expected);
            assertEquals(arguments.get(index), readRequests(primary.received()).get(index + 1));
        }
    }

    @Test
    void repeatedRegistrationDoesNotReplayArguments() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("initial.sql"), "select 1");
        Child primary = start(state, -1, sql.toString());
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("REGISTER_AGAIN");
        await(() -> Files.exists(primary.directory.resolve("registered-again")));
        assertEquals("true", Files.readString(primary.directory.resolve("registered-again")));
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        assertEquals(sql.toString(), readRequests(primary.received()).get(0));
        assertEquals("", readRequests(primary.received()).get(1));
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
        assertTrue(readRequests(primary.received()).stream().allMatch(String::isEmpty));
    }

    @Test
    void sameTimestampReplacementsDeliverChangedAndRepeatedArgumentsOnce() throws Exception {
        Path state = temporary.resolve("state");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        FileTime timestamp = FileTime.fromMillis(System.currentTimeMillis() - 10000);
        List<String> arguments = List.of("first.sql", "second.sql", "second.sql", "", "");
        for (int index = 0; index < arguments.size(); index++) {
            Path replacement = Files.createTempFile(state, "ipc-", ".tmp");
            Files.writeString(replacement, arguments.get(index));
            Files.setLastModifiedTime(replacement, timestamp);
            Files.move(replacement, state.resolve("app.ipc"), ATOMIC_MOVE, REPLACE_EXISTING);
            int expectedCount = index + 2;
            await(() -> lineCount(primary.received()) == expectedCount);
            assertEquals(arguments.get(index), readRequests(primary.received()).get(index + 1));
            Thread.sleep(250);
            assertEquals(expectedCount, lineCount(primary.received()), "Late file events repeated a request");
        }
    }

    @Test
    void inPlaceWriteWithTheSameTimestampIsDetectedFromFileEvents() throws Exception {
        Path state = temporary.resolve("state");
        Path ipc = state.resolve("app.ipc");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        primary.command("PAUSE_NEXT_DELIVERY");
        await(() -> Files.exists(primary.directory.resolve("pause-enabled")));
        publish(ipc, "first.sql");
        await(() -> Files.exists(primary.directory.resolve("delivery-paused")));
        FileTime timestamp = Files.getLastModifiedTime(ipc);
        try {
            Files.writeString(ipc, "other.sql");
            Files.setLastModifiedTime(ipc, timestamp);
        } finally {
            primary.command("RESUME_DELIVERY");
        }
        await(() -> lineCount(primary.received()) == 3);
        assertEquals("other.sql", readRequests(primary.received()).get(2));
        Thread.sleep(300);
        assertEquals(3, lineCount(primary.received()));
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
        assertTrue(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "extension", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "false", "community", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "cli", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "community", true, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock(null, null, "community", false, false));
        assertFalse(SingleInstanceUtil.requiresInstanceLock("DESKTOP", "true", "community", false, true));
    }

    @Test
    void relativeFileNamedLikeAProtocolUsesTheSendersDirectory() throws Exception {
        Path state = temporary.resolve("state");
        Path sender = Files.createDirectories(temporary.resolve("sender"));
        Path sql = Files.writeString(sender.resolve("chat2db-export.sql"), "select 1");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        start(InstanceProcess.class, state, -1, sender, sql.getFileName().toString()).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        String argument = readRequests(primary.received()).get(1);
        assertTrue(Path.of(argument).isAbsolute());
        assertTrue(Files.isSameFile(sql, Path.of(argument)));
    }

    @Test
    void ipcReadFailureDoesNotStopTheListenerOrRepeatRequests() throws Exception {
        Path state = temporary.resolve("state");
        Path ipc = state.resolve("app.ipc");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        Files.createDirectory(ipc);
        try {
            primary.command("READY");
            await(() -> primary.output().contains("waiting for recovery"));
        } finally {
            Files.delete(ipc);
        }
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        Thread.sleep(300);
        assertEquals(2, lineCount(primary.received()), "Unchanged IPC content was delivered twice");
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 3);
    }

    @Test
    void legacySenderRequestsAreRetainedUntilTheNewWindowIsReady() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("legacy.sql"), "select 1");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        Files.writeString(state.resolve("app.ipc"), sql.toString());
        Thread.sleep(300);
        assertFalse(Files.exists(primary.received()));
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 2);
        assertTrue(readRequests(primary.received()).contains(sql.toString()));
    }

    @Test
    void newSenderUsesTheLegacyOwnersProtocol() throws Exception {
        Path state = temporary.resolve("state");
        Path sql = Files.writeString(temporary.resolve("legacy.sql"), "select 1");
        Child legacy = start(LegacyInstanceProcess.class, state, -1, null);
        legacy.awaitPrimary();
        start(state, -1, sql.toString()).awaitSecondary();
        await(() -> lineCount(legacy.received()) == 1);
        assertEquals(sql.toString(), readRequests(legacy.received()).get(0));
        assertFalse(Files.exists(state.resolve("app.ipc.d")), "New sender queued a request the old owner cannot read");
    }

    @Test
    void staleLegacyRequestIsNotReplayedWhenStartingANewInstance() throws Exception {
        Path state = Files.createDirectories(temporary.resolve("state"));
        Files.writeString(state.resolve("app.ipc"), "chat2db-community://open?console=old");
        Child primary = start(state, -1);
        primary.awaitPrimary();
        primary.command("READY");
        await(() -> lineCount(primary.received()) == 1);
        start(state, -1).awaitSecondary();
        await(() -> lineCount(primary.received()) == 2);
        assertTrue(readRequests(primary.received()).stream().allMatch(String::isEmpty));
    }

    private Child start(Path state, int port, String... arguments) throws Exception {
        return start(InstanceProcess.class, state, port, null, arguments);
    }

    private Child start(Class<?> mainClass, Path state, int port, Path workingDirectory, String... arguments) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "process-");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                mainClass.getName(), state.toString(), directory.toString(), String.valueOf(port)));
        command.addAll(Arrays.asList(arguments));
        Path argumentFile = directory.resolve("java.args");
        Files.write(argumentFile, command.subList(1, command.size()).stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
        Process process = new ProcessBuilder(command.get(0), "@" + argumentFile).redirectErrorStream(true)
                .directory(workingDirectory == null ? directory.toFile() : workingDirectory.toFile()).start();
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

    private static List<String> readRequests(Path path) throws Exception {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            result.add(MAPPER.readValue(line, String.class));
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

    private static final class Child {
        private final Process process;
        private final Path directory;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Thread outputReader;
        private volatile IOException outputFailure;

        private Child(Process process, Path directory) {
            this.process = process;
            this.directory = directory;
            outputReader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(output);
                } catch (IOException exception) {
                    outputFailure = exception;
                }
            }, "instance-test-output");
            outputReader.setDaemon(true);
            outputReader.start();
        }

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
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static void publish(Path path, String value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "status-", ".tmp");
        Files.writeString(temporary, value);
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    public static final class InstanceProcess {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[1]);
            if (!SingleInstanceUtil.registerInstance(Path.of(args[0]), Arrays.copyOfRange(args, 3, args.length))) {
                publish(directory.resolve("status"), "SECONDARY");
                return;
            }
            int port = Integer.parseInt(args[2]);
            ServerSocket server = port < 0 ? null : new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
            publish(directory.resolve("initialized"), String.valueOf(server == null ? -1 : server.getLocalPort()));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.createFile(directory.resolve("stopping"));
                    await(() -> Files.exists(directory.resolve("allow-exit")));
                    if (server != null) { server.close(); }
                } catch (Exception exception) { throw new RuntimeException(exception); }
            }));
            publish(directory.resolve("status"), "PRIMARY");
            AtomicReference<CountDownLatch> deliveryPause = new AtomicReference<>();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String command;
            while ((command = input.readLine()) != null) {
                switch (command) {
                    case "READY" -> SingleInstanceUtil.onReady(argument -> {
                        try {
                            Files.writeString(directory.resolve("received.jsonl"),
                                    MAPPER.writeValueAsString(argument) + "\n", CREATE, APPEND);
                            CountDownLatch pause = deliveryPause.get();
                            if (pause != null) {
                                Files.createFile(directory.resolve("delivery-paused"));
                                if (!pause.await(15, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Test did not resume delivery");
                                }
                            }
                        } catch (Exception exception) { throw new RuntimeException(exception); }
                    });
                    case "EXIT" -> System.exit(0);
                    case "PAUSE_NEXT_DELIVERY" -> {
                        deliveryPause.set(new CountDownLatch(1));
                        Files.createFile(directory.resolve("pause-enabled"));
                    }
                    case "RESUME_DELIVERY" -> deliveryPause.getAndSet(null).countDown();
                    case "REGISTER_AGAIN" -> publish(directory.resolve("registered-again"), String.valueOf(
                            SingleInstanceUtil.registerInstance(Path.of(args[0]), Arrays.copyOfRange(args, 3, args.length))));
                    case "STOP" -> {
                        Files.writeString(directory.resolve("allow-exit"), "");
                        System.exit(0);
                    }
                    case "CRASH" -> Runtime.getRuntime().halt(0);
                    default -> throw new IllegalArgumentException(command);
                }
            }
        }
    }

    public static final class LegacyInstanceProcess {
        public static void main(String[] args) throws Exception {
            Path state = Files.createDirectories(Path.of(args[0]));
            Path directory = Path.of(args[1]);
            try (FileChannel channel = FileChannel.open(state.resolve("app.lock"), CREATE, WRITE);
                 FileLock lock = channel.lock()) {
                publish(directory.resolve("initialized"), "-1");
                publish(directory.resolve("status"), "PRIMARY");
                Thread listener = new Thread(() -> {
                    try {
                        Path ipc = state.resolve("app.ipc");
                        while (!Files.exists(ipc)) { Thread.sleep(25); }
                        Files.writeString(directory.resolve("received.jsonl"),
                                MAPPER.writeValueAsString(Files.readString(ipc)) + "\n");
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                });
                listener.setDaemon(true);
                listener.start();
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
        }
    }
}
