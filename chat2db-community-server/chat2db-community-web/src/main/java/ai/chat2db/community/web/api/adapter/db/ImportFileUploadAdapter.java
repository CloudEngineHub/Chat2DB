package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.file.IUploadFileService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@Component
public class ImportFileUploadAdapter {

    private final IUploadFileService<MultipartFile> uploadFileService;

    private final IImportFileStagingService importFileStagingService;

    public ImportFileUploadAdapter(IUploadFileService<MultipartFile> uploadFileService,
            IImportFileStagingService importFileStagingService) {
        this.uploadFileService = uploadFileService;
        this.importFileStagingService = importFileStagingService;
    }

    public String stage(MultipartFile upload) {
        File transferredFile = null;
        try {
            transferredFile = uploadFileService.transferToTempFile(upload);
            return importFileStagingService.stage(transferredFile, upload.getOriginalFilename());
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not store import file", e);
        } finally {
            if (transferredFile != null && transferredFile.isFile()) {
                transferredFile.delete();
            }
        }
    }
}
