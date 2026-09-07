package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.tools.runtime.ProductRuntimeIdentityProvider;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

@Slf4j
public final class SingleInstanceUtil {
    private static FileLock fileLock;
    private static volatile Consumer<String[]> argumentConsumer;

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

    static synchronized boolean registerInstance(Path directory, String[] args) throws IOException {
        if (fileLock != null && fileLock.isValid()) {
            return true;
        }
        Files.createDirectories(directory);
        Path ipc = directory.resolve("app.ipc");
        FileTime lastWrite = modifiedTime(ipc);
        String argument = launchArgument(args);
        FileChannel channel = FileChannel.open(directory.resolve("app.lock"), CREATE, WRITE);
        boolean primary = false;
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ignored) {
                lock = null;
            }
            if (lock == null) {
                Path temporary = Files.createTempFile(directory, "app.ipc-", ".tmp");
                try {
                    Files.writeString(temporary, argument);
                    Files.move(temporary, ipc, ATOMIC_MOVE, REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                log.info("Another desktop instance is running; duplicate startup stopped.");
                return false;
            }
            WatchService watcher = directory.getFileSystem().newWatchService();
            try {
                directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                Thread listener = new Thread(() -> listen(ipc, watcher, lastWrite, argument), "chat2db-instance-requests");
                listener.setDaemon(true);
                listener.start();
            } catch (IOException | RuntimeException exception) {
                watcher.close();
                throw exception;
            }
            // FileLock retains its channel; the OS releases both after JVM shutdown hooks finish.
            fileLock = lock;
            primary = true;
            log.info("Successfully acquired the instance lock.");
            return true;
        } finally {
            if (!primary) {
                channel.close();
            }
        }
    }

    public static void onReady(Consumer<String[]> consumer) {
        argumentConsumer = consumer;
    }

    private static String launchArgument(String[] args) {
        if (args.length == 0 || args[0].startsWith("-")) {
            return "";
        }
        String value = args[0];
        return value.startsWith(ProductRuntimeIdentityProvider.current().protocolScheme() + "://")
                ? value : Path.of(value).toAbsolutePath().normalize().toString();
    }

    private static String[] arguments(String argument) {
        return argument.isEmpty() ? new String[0] : new String[]{argument};
    }

    private static FileTime modifiedTime(Path ipc) throws IOException {
        try {
            return Files.getLastModifiedTime(ipc);
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private static void listen(Path ipc, WatchService watcher, FileTime lastWrite, String initialArgument) {
        Queue<String[]> pending = new ArrayDeque<>();
        pending.add(arguments(initialArgument));
        boolean failed = false;
        try (watcher) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    FileTime modified = modifiedTime(ipc);
                    if (modified != null && !modified.equals(lastWrite)) {
                        String argument = Files.readString(ipc);
                        if (modified.equals(modifiedTime(ipc))) {
                            pending.add(arguments(argument));
                            lastWrite = modified;
                        }
                    }
                    Consumer<String[]> handler = argumentConsumer;
                    while (handler != null && !pending.isEmpty()) {
                        handler.accept(pending.element());
                        pending.remove();
                    }
                    failed = false;
                } catch (IOException | RuntimeException exception) {
                    if (!failed) {
                        log.warn("Cannot process desktop launch request; waiting for recovery", exception);
                    }
                    failed = true;
                }
                WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
            }
        } catch (IOException exception) {
            log.warn("Cannot close desktop launch listener", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
