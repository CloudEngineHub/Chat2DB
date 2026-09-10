package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactServiceTest {

    @TempDir
    Path tempDirectory;

    private File journalFile() {
        return tempDirectory.resolve("artifact-deletions.json").toFile();
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
    void taskDeletionRemovesPublishedArtifactBeforeTaskRecord() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());

        new TaskServiceImpl(storage, null, new ArtifactService(journalFile())).delete(1L);

        assertFalse(Files.exists(artifact));
        assertTrue(storage.deleted);
        assertTrue(storage.get(1L).isEmpty());
    }

    @Test
    void artifactDeletionFailurePreservesTaskRecord() throws IOException {
        Path nonEmptyDirectory = Files.createDirectory(tempDirectory.resolve("artifact-directory"));
        Files.writeString(nonEmptyDirectory.resolve("child"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(nonEmptyDirectory.toString())
                .build());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new TaskServiceImpl(storage, null, new ArtifactService(journalFile())).delete(1L));

        assertEquals(TaskConstants.DELETE_ARTIFACT_FAILED_MESSAGE_CODE, exception.getCode());
        assertFalse(storage.deleted);
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void taskStorageDeletionFailureRestoresPublishedArtifact() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("recover.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());
        storage.failDeletion = true;

        assertThrows(IllegalStateException.class,
                () -> new TaskServiceImpl(storage, null, new ArtifactService(journalFile())).delete(1L));

        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void artifactCommitFailureRestoresTaskAndPublishedArtifact() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("commit-failure.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());
        ArtifactService artifactService = new ArtifactService(journalFile()) {
            @Override
            void commitPublishedDeletion(PublishedArtifactDeletion deletion) {
                throw new IllegalStateException("Could not commit artifact deletion");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new TaskServiceImpl(storage, null, artifactService).delete(1L));

        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void concurrentDeletionCannotRestoreAnOrphanArtifact() throws Exception {
        Path artifact = Files.writeString(tempDirectory.resolve("concurrent.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());
        TaskServiceImpl service = new TaskServiceImpl(storage, null, new ArtifactService(journalFile()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger alreadyDeleted = new AtomicInteger();
        try {
            Future<?> first = executor.submit(() -> {
                start.await();
                try {
                    service.delete(1L);
                    deleted.incrementAndGet();
                } catch (DataNotFoundException ignored) {
                    alreadyDeleted.incrementAndGet();
                }
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                try {
                    service.delete(1L);
                    deleted.incrementAndGet();
                } catch (DataNotFoundException ignored) {
                    alreadyDeleted.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, deleted.get());
        assertEquals(1, alreadyDeleted.get());
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        try (var files = Files.list(tempDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".task-delete-")));
        }
    }

    @Test
    void activeTaskDeletionIsRejectedBeforeArtifactDeletion() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("running.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.RUNNING.name())
                .artifactId(artifact.toString())
                .build());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new TaskServiceImpl(storage, null, new ArtifactService(journalFile())).delete(1L));

        assertEquals(TaskConstants.DELETE_ACTIVE_FORBIDDEN_MESSAGE_CODE, exception.getCode());
        assertTrue(Files.exists(artifact));
        assertFalse(storage.deleted);
    }

    @Test
    void stagedDeletionSurvivesRestartAndIsRestoredWhenTaskSurvives() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("restart-restore.csv"), "value");
        ArtifactService crashed = new ArtifactService(journalFile());
        ArtifactService.PublishedArtifactDeletion staged =
                crashed.stagePublishedDeletion(7L, artifact.toString());

        ArtifactService restarted = new ArtifactService(journalFile());
        assertEquals(1, restarted.loadStagedDeletions().size());
        restarted.recoverStagedDeletion(restarted.loadStagedDeletions().get(0), true);

        assertEquals("value", Files.readString(artifact));
        assertFalse(Files.exists(staged.stagedPath()));
        assertTrue(restarted.loadStagedDeletions().isEmpty(),
                "applied recovery must clear the journal entry");
    }

    @Test
    void journalWriteFailureNeverMovesThePublishedArtifact() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("journal-failure.csv"), "value");
        File unusableJournal = Files.createDirectory(tempDirectory.resolve("journal-directory")).toFile();
        ArtifactService service = new ArtifactService(unusableJournal);

        assertThrows(BusinessException.class,
                () -> service.stagePublishedDeletion(7L, artifact.toString()));

        assertEquals("value", Files.readString(artifact));
        try (var files = Files.list(tempDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".task-delete-")));
        }
    }

    @Test
    void stagedDeletionSurvivesRestartAndIsSweptWhenTaskRecordIsGone() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("restart-sweep.csv"), "value");
        ArtifactService crashed = new ArtifactService(journalFile());
        ArtifactService.PublishedArtifactDeletion staged =
                crashed.stagePublishedDeletion(8L, artifact.toString());

        ArtifactService restarted = new ArtifactService(journalFile());
        restarted.recoverStagedDeletion(restarted.loadStagedDeletions().get(0), false);

        assertFalse(Files.exists(staged.stagedPath()));
        assertFalse(Files.exists(artifact), "committed deletion must keep the artifact removed");
        assertTrue(restarted.loadStagedDeletions().isEmpty());
    }

    @Test
    void missingStagedFileAtRecoveryClearsJournalWithoutThrowing() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("already-gone.csv"), "value");
        ArtifactService crashed = new ArtifactService(journalFile());
        ArtifactService.PublishedArtifactDeletion staged =
                crashed.stagePublishedDeletion(9L, artifact.toString());
        Files.delete(staged.stagedPath());

        ArtifactService restarted = new ArtifactService(journalFile());
        restarted.recoverStagedDeletion(restarted.loadStagedDeletions().get(0), true);

        assertFalse(Files.exists(staged.stagedPath()), "staged file stays gone; nothing to restore");
        assertTrue(restarted.loadStagedDeletions().isEmpty());
    }

    @Test
    void commitAndRestoreClearJournalEntries() throws IOException {
        Path committed = Files.writeString(tempDirectory.resolve("committed.csv"), "value");
        Path restored = Files.writeString(tempDirectory.resolve("restored.csv"), "value");
        ArtifactService service = new ArtifactService(journalFile());
        ArtifactService.PublishedArtifactDeletion commitDeletion =
                service.stagePublishedDeletion(11L, committed.toString());
        ArtifactService.PublishedArtifactDeletion restoreDeletion =
                service.stagePublishedDeletion(12L, restored.toString());
        assertEquals(2, service.loadStagedDeletions().size());

        service.commitPublishedDeletion(commitDeletion);
        assertEquals(1, service.loadStagedDeletions().size());

        service.restorePublishedDeletion(restoreDeletion);
        assertTrue(service.loadStagedDeletions().isEmpty());
        assertEquals("value", Files.readString(restored));
    }

    @Test
    void journalCleanupFailureDoesNotRollBackDeletedTask() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("commit.csv"), "value");
        Path blockedWrite = tempDirectory.resolve("artifact-deletions.json.part");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L).status(TaskStatus.SUCCESS.name()).artifactId(artifact.toString()).build());
        ArtifactService service = new ArtifactService(journalFile()) {
            @Override
            void commitPublishedDeletion(PublishedArtifactDeletion deletion) {
                assertDoesNotThrow(() -> Files.createDirectory(blockedWrite));
                super.commitPublishedDeletion(deletion);
            }
        };

        assertDoesNotThrow(() -> new TaskServiceImpl(storage, null, service).delete(1L));

        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertEquals(1, service.loadStagedDeletions().size());
        Files.delete(blockedWrite);
        ArtifactService restarted = new ArtifactService(journalFile());
        new TaskServiceImpl(storage, null, restarted).recoverInterruptedArtifactDeletions();
        assertTrue(restarted.loadStagedDeletions().isEmpty());
        assertTrue(new ArtifactService(journalFile()).loadStagedDeletions().isEmpty());
    }

    @Test
    void startupRecoveryPreservesNewExportAndRetriesAfterConflictIsRemoved() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        ArtifactService service = new ArtifactService(journalFile());
        var deletion = service.stagePublishedDeletion(1L, artifact.toString());
        var next = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        Files.writeString(next.getTemporaryFile().toPath(), "new export");
        assertEquals(artifact, Path.of(service.publish(next)));
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L).status(TaskStatus.SUCCESS.name()).artifactId(artifact.toString()).build());
        ArtifactService restarted = new ArtifactService(journalFile());
        TaskServiceImpl tasks = new TaskServiceImpl(storage, null, restarted);

        tasks.recoverInterruptedArtifactDeletions();

        assertEquals("new export", Files.readString(artifact));
        assertEquals("old export", Files.readString(deletion.stagedPath()));
        assertEquals(1, restarted.loadStagedDeletions().size());
        var download = tasks.resolveArtifact(1L);
        assertEquals("export.csv", download.getFileName());
        assertEquals("old export", Files.readString(Path.of(URI.create(download.getFileUri()))));
        Files.delete(artifact);
        tasks.recoverInterruptedArtifactDeletions();
        assertEquals("old export", Files.readString(artifact));
        assertFalse(Files.exists(deletion.stagedPath()));
        assertTrue(restarted.loadStagedDeletions().isEmpty());
    }

    @Test
    void deletingTaskAfterRecoveryConflictOnlyDeletesItsStagedArtifact() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        ArtifactService service = new ArtifactService(journalFile());
        var deletion = service.stagePublishedDeletion(1L, artifact.toString());
        Files.writeString(artifact, "new export");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L).status(TaskStatus.SUCCESS.name()).artifactId(artifact.toString()).build());
        ArtifactService restarted = new ArtifactService(journalFile());
        TaskServiceImpl tasks = new TaskServiceImpl(storage, null, restarted);
        tasks.recoverInterruptedArtifactDeletions();

        tasks.delete(1L);

        assertEquals("new export", Files.readString(artifact));
        assertFalse(Files.exists(deletion.stagedPath()));
        assertTrue(storage.get(1L).isEmpty());
        assertTrue(new ArtifactService(journalFile()).loadStagedDeletions().isEmpty());
    }

    @Test
    void rollbackPreservesExistingFileAndPendingRecovery() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        ArtifactService service = new ArtifactService(journalFile());
        var deletion = service.stagePublishedDeletion(1L, artifact.toString());
        Files.writeString(artifact, "new export");

        assertThrows(BusinessException.class, () -> service.restorePublishedDeletion(deletion));

        assertEquals("new export", Files.readString(artifact));
        assertEquals("old export", Files.readString(deletion.stagedPath()));
        assertEquals(1, new ArtifactService(journalFile()).loadStagedDeletions().size());
    }

    @Test
    void failedJournalRemovalRemainsRetryableWithoutRestart() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        ArtifactService service = new ArtifactService(journalFile());
        var deletion = service.stagePublishedDeletion(1L, artifact.toString());
        Path blockedWrite = Files.createDirectory(tempDirectory.resolve("artifact-deletions.json.part"));

        assertThrows(BusinessException.class, () -> service.restorePublishedDeletion(deletion));

        assertEquals("value", Files.readString(artifact));
        assertEquals(1, service.loadStagedDeletions().size());
        Files.delete(blockedWrite);
        service.restorePublishedDeletion(deletion);
        assertTrue(service.loadStagedDeletions().isEmpty());
        assertTrue(new ArtifactService(journalFile()).loadStagedDeletions().isEmpty());
    }

    @Test
    void failedJournalAppendDoesNotLeakIntoLaterSuccessfulWrite() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("first.csv"), "first");
        ArtifactService service = new ArtifactService(journalFile());
        Path blockedWrite = Files.createDirectory(tempDirectory.resolve("artifact-deletions.json.part"));

        assertThrows(BusinessException.class, () -> service.stagePublishedDeletion(1L, artifact.toString()));

        assertTrue(service.loadStagedDeletions().isEmpty());
        assertEquals("first", Files.readString(artifact));
        Files.delete(blockedWrite);
        Path second = Files.writeString(tempDirectory.resolve("second.csv"), "second");
        service.stagePublishedDeletion(2L, second.toString());
        var entries = new ArtifactService(journalFile()).loadStagedDeletions();
        assertEquals(1, entries.size());
        assertEquals(2L, entries.get(0).taskId());
    }

    private static final class RecordingTaskStorage implements TaskStorage {

        private Task task;

        private boolean deleted;

        private boolean failDeletion;

        private RecordingTaskStorage(Task task) {
            this.task = task;
        }

        @Override
        public synchronized Optional<Task> get(Long taskId) {
            return Optional.ofNullable(task);
        }

        @Override
        public synchronized boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            if (failDeletion) {
                throw new IllegalStateException("Could not delete task record");
            }
            deleted = task != null && TaskStatus.isTerminal(task.getStatus());
            if (deleted) {
                Task deletedTask = task;
                task = null;
                try {
                    commitAction.run();
                } catch (RuntimeException e) {
                    task = deletedTask;
                    deleted = false;
                    throw e;
                }
            }
            return deleted;
        }

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
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
        public List<Task> listNonTerminalTasks() {
            throw new UnsupportedOperationException();
        }
    }
}
