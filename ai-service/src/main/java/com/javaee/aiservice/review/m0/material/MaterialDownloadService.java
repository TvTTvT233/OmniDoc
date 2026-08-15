package com.javaee.aiservice.review.m0.material;

import com.javaee.aiservice.review.m0.file.FileArtifactSnapshot;
import com.javaee.aiservice.review.m0.file.FormalContentTypes;
import com.javaee.aiservice.review.m0.file.OwnedFileAccessException;
import com.javaee.aiservice.review.m0.file.ReviewM0FileAccess;
import com.javaee.aiservice.review.m0.model.LogicalFileName;
import com.javaee.aiservice.review.m0.model.MaterialKind;
import com.javaee.aiservice.review.m0.model.SubmissionMaterial;
import com.javaee.aiservice.review.m0.model.SubmissionSource;

import java.io.InputStream;
import java.util.Objects;

public final class MaterialDownloadService {
    private final ReviewM0MaterialRepository repository;
    private final ReviewM0FileAccess fileAccess;

    public MaterialDownloadService(
            ReviewM0MaterialRepository repository,
            ReviewM0FileAccess fileAccess
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.fileAccess = Objects.requireNonNull(fileAccess);
    }

    public DownloadPayload openOwned(
            String ownerId,
            String caseId,
            String materialId
    ) {
        SubmissionMaterial material = repository.findMaterialOwned(ownerId, caseId, materialId)
                .orElseThrow(MaterialErrors::resourceNotFound);
        SubmissionSource source = repository.findSourceOwned(ownerId, caseId, material.sourceId())
                .orElseThrow(MaterialErrors::resourceNotFound);
        FileArtifactSnapshot current = fileAccess.getOwnedFile(ownerId, source.fileId());
        if (!current.available() || !sameSourceFacts(source, current)) {
            throw new OwnedFileAccessException(
                    OwnedFileAccessException.Kind.SOURCE_UNAVAILABLE,
                    "Material source no longer matches its immutable snapshot");
        }
        LogicalFileName logicalName = new LogicalFileName(material.submissionFileName());
        logicalName.requireType(material.formalFileType());

        InputStream stream;
        long contentLength;
        if (material.kind() == MaterialKind.TOP_LEVEL_FILE) {
            stream = fileAccess.openFile(ownerId, source.fileId());
            contentLength = source.fileSizeBytes();
        } else {
            stream = fileAccess.openZipEntry(
                    ownerId,
                    source.fileId(),
                    material.normalizedEntryPath(),
                    material.contentDigest());
            contentLength = -1;
        }
        return new DownloadPayload(
                material.materialId(),
                FormalContentTypes.responseContentType(material.formalFileType()),
                contentLength,
                ContentDisposition.attachment(logicalName.normalized()),
                stream);
    }

    private static boolean sameSourceFacts(SubmissionSource source, FileArtifactSnapshot current) {
        return source.fileId().equals(current.fileId())
                && source.originalFileNameSnapshot().equals(current.originalFileName())
                && Objects.equals(source.declaredContentType(), current.declaredContentType())
                && source.fileSizeBytes() == current.sizeBytes()
                && source.fileDigest().equals(current.contentDigest())
                && Objects.equals(source.storageVersion(), current.storageVersion());
    }
}
