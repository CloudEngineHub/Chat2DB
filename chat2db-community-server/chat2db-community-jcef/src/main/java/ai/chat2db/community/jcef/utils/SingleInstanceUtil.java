package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.tools.runtime.ProductRuntimeIdentityProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;

import javax.swing.JOptionPane;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

@Slf4j
public final class SingleInstanceUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration EXIT_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static FileLock fileLock;
    private static volatile Consumer<String> argumentConsumer;
    private static volatile ServerSocket ipcServer;
    private static volatile boolean shuttingDown;

    private SingleInstanceUtil() {
    }

    public static boolean registerDesktopInstance(String[] args) {
        if (!requiresInstanceLock(System.getProperty("chat2db.mode"),
                System.getProperty("chat2db.gui"), System.getProperty("chat2db.runtime.mode"),
                Boolean.getBoolean("chat2db.cli.runtime"), OS.isMacintosh())) {
            return true;
        }
        try {
            return registerInstance(Path.of(System.getProperty("user.home"),
                    ProductRuntimeIdentityProvider.current().stateDirectoryName()), args);
        } catch (IOException exception) {
            log.error("Cannot initialize the desktop instance", exception);
            JOptionPane.showMessageDialog(null, exception.getLocalizedMessage(),
                    ProductRuntimeIdentityProvider.current().displayName(), JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return false;
        }
    }

    static boolean requiresInstanceLock(String mode, String gui, String runtime, boolean cli, boolean mac) {
        return "DESKTOP".equalsIgnoreCase(mode) && !"false".equalsIgnoreCase(gui)
                && !"cli".equalsIgnoreCase(runtime) && !cli && !mac;
    }

    static synchronized boolean registerInstance(Path directory, String[] args) throws IOException {
        if (fileLock != null && fileLock.isValid()) {
            return true;
        }
        Files.createDirectories(directory);
        String argument = launchArgument(args);
        // Publish the receiver before another new launcher can inspect its endpoint.
        try (FileChannel launchChannel = FileChannel.open(directory.resolve("app.launch.lock"), CREATE, WRITE);
             FileLock launchLock = launchChannel.lock()) {
            FileChannel channel = FileChannel.open(directory.resolve("app.lock"), CREATE, WRITE);
            boolean primary = false;
            try {
                long deadline = System.nanoTime() + EXIT_TIMEOUT.toNanos();
                boolean checkedLegacy = false;
                boolean waitingForExit = false;
                while (true) {
                    FileLock lock;
                    try {
                        lock = channel.tryLock();
                    } catch (OverlappingFileLockException ignored) {
                        lock = null;
                    }
                    if (lock != null) {
                        startReceiver(directory, argument);
                        // FileLock retains its channel until all JVM shutdown hooks finish.
                        fileLock = lock;
                        primary = true;
                        return true;
                    }
                    Endpoint endpoint = readEndpoint(directory);
                    if (endpoint == null) {
                        if (!checkedLegacy) {
                            checkedLegacy = true;
                            continue;
                        }
                        publish(directory.resolve("app.ipc"), argument.getBytes(StandardCharsets.UTF_8));
                        return false;
                    }
                    if (forward(endpoint, argument)) {
                        return false;
                    }
                    if (!waitingForExit) {
                        log.info("Waiting for the previous desktop instance to exit.");
                        waitingForExit = true;
                    }
                    if (System.nanoTime() >= deadline) {
                        throw new IOException("The running application did not finish exiting");
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for the running application", exception);
                    }
                }
            } finally {
                if (!primary) {
                    channel.close();
                }
            }
        }
    }

    private static void startReceiver(Path directory, String initialArgument) throws IOException {
        Path legacyIpc = directory.resolve("app.ipc");
        IpcVersion lastWrite = ipcVersion(legacyIpc);
        Queue<String> pending = new ConcurrentLinkedQueue<>();
        pending.add(initialArgument);
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        WatchService watcher;
        try {
            watcher = directory.getFileSystem().newWatchService();
        } catch (IOException exception) {
            server.close();
            throw exception;
        }
        try {
            directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            ProcessHandle process = ProcessHandle.current();
            Endpoint endpoint = new Endpoint(process.pid(), process.info().startInstant().toString(),
                    server.getLocalPort(), UUID.randomUUID().toString());
            publish(directory.resolve("app.ipc.endpoint"), MAPPER.writeValueAsBytes(endpoint));
            ipcServer = server;
            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstanceUtil::beginShutdown, "chat2db-instance-exit"));
            startDaemon("chat2db-instance-receiver", () -> receive(server, endpoint.token(), pending));
            startDaemon("chat2db-instance-requests", () -> dispatch(legacyIpc, watcher, lastWrite, pending));
            log.info("Successfully acquired the instance lock.");
        } catch (IOException | RuntimeException exception) {
            server.close();
            watcher.close();
            throw exception;
        }
    }

    public static void onReady(Consumer<String> consumer) {
        argumentConsumer = consumer;
    }

    public static void beginShutdown() {
        shuttingDown = true;
        ServerSocket server = ipcServer;
        if (server != null) {
            try {
                server.close();
            } catch (IOException exception) {
                log.warn("Cannot close desktop instance receiver", exception);
            }
        }
    }

    private static Endpoint readEndpoint(Path directory) throws IOException {
        Path path = directory.resolve("app.ipc.endpoint");
        if (!Files.exists(path)) {
            return null;
        }
        Endpoint endpoint = MAPPER.readValue(path.toFile(), Endpoint.class);
        return ProcessHandle.of(endpoint.pid()).filter(ProcessHandle::isAlive)
                .filter(process -> endpoint.startedAt().equals(process.info().startInstant().toString()))
                .map(process -> endpoint).orElse(null);
    }

    private static boolean forward(Endpoint endpoint, String argument) throws IOException {
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", endpoint.port()), 500);
            } catch (IOException exception) {
                return false;
            }
            socket.setSoTimeout(5000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(endpoint.token());
            byte[] request = argument.getBytes(StandardCharsets.UTF_8);
            if (request.length > MAX_REQUEST_BYTES) {
                throw new IOException("Desktop launch request is too large");
            }
            output.writeInt(request.length);
            output.write(request);
            output.flush();
            // Never retry an ambiguous write: only an explicit refusal is safe to retry.
            return new DataInputStream(socket.getInputStream()).readBoolean();
        }
    }

    private static void receive(ServerSocket server, String token, Queue<String> pending) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                if (!token.equals(input.readUTF())) {
                    continue;
                }
                int length = input.readInt();
                if (length < 0 || length > MAX_REQUEST_BYTES) {
                    continue;
                }
                byte[] request = new byte[length];
                input.readFully(request);
                boolean accepted = !shuttingDown;
                if (accepted) {
                    pending.add(new String(request, StandardCharsets.UTF_8));
                }
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeBoolean(accepted);
                output.flush();
            } catch (SocketTimeoutException ignored) {
                // An incomplete client must not prevent subsequent launches from connecting.
            } catch (IOException exception) {
                if (!server.isClosed()) {
                    log.warn("Cannot receive desktop launch request", exception);
                }
            }
        }
    }

    private static String launchArgument(String[] args) {
        if (args.length == 0 || args[0].startsWith("-")) {
            return "";
        }
        String value = args[0];
        return value.startsWith(ProductRuntimeIdentityProvider.current().protocolScheme() + "://")
                ? value : Path.of(value).toAbsolutePath().normalize().toString();
    }

    private static void publish(Path path, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "app.ipc-", ".tmp");
        try {
            Files.write(temporary, content);
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static IpcVersion ipcVersion(Path ipc) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(ipc, BasicFileAttributes.class);
            return new IpcVersion(attributes.fileKey(), attributes.lastModifiedTime());
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private static void dispatch(Path ipc, WatchService watcher, IpcVersion lastWrite, Queue<String> pending) {
        boolean failed = false;
        boolean deliveryFailed = false;
        boolean ipcChanged = false;
        String lastArgument = null;
        try (watcher) {
            while (!shuttingDown) {
                try {
                    IpcVersion modified = ipcVersion(ipc);
                    if (modified != null && (ipcChanged || !modified.equals(lastWrite))) {
                        String argument = Files.readString(ipc);
                        if (modified.equals(ipcVersion(ipc))) {
                            if (!modified.equals(lastWrite) || !Objects.equals(argument, lastArgument)) {
                                pending.add(argument);
                            }
                            lastWrite = modified;
                            lastArgument = argument;
                            ipcChanged = false;
                        }
                    }
                    failed = false;
                } catch (IOException exception) {
                    if (!failed) {
                        log.warn("Cannot read legacy desktop launch request; waiting for recovery", exception);
                    }
                    failed = true;
                }
                try {
                    Consumer<String> handler = argumentConsumer;
                    while (handler != null && !shuttingDown && !pending.isEmpty()) {
                        handler.accept(pending.element());
                        pending.remove();
                    }
                    deliveryFailed = false;
                } catch (RuntimeException exception) {
                    if (!deliveryFailed) {
                        log.warn("Cannot dispatch desktop launch request; waiting for recovery", exception);
                    }
                    deliveryFailed = true;
                }
                WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);
                if (key != null) {
                    ipcChanged |= key.pollEvents().stream().anyMatch(event -> ipc.getFileName().equals(event.context()));
                    key.reset();
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.error("Desktop launch dispatch failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void startDaemon(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }

    private record Endpoint(long pid, String startedAt, int port, String token) {
    }

    private record IpcVersion(Object fileKey, FileTime modifiedTime) {
    }
}
