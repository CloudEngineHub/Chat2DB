package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.file.IImportFileRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFileImportTaskExecutorTest {

    @Test
    void releasesStagedFileWhenExecutionFails(@TempDir Path tempDirectory) throws Exception {
        File source = Files.writeString(tempDirectory.resolve("input.sql"), "select 1").toFile();
        RecordingImportFileRegistry registry = new RecordingImportFileRegistry();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(source.getAbsolutePath())
                .format("JSON")
                .importFileId("staged-file-id")
                .build();

        assertThrows(TaskExecutionException.class,
                () -> new SqlFileImportTaskExecutor(registry).execute(spec, null));

        assertEquals("staged-file-id", registry.releasedFileId);
    }

    private static final class RecordingImportFileRegistry implements IImportFileRegistry {

        private String releasedFileId;

        @Override
        public String register(File file, String originalFileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File resolve(String fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void claim(String fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(String fileId) {
            releasedFileId = fileId;
        }
    }
}
