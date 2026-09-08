package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.file.IImportFileRegistry;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.response.task.TaskSubmitResponse;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbImportPreviewControllerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void importExecuteRequestParticipatesInDatasourceBinding() {
        assertInstanceOf(DataSourceBaseRequest.class, new DbImportPreviewController.ImportExecuteRequest());
    }

    @Test
    void previewRequestUsesJsonBodyBinding() throws Exception {
        assertInstanceOf(DataSourceBaseRequest.class, new DbImportPreviewController.ImportPreviewRequest());
        Method preview = DbImportPreviewController.class.getDeclaredMethod(
                "preview", DbImportPreviewController.ImportPreviewRequest.class);

        assertTrue(preview.getParameters()[0].isAnnotationPresent(RequestBody.class));
    }

    @Test
    void previewCarriesSchemaNameIntoMetadataRequest() throws Exception {
        File stagedFile = tempDirectory.resolve("orders.csv").toFile();
        Files.writeString(stagedFile.toPath(), "name\nAlice\n");
        AtomicReference<String> capturedSchemaName = new AtomicReference<>();
        IDbImportPreviewService importPreviewService = (dataSourceId, databaseName, schemaName, tableName, file) -> {
            capturedSchemaName.set(schemaName);
            return ImportPreview.builder().build();
        };
        DbImportPreviewController controller = new DbImportPreviewController();
        setField(controller, "importPreviewService", importPreviewService);
        setField(controller, "importFileRegistry", new CapturingImportFileRegistry(stagedFile));
        DbImportPreviewController.ImportPreviewRequest request =
                new DbImportPreviewController.ImportPreviewRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setTableName("orders");
        request.setFileId("file-1");

        controller.preview(request);

        assertEquals("public", capturedSchemaName.get());
    }

    @Test
    void executeCarriesSchemaNameIntoSubmittedTaskTargetSnapshot() throws Exception {
        File stagedFile = tempDirectory.resolve("orders.csv").toFile();
        Files.writeString(stagedFile.toPath(), "name\nAlice\n");
        CapturingTaskService taskService = new CapturingTaskService();
        CapturingImportFileRegistry importFileRegistry = new CapturingImportFileRegistry(stagedFile);
        DbImportPreviewController controller = new DbImportPreviewController();
        setField(controller, "taskService", taskService);
        setField(controller, "importFileRegistry", importFileRegistry);
        DbImportPreviewController.ImportExecuteRequest request =
                new DbImportPreviewController.ImportExecuteRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setTableName("orders");
        request.setFileId("file-1");
        request.setMappings(List.of(ImportColumnMapping.builder()
                .sourceColumn("Name").targetColumn("name").build()));

        DataResult<TaskSubmitResponse> result = controller.execute(request);

        assertEquals(42L, result.getData().getTaskId());
        assertTrue(importFileRegistry.claimed);
        ImportTaskSpec spec = taskService.submittedImport;
        assertEquals(7L, spec.getTarget().getDataSourceId());
        assertEquals("app", spec.getTarget().getDatabaseName());
        assertEquals("public", spec.getTarget().getSchemaName());
        assertEquals("orders", spec.getTarget().getTableName());
        assertEquals("file-1", spec.getImportFileId());
    }

    @Test
    void executeRejectsDuplicateTargetMappingsBeforeClaimingTheFile() throws Exception {
        File stagedFile = tempDirectory.resolve("orders.csv").toFile();
        Files.writeString(stagedFile.toPath(), "name,email\nAlice,a@example.com\n");
        CapturingImportFileRegistry importFileRegistry = new CapturingImportFileRegistry(stagedFile);
        DbImportPreviewController controller = new DbImportPreviewController();
        setField(controller, "importFileRegistry", importFileRegistry);
        DbImportPreviewController.ImportExecuteRequest request =
                new DbImportPreviewController.ImportExecuteRequest();
        request.setMappings(List.of(
                ImportColumnMapping.builder().sourceColumn("name").targetColumn("name").build(),
                ImportColumnMapping.builder().sourceColumn("email").targetColumn("NAME").build()));

        assertThrows(IllegalArgumentException.class, () -> controller.execute(request));
        assertFalse(importFileRegistry.claimed);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class CapturingImportFileRegistry implements IImportFileRegistry {

        private final File file;

        private boolean claimed;

        private CapturingImportFileRegistry(File file) {
            this.file = file;
        }

        @Override
        public String register(File file, String originalFileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File resolve(String fileId) {
            return file;
        }

        @Override
        public void claim(String fileId) {
            claimed = true;
        }

        @Override
        public void release(String fileId) {
        }
    }

    private static final class CapturingTaskService implements TaskService {

        private ImportTaskSpec submittedImport;

        @Override
        public Long submitExport(ExportTaskSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long submitImport(ImportTaskSpec spec) {
            submittedImport = spec;
            return 42L;
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Task get(Long taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Long taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int activeTaskCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prepareForUserExit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortUserExit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskDownload resolveArtifact(Long taskId) {
            throw new UnsupportedOperationException();
        }
    }
}
