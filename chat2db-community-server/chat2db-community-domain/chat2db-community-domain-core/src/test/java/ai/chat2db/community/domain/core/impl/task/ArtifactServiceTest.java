package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactServiceTest {
    @TempDir
    Path tempDirectory;

    private File journalFile() {
        return tempDirectory.resolve("task-artifact-deletions.json").toFile();
    }

    @Test
    void concurrentDraftsReserveDifferentTargetsAndPublishIndependently() throws IOException {
        ArtifactService service = new ArtifactService(journalFile());
        var first = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        var second = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertNotEquals(first.getTargetFile(), second.getTargetFile());
        Files.writeString(first.getTemporaryFile().toPath(), "first");
        Files.writeString(second.getTemporaryFile().toPath(), "second");

        String firstArtifact = service.publish(first);
        String secondArtifact = service.publish(second);

        assertEquals("first", Files.readString(Path.of(firstArtifact)));
        assertEquals("second", Files.readString(Path.of(secondArtifact)));
        service.deletePublished(firstArtifact);
        assertFalse(Files.exists(Path.of(firstArtifact)));
        assertTrue(Files.exists(Path.of(secondArtifact)));
    }

    @Test
    void failedPublicationReleasesReservedTarget() {
        ArtifactService service = new ArtifactService(journalFile());
        var failed = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");

        assertThrows(IllegalStateException.class, () -> service.publish(failed));

        var replacement = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertEquals(failed.getTargetFile(), replacement.getTargetFile());
        service.deleteDraft(replacement);
    }

    @Test
    void successfulDeletionRemovesTaskArtifactAndArrayEntry() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertEquals("[]", Files.readString(journalFile().toPath()));
    }

    @Test
    void tasksWithoutArtifactsAlsoUseTheDeletionQueue() throws IOException {
        RecordingTaskStorage storage = storage(1L, null);
        storage.beforeDelete = () -> assertEquals(1, queue().get(0).attempts());
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertEquals("[]", Files.readString(journalFile().toPath()));
    }

    @Test
    void storageFailureLeavesStagedFileAndRestartCompletesDeletion() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        RecordingTaskStorage storage = storage(1L, artifact);
        storage.failDeletion = true;
        TaskServiceImpl service = tasks(storage);
        assertThrows(BusinessException.class, () -> service.delete(1L));
        var pending = queue().get(0);
        assertEquals(1, pending.attempts());
        assertTrue(pending.lastError().contains("storage unavailable"));
        assertFalse(Files.exists(artifact));
        assertEquals("old export", Files.readString(Path.of(pending.stagedPath())));
        Files.writeString(artifact, "new export");
        var download = service.resolveArtifact(1L);
        assertEquals("export.csv", download.getFileName());
        assertEquals("old export", Files.readString(Path.of(URI.create(download.getFileUri()))));

        storage.failDeletion = false;
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(storage.get(1L).isEmpty());
        assertEquals("new export", Files.readString(artifact));
        assertFalse(Files.exists(Path.of(pending.stagedPath())));
        assertTrue(queue().isEmpty());
    }

    @Test
    void restartResumesIntentWrittenBeforeTheFileWasStaged() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        var pending = intent(1L, artifact, 1);
        writeQueue(pending);
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertFalse(Files.exists(artifact));
        assertFalse(Files.exists(Path.of(pending.stagedPath())));
        assertTrue(storage.get(1L).isEmpty());
        assertTrue(queue().isEmpty());
    }

    @Test
    void restartAfterTaskRemovalOnlyDeletesTheUniqueStagedFile() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "new export");
        var pending = intent(1L, artifact, 1);
        Files.writeString(Path.of(pending.stagedPath()), "old export");
        writeQueue(pending);
        tasks(new RecordingTaskStorage()).recoverInterruptedArtifactDeletions();
        assertEquals("new export", Files.readString(artifact));
        assertFalse(Files.exists(Path.of(pending.stagedPath())));
        assertTrue(queue().isEmpty());
    }

    @Test
    void missingArtifactIsAlreadyDeleted() throws IOException {
        Path missing = tempDirectory.resolve("missing.csv");
        RecordingTaskStorage storage = storage(1L, missing);
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertTrue(queue().isEmpty());
    }

    @Test
    void repeatedStartupFailuresStopAtTheAttemptLimitAndKeepTheirError() throws IOException {
        Path directory = Files.createDirectory(tempDirectory.resolve("not-a-file"));
        Files.writeString(directory.resolve("child"), "keep");
        RecordingTaskStorage storage = storage(1L, directory);
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        for (int startup = 0; startup < 5; startup++) {
            tasks(storage).recoverInterruptedArtifactDeletions();
        }
        var failed = queue().get(0);
        assertEquals(ArtifactService.MAX_DELETION_ATTEMPTS, failed.attempts());
        assertTrue(failed.lastError().contains("not a regular file"));
        assertTrue(storage.get(1L).isPresent());
        assertEquals("keep", Files.readString(directory.resolve("child")));
    }

    @Test
    void anExplicitDeleteCanRetryAnExhaustedEntryAfterTheProblemIsFixed() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        writeQueue(intent(1L, artifact, ArtifactService.MAX_DELETION_ATTEMPTS));
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertTrue(queue().isEmpty());
    }

    @Test
    void undeletableStagedFileKeepsItsErrorWithoutRestoringTheRemovedTask() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "new export");
        var pending = intent(1L, artifact, 0);
        Path staged = Files.createDirectory(Path.of(pending.stagedPath()));
        Files.writeString(staged.resolve("child"), "keep");
        writeQueue(pending);
        RecordingTaskStorage storage = new RecordingTaskStorage();
        for (int startup = 0; startup < 5; startup++) {
            tasks(storage).recoverInterruptedArtifactDeletions();
        }
        assertTrue(storage.get(1L).isEmpty());
        assertEquals(ArtifactService.MAX_DELETION_ATTEMPTS, queue().get(0).attempts());
        assertTrue(queue().get(0).lastError().contains("DirectoryNotEmptyException"));
        assertEquals("new export", Files.readString(artifact));
        assertEquals("keep", Files.readString(staged.resolve("child")));
    }

    @Test
    void oneExhaustedEntryDoesNotBlockOtherPendingDeletions() throws IOException {
        Path first = Files.writeString(tempDirectory.resolve("first.csv"), "first");
        Path second = Files.writeString(tempDirectory.resolve("second.csv"), "second");
        RecordingTaskStorage storage = storage(1L, first);
        storage.tasks.put(2L, task(2L, second));
        writeQueue(intent(1L, first, ArtifactService.MAX_DELETION_ATTEMPTS), intent(2L, second, 0));
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(Files.exists(first));
        assertTrue(storage.get(1L).isPresent());
        assertFalse(Files.exists(second));
        assertTrue(storage.get(2L).isEmpty());
        assertEquals(List.of(1L), queue().stream().map(ArtifactService.PendingTaskDeletion::taskId).toList());
    }

    @Test
    void alreadyCompletedDeletionIsRemovedEvenAtTheAttemptLimit() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "new export");
        writeQueue(intent(1L, artifact, ArtifactService.MAX_DELETION_ATTEMPTS));
        tasks(new RecordingTaskStorage()).recoverInterruptedArtifactDeletions();
        assertTrue(queue().isEmpty());
        assertEquals("new export", Files.readString(artifact));
    }

    @Test
    void queueWriteFailurePreventsAnyDeletion() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        Files.createDirectory(tempDirectory.resolve(journalFile().getName() + ".part"));
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
        assertFalse(journalFile().exists());
    }

    @Test
    void queueCleanupFailureDoesNotRestoreADeletedTask() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        RecordingTaskStorage storage = storage(1L, artifact);
        Path blockedWrite = tempDirectory.resolve(journalFile().getName() + ".part");
        storage.afterDelete = () -> assertDoesNotThrow(() -> Files.createDirectory(blockedWrite));
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertEquals(1, queue().size());
        Files.delete(blockedWrite);
        Files.writeString(artifact, "new export");
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertEquals("new export", Files.readString(artifact));
        assertTrue(queue().isEmpty());
    }

    @Test
    void corruptQueueIsPreservedAndPreventsNewDestructiveWork() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        Files.writeString(journalFile().toPath(), "broken JSON");
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertEquals("broken JSON", Files.readString(journalFile().toPath()));
        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void concurrentFailedDeletesKeepBothEntriesInTheSameArray() throws Exception {
        Path first = Files.writeString(tempDirectory.resolve("first.csv"), "first");
        Path second = Files.writeString(tempDirectory.resolve("second.csv"), "second");
        RecordingTaskStorage storage = storage(1L, first);
        storage.tasks.put(2L, task(2L, second));
        storage.failDeletion = true;
        TaskServiceImpl service = tasks(storage);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var jobs = List.of(1L, 2L).stream().map(id -> executor.submit(() -> {
                start.await();
                assertThrows(BusinessException.class, () -> service.delete(id));
                return null;
            })).toList();
            start.countDown();
            for (var job : jobs) {
                job.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(List.of(1L, 2L), queue().stream().map(ArtifactService.PendingTaskDeletion::taskId).sorted().toList());
        assertTrue(queue().stream().allMatch(entry -> entry.attempts() == 1 && entry.lastError() != null));
        storage.failDeletion = false;
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(queue().isEmpty());
        assertTrue(storage.tasks.isEmpty());
    }

    @Test
    void activeTasksAndUnownedTasksNeverEnterTheDeletionQueue() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        storage.tasks.get(1L).setStatus(TaskStatus.RUNNING.name());
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        storage.tasks.get(1L).setStatus(TaskStatus.SUCCESS.name());
        storage.tasks.get(1L).setUserId(123L);
        assertThrows(DataNotFoundException.class, () -> tasks(storage).delete(1L));
        assertFalse(journalFile().exists());
        assertEquals("value", Files.readString(artifact));
    }

    private TaskServiceImpl tasks(RecordingTaskStorage storage) {
        return new TaskServiceImpl(storage, null, new ArtifactService(journalFile()));
    }

    private Task task(Long id, Path artifact) {
        return Task.builder().id(id).status(TaskStatus.SUCCESS.name())
                .artifactId(artifact == null ? null : artifact.toString()).build();
    }

    private RecordingTaskStorage storage(Long id, Path artifact) {
        RecordingTaskStorage storage = new RecordingTaskStorage();
        storage.tasks.put(id, task(id, artifact));
        return storage;
    }

    private ArtifactService.PendingTaskDeletion intent(Long id, Path artifact, int attempts) {
        return new ArtifactService.PendingTaskDeletion(id, artifact.toString(),
                artifact.resolveSibling("." + artifact.getFileName() + ".task-delete-" + id).toString(), attempts, null);
    }

    private void writeQueue(ArtifactService.PendingTaskDeletion... pending) throws IOException {
        Files.writeString(journalFile().toPath(), JSON.toJSONString(List.of(pending)));
    }

    private List<ArtifactService.PendingTaskDeletion> queue() {
        return assertDoesNotThrow(() -> JSON.parseArray(Files.readString(journalFile().toPath()),
                ArtifactService.PendingTaskDeletion.class));
    }

    private static final class RecordingTaskStorage implements TaskStorage {
        private final Map<Long, Task> tasks = new LinkedHashMap<>();
        private boolean failDeletion;
        private Runnable beforeDelete = () -> {};
        private Runnable afterDelete = () -> {};

        @Override
        public synchronized Optional<Task> get(Long id) {
            return Optional.ofNullable(tasks.get(id));
        }

        @Override
        public synchronized boolean deleteTerminalTask(Long id, Runnable commitAction) {
            beforeDelete.run();
            if (failDeletion) {
                throw new IllegalStateException("storage unavailable");
            }
            Task task = tasks.get(id);
            if (task == null || !TaskStatus.isTerminal(task.getStatus())) {
                return false;
            }
            tasks.remove(id);
            afterDelete.run();
            if (commitAction != null) {
                commitAction.run();
            }
            return true;
        }

        @Override
        public Task create(Task task, TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean compareAndSetStatus(Long id, String expected, String target,
                TaskStatusPatch patch, TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateProgressIfRunning(Long id, TaskProgress progress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEvents(Long id, long after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long id, Long before, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            throw new UnsupportedOperationException();
        }
    }
}
