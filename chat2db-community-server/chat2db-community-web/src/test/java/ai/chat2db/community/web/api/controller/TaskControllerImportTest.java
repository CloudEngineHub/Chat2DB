package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.file.IImportFileRegistry;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.web.api.converter.task.TaskWebConverter;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskControllerImportTest {

    @Test
    void submitsWebImportFromServerStagedFile(@TempDir Path tempDirectory) throws Exception {
        File stagedFile = Files.writeString(tempDirectory.resolve("file-id.json"), "[]").toFile();
        RecordingImportFileRegistry registry = new RecordingImportFileRegistry(stagedFile);
        AtomicReference<ImportTaskSpec> submittedSpec = new AtomicReference<>();
        TaskController controller = controller(registry, submittedSpec, null);
        TaskImportRequest request = request();

        assertEquals(42L, controller.submitImport(request).getData().getTaskId());

        assertTrue(registry.claimed);
        assertFalse(registry.released);
        assertEquals(stagedFile.getAbsolutePath(), submittedSpec.get().getSourceFile());
        assertEquals("file-id", submittedSpec.get().getImportFileId());
        assertEquals("users.json", submittedSpec.get().getDisplayFileName());
    }

    @Test
    void releasesStagedFileWhenTaskSubmissionFails(@TempDir Path tempDirectory) throws Exception {
        File stagedFile = Files.writeString(tempDirectory.resolve("file-id.sql"), "select 1").toFile();
        RecordingImportFileRegistry registry = new RecordingImportFileRegistry(stagedFile);
        TaskController controller = controller(registry, new AtomicReference<>(), new IllegalStateException("failed"));

        assertThrows(IllegalStateException.class, () -> controller.submitImport(request()));

        assertTrue(registry.claimed);
        assertTrue(registry.released);
    }

    private static TaskImportRequest request() {
        TaskImportRequest request = new TaskImportRequest();
        request.setDataSourceId(1L);
        request.setDatabaseName("app");
        request.setTableName("users");
        request.setTaskType("DATA_FILE_IMPORT");
        request.setFormat("JSON");
        request.setFileId("file-id");
        request.setDisplayFileName("users.json");
        return request;
    }

    private static TaskController controller(RecordingImportFileRegistry registry,
            AtomicReference<ImportTaskSpec> submittedSpec, RuntimeException failure) {
        TaskService taskService = (TaskService) Proxy.newProxyInstance(
                TaskControllerImportTest.class.getClassLoader(), new Class<?>[] {TaskService.class},
                (proxy, method, args) -> {
                    if ("submitImport".equals(method.getName())) {
                        submittedSpec.set((ImportTaskSpec) args[0]);
                        if (failure != null) {
                            throw failure;
                        }
                        return 42L;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new TaskController(taskService, new TaskWebConverter(), null, registry);
    }

    private static final class RecordingImportFileRegistry implements IImportFileRegistry {

        private final File stagedFile;
        private boolean claimed;
        private boolean released;

        private RecordingImportFileRegistry(File stagedFile) {
            this.stagedFile = stagedFile;
        }

        @Override
        public String register(File file, String originalFileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File resolve(String fileId) {
            return stagedFile;
        }

        @Override
        public void claim(String fileId) {
            claimed = true;
        }

        @Override
        public void release(String fileId) {
            released = true;
        }
    }
}
