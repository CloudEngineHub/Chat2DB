package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ArtifactService {

    private static final String DRAFT_FILE_SUFFIX = ".part";

    private static final String DELETION_FILE_MARKER = ".task-delete-";

    private final Set<Path> reservedTargets = ConcurrentHashMap.newKeySet();

    private final File deletionJournalFile;

    static final int MAX_DELETION_ATTEMPTS = 3;

    public ArtifactService() {
        this(new File(ConfigUtils.getEnvBasePath(), "task-artifact-deletions.json"));
    }

    ArtifactService(File deletionJournalFile) {
        this.deletionJournalFile = deletionJournalFile;
    }

    ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType) {
        File directory = resolveDirectory(outputDirectory);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create artifact directory");
        }
        String safeFileName = safeFileName(fileName);
        File target = reserveAvailableTarget(directory, safeFileName);
        File temporary = new File(directory,
                ".task-" + taskId + "-" + UUID.randomUUID() + "-" + safeFileName + DRAFT_FILE_SUFFIX);
        return ArtifactDraft.builder()
                .temporaryFile(temporary)
                .targetFile(target)
                .mediaType(mediaType)
                .build();
    }

    String publish(ArtifactDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Artifact draft is incomplete");
        }
        try {
            if (draft.getTemporaryFile() == null || draft.getTargetFile() == null) {
                throw new IllegalArgumentException("Artifact draft is incomplete");
            }
            Path source = draft.getTemporaryFile().toPath();
            Path target = draft.getTargetFile().toPath();
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                throw new IllegalStateException("Artifact draft is not readable");
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not publish artifact", e);
        } finally {
            releaseTarget(draft);
        }
    }

    void deleteDraft(ArtifactDraft draft) {
        if (draft == null) {
            return;
        }
        try {
            if (draft.getTemporaryFile() != null) {
                Files.deleteIfExists(draft.getTemporaryFile().toPath());
            }
        } catch (IOException ignored) {
            // A failed cleanup must not overwrite the task's terminal result.
        } finally {
            releaseTarget(draft);
        }
    }

    void deletePublished(String artifactId) {
        if (StringUtils.isBlank(artifactId)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(artifactId));
        } catch (IOException ignored) {
            // Best effort rollback when a terminal compare-and-set loses.
        }
    }

    synchronized void deleteTask(Task task, TaskStorage storage) {
        if (storage.get(task.getId()).isEmpty()) {
            throw new DataNotFoundException();
        }
        try {
            List<PendingTaskDeletion> pending = readPendingDeletions();
            PendingTaskDeletion deletion = pending.stream()
                    .filter(entry -> Objects.equals(entry.taskId(), task.getId())).findFirst().orElse(null);
            if (deletion == null) {
                Path original = StringUtils.isBlank(task.getArtifactId()) ? null
                        : Path.of(task.getArtifactId()).toAbsolutePath().normalize();
                String staged = original == null ? null : original.resolveSibling("." + original.getFileName()
                        + DELETION_FILE_MARKER + UUID.randomUUID()).toString();
                deletion = new PendingTaskDeletion(task.getId(), original == null ? null : original.toString(),
                        staged, 0, null);
                pending.add(deletion);
            }
            completeDeletion(pending, deletion, storage);
        } catch (Exception e) {
            throw artifactDeletionFailure(task.getArtifactId(), e);
        }
    }

    synchronized void retryPendingDeletions(TaskStorage storage) {
        try {
            List<PendingTaskDeletion> pending = readPendingDeletions();
            for (PendingTaskDeletion deletion : List.copyOf(pending)) {
                try {
                    if (storage.get(deletion.taskId()).isEmpty() && (deletion.stagedPath() == null
                            || Files.notExists(Path.of(deletion.stagedPath())))) {
                        pending.remove(deletion);
                        writePendingDeletions(pending);
                    } else if (deletion.attempts() < MAX_DELETION_ATTEMPTS) {
                        completeDeletion(pending, deletion, storage);
                    }
                } catch (Exception e) {
                    log.error("Could not complete pending deletion for task {}", deletion.taskId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Could not read pending task deletions from {}", deletionJournalFile, e);
        }
    }

    private void completeDeletion(List<PendingTaskDeletion> pending, PendingTaskDeletion deletion,
            TaskStorage storage) throws IOException {
        int index = pending.indexOf(deletion);
        PendingTaskDeletion attempted = new PendingTaskDeletion(deletion.taskId(), deletion.originalPath(),
                deletion.stagedPath(), deletion.attempts() + 1, null);
        pending.set(index, attempted);
        // Persist the intent and attempt count before touching either the task or its artifact.
        writePendingDeletions(pending);
        try {
            Path staged = deletion.stagedPath() == null ? null : Path.of(deletion.stagedPath());
            if (storage.get(deletion.taskId()).isPresent()) {
                if (staged != null && Files.notExists(staged)) {
                    Path original = Path.of(deletion.originalPath());
                    if (Files.exists(original)) {
                        if (!Files.isRegularFile(original)) {
                            throw new IOException("Task artifact is not a regular file: " + original);
                        }
                        Files.move(original, staged);
                    }
                }
                if (!storage.deleteTerminalTask(deletion.taskId(), null)) {
                    throw new IOException("Task is not terminal: " + deletion.taskId());
                }
            }
            // Once the task record is gone, its original path may belong to a newer export.
            if (staged != null) {
                Files.deleteIfExists(staged);
            }
        } catch (Exception e) {
            pending.set(index, new PendingTaskDeletion(attempted.taskId(), attempted.originalPath(),
                    attempted.stagedPath(), attempted.attempts(), e.toString()));
            try {
                writePendingDeletions(pending);
            } catch (Exception writeFailure) {
                e.addSuppressed(writeFailure);
            }
            throw e;
        }
        pending.remove(index);
        writePendingDeletions(pending);
    }

    synchronized File resolvePublishedArtifact(Long taskId, String artifactId) {
        for (PendingTaskDeletion deletion : readPendingDeletions()) {
            if (Objects.equals(taskId, deletion.taskId()) && deletion.stagedPath() != null
                    && Files.exists(Path.of(deletion.stagedPath()))) {
                return new File(deletion.stagedPath());
            }
        }
        return new File(artifactId);
    }

    private List<PendingTaskDeletion> readPendingDeletions() {
        if (!deletionJournalFile.exists()) {
            return new ArrayList<>();
        }
        List<PendingTaskDeletion> pending = JSON.parseArray(
                FileUtil.readUtf8String(deletionJournalFile), PendingTaskDeletion.class);
        if (pending == null || pending.stream().anyMatch(entry -> entry == null || entry.taskId() == null)) {
            throw new IllegalStateException("Invalid task deletion queue: " + deletionJournalFile);
        }
        return pending;
    }

    private void writePendingDeletions(List<PendingTaskDeletion> pending) throws IOException {
        FileUtil.mkParentDirs(deletionJournalFile);
        Path temporary = deletionJournalFile.toPath().resolveSibling(deletionJournalFile.getName() + DRAFT_FILE_SUFFIX);
        Files.writeString(temporary, JSON.toJSONString(pending));
        try {
            Files.move(temporary, deletionJournalFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, deletionJournalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath) {
        boolean cleaned = true;
        if (StringUtils.isNotBlank(temporaryPath)) {
            Path temporary = Path.of(temporaryPath).toAbsolutePath().normalize();
            String fileName = temporary.getFileName() == null ? "" : temporary.getFileName().toString();
            if (fileName.startsWith(".task-" + taskId + "-") && fileName.endsWith(DRAFT_FILE_SUFFIX)) {
                cleaned = deleteQuietly(temporary);
            }
        }
        if (StringUtils.isNotBlank(publishedPath)) {
            cleaned = deleteQuietly(Path.of(publishedPath).toAbsolutePath().normalize()) && cleaned;
        }
        return cleaned;
    }

    private File resolveDirectory(String outputDirectory) {
        if (StringUtils.isNotBlank(outputDirectory)) {
            return new File(outputDirectory);
        }
        File downloads = new File(System.getProperty("user.home"), "Downloads");
        if (downloads.exists() || downloads.mkdirs()) {
            return downloads;
        }
        return new File(ConfigUtils.getEnvBasePath(), "artifacts");
    }

    private String safeFileName(String fileName) {
        String safeName = new File(StringUtils.defaultIfBlank(fileName, "chat2db-export")).getName();
        if (StringUtils.isBlank(safeName) || ".".equals(safeName) || "..".equals(safeName)) {
            return "chat2db-export";
        }
        return safeName;
    }

    private File reserveAvailableTarget(File directory, String fileName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        String suffix = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 0; index < 1000; index++) {
            String candidateName = index == 0 ? fileName : baseName + "_" + index + suffix;
            File candidate = new File(directory, candidateName);
            Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
            if (!Files.exists(candidatePath) && reservedTargets.add(candidatePath)) {
                return candidate;
            }
        }
        while (true) {
            File candidate = new File(directory, baseName + "_" + UUID.randomUUID() + suffix);
            Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
            if (!Files.exists(candidatePath) && reservedTargets.add(candidatePath)) {
                return candidate;
            }
        }
    }

    private void releaseTarget(ArtifactDraft draft) {
        if (draft.getTargetFile() != null) {
            reservedTargets.remove(draft.getTargetFile().toPath().toAbsolutePath().normalize());
        }
    }

    private boolean deleteQuietly(Path path) {
        try {
            if (Files.notExists(path)) {
                return true;
            }
            if (Files.isRegularFile(path)) {
                Files.deleteIfExists(path);
                return Files.notExists(path);
            }
            return false;
        } catch (IOException ignored) {
            // The task is still converged to a terminal state even if filesystem cleanup fails.
            return false;
        }
    }

    private BusinessException artifactDeletionFailure(String artifactId, Exception cause) {
        return new BusinessException(TaskConstants.DELETE_ARTIFACT_FAILED_MESSAGE_CODE,
                new Object[]{artifactId}, cause);
    }

    record PendingTaskDeletion(Long taskId, String originalPath, String stagedPath, int attempts, String lastError) {
    }
}
