package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.tools.runtime.ProductRuntimeIdentityProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

@Slf4j
public final class SingleInstanceUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Instance instance;

    private SingleInstanceUtil() {
    }

    public static boolean registerDesktopInstance(String[] args) {
        if (!requiresInstanceLock(System.getProperty("chat2db.mode"),
                System.getProperty("chat2db.gui"), System.getProperty("chat2db.runtime.mode"),
                Boolean.getBoolean("chat2db.cli.runtime"), OS.isMacintosh())) {
            return true;
        }
        try {
            return registerInstance(args);
        } catch (IOException exception) {
            log.error("Cannot initialize the desktop instance lock", exception);
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

    public static synchronized boolean registerInstance(String[] args) throws IOException {
        if (instance != null) {
            return true;
        }
        Path directory = Path.of(System.getProperty("user.home"),
                ProductRuntimeIdentityProvider.current().stateDirectoryName());
        Instance candidate = new Instance(directory);
        try {
            if (candidate.acquire(args)) {
                // Keep the OS lock until process exit, including all JVM shutdown hooks.
                instance = candidate;
                return true;
            }
        } catch (IOException | RuntimeException exception) {
            candidate.close();
            throw exception;
        }
        candidate.close();
        return false;
    }

    public static synchronized void onReady(Consumer<String[]> consumer) {
        if (instance != null) {
            instance.onReady(consumer);
        }
    }

    static final class Instance implements AutoCloseable {
        private final Path directory;
        private final Path inbox;
        private FileChannel channel;
        private FileLock lock;
        private WatchService watcher;
        private volatile Consumer<String[]> consumer;
        private volatile boolean closed;

        Instance(Path directory) {
            this.directory = directory;
            this.inbox = directory.resolve("app.ipc.d");
        }

        void onReady(Consumer<String[]> handler) {
            consumer = handler;
        }

        boolean acquire(String[] args) throws IOException {
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve("app.lock"), CREATE, WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ignored) {
                lock = null;
            }
            if (lock == null) {
                send(args);
                log.info("Another desktop instance is running; launch request forwarded.");
                return false;
            }
            Files.createDirectories(inbox);
            watcher = inbox.getFileSystem().newWatchService();
            inbox.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            send(args);
            Thread listener = new Thread(this::listen, "chat2db-instance-requests");
            listener.setDaemon(true);
            listener.start();
            log.info("Successfully acquired the instance lock.");
            return true;
        }

        private static List<String> launchArguments(String[] args) {
            List<String> arguments = new ArrayList<>();
            for (String arg : args) {
                if (arg.startsWith("chat2db-")) {
                    arguments.add(arg);
                } else if (!arg.startsWith("-")) {
                    Path file = Path.of(arg);
                    if (Files.isRegularFile(file)) {
                        arguments.add(file.toAbsolutePath().normalize().toString());
                    }
                }
            }
            return arguments;
        }

        private void send(String[] args) throws IOException {
            Files.createDirectories(inbox);
            String name = "%019d-%s".formatted(System.currentTimeMillis(), UUID.randomUUID());
            Path temporary = Files.createTempFile(inbox, name, ".tmp");
            try {
                MAPPER.writeValue(temporary.toFile(), launchArguments(args));
                Files.move(temporary, inbox.resolve(name + ".json"));
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private void listen() {
            try {
                while (!closed) {
                    dispatchPending();
                    WatchKey key = watcher.poll(200, TimeUnit.MILLISECONDS);
                    if (key != null) {
                        key.pollEvents();
                        if (!key.reset()) {
                            throw new IOException("Desktop request directory is no longer available");
                        }
                    }
                }
            } catch (ClosedWatchServiceException ignored) {
                // Explicit close is used by failed startup and isolated lifecycle tests.
            } catch (IOException exception) {
                log.error("Desktop launch request listener failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private void dispatchPending() throws IOException {
            Consumer<String[]> handler = consumer;
            if (handler == null) {
                return;
            }
            List<Path> requests = new ArrayList<>();
            try (var entries = Files.newDirectoryStream(inbox, "*.json")) {
                entries.forEach(requests::add);
            }
            requests.sort(Comparator.comparing(Path::getFileName));
            for (Path request : requests) {
                try {
                    String[] arguments = MAPPER.readValue(request.toFile(), String[].class);
                    handler.accept(arguments);
                } catch (IOException | RuntimeException exception) {
                    log.error("Cannot process desktop launch request {}", request.getFileName(), exception);
                }
                Files.deleteIfExists(request);
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            try {
                if (watcher != null) {
                    watcher.close();
                }
            } finally {
                if (channel != null) {
                    channel.close();
                }
            }
            // Never unlink app.lock: another process may already be acquiring the same file.
        }
    }
}
