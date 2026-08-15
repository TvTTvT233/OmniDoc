package com.javaee.aiservice.review.m0.baseline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes the checked-in file-service boundary and the Review M0
 * fail-closed adapter. These tests do not claim a real file-service integration.
 */
class CurrentFileServiceBaselineTest {

    @Test
    void fileAccessIsBucketScopedAndDoesNotExposeAnOwnedFileContract() {
        String security = source("config/SecurityConfig.java");
        String storage = source("config/FileStorageConfig.java");
        String controller = source("controller/FileController.java");
        String metadataController = source("controller/FileMetadataController.java");
        String serviceContract = source("service/FileService.java");
        String mapper = RepositorySourceEvidence.read(
                "file-service/src/main/resources/mapper/FileMetadataMapper.xml");

        assertThat(security)
                .contains(".requestMatchers(\"/api/files/**\").permitAll()");
        assertThat(storage)
                .contains("getHeader(\"X-User-Id\")")
                .contains("UserBucketUtils::bucketNameForUser");
        assertThat(controller)
                .contains("@RequestHeader(value = \"X-User-Id\", required = false) String userId")
                .contains("fileService.download(fileId)");
        assertThat(metadataController)
                .contains("fileMetadataService.getMetadata(fileId)")
                .doesNotContain("RequestUserContext", "getRequiredUserId");
        assertThat(serviceContract)
                .contains("byte[] download(String fileId)")
                .doesNotContain("download(String userId", "getOwnedFile", "openFile");
        assertThat(mapper)
                .contains("WHERE file_id = #{fileId}")
                .doesNotContain("user_id", "create_by =");
    }

    @Test
    void metadataContainsOriginalNameAndRecordedSizeButNoStableSha256() {
        String entity = source("entity/FileMetadata.java");
        String implementation = source("service/impl/FileServiceImpl.java");
        String schema = RepositorySourceEvidence.read("file-service/src/main/resources/file.sql");

        assertThat(entity)
                .contains("private String originalFileName")
                .contains("private long fileSize")
                .contains("private String md5")
                .contains("private String objectKey")
                .doesNotContainIgnoringCase("sha256")
                .doesNotContainIgnoringCase("sha-256");
        assertThat(implementation)
                .contains("fileMetadata.setOriginalFileName(fileName)")
                .contains("fileMetadata.setFileSize(file.getSize())")
                .contains("fileMetadata.setObjectKey(storageFileName)")
                .doesNotContain("setMd5(", "SHA-256", "SHA256");
        assertThat(schema)
                .contains("`original_file_name`", "`file_size`", "`md5`", "`object_key`")
                .doesNotContainIgnoringCase("sha256")
                .doesNotContainIgnoringCase("sha-256");
    }

    @Test
    void metadataAndNameLookupsFailOpenInsteadOfProvidingAStableSnapshot() {
        String metadataService = source("service/impl/FileMetadataServiceImpl.java");
        String controller = source("controller/FileController.java");
        String implementation = source("service/impl/FileServiceImpl.java");

        assertThat(metadataService)
                .contains("获取元数据失败")
                .contains("return null")
                .contains("保存元数据失败");
        assertThat(controller)
                .contains(".orElse(fileId)")
                .contains("获取文件名失败，返回 fileId 作为降级值")
                .contains("return ResponseEntity.ok(fileId)");
        assertThat(implementation)
                .contains("数据库不可用，跳过元数据保存")
                .contains("return fileId");
    }

    @Test
    void downloadsMaterializeTheWholeObjectAndThereIsNoRangeOrStreamApi() {
        String controller = source("controller/FileController.java");
        String serviceContract = source("service/FileService.java");
        String implementation = source("service/impl/FileServiceImpl.java");

        assertThat(controller)
                .contains("public ResponseEntity<byte[]> download")
                .contains("byte[] fileBytes = fileService.download(fileId)");
        assertThat(serviceContract).contains("byte[] download(String fileId)");
        assertThat(implementation)
                .contains("return Files.readAllBytes(storagePath)")
                .contains("return FileUtils.toByteArray(inputStream)")
                .doesNotContain("ResourceRegion", "StreamingResponseBody", "Range");
    }

    @Test
    void reviewM0ProvidesDigestBoundZipEntryStreamingWhileFileServiceIntegrationRemainsFailClosed() {
        String port = RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/review/m0/file/ReviewM0FileAccess.java");
        String verifiedAccess = RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/review/m0/file/VerifiedReviewM0FileAccess.java");
        String entryOpener = RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/review/m0/archive/ZipArchiveEntryOpener.java");
        String failClosed = RepositorySourceEvidence.read(
                "ai-service/src/main/java/com/javaee/aiservice/review/m0/file/FailClosedOwnedFileByteSource.java");

        assertThat(port)
                .contains("InputStream openZipEntry(", "normalizedEntryPath", "expectedEntryDigest");
        assertThat(verifiedAccess)
                .contains("zipEntryOpener.open(archive, normalizedEntryPath, expectedEntryDigest)")
                .contains("new IntegrityVerifyingInputStream");
        assertThat(entryOpener)
                .contains("new ZipInputStream(", "TargetEntryInputStream", "expectedEntryDigest");
        assertThat(failClosed)
                .contains("SERVICE_UNAVAILABLE")
                .contains("file service cannot prove fileId ownership")
                .doesNotContain("X-User-Id", "bucketName", "MinioClient");
    }

    private static String source(String fileServiceRelativePath) {
        return RepositorySourceEvidence.read(
                "file-service/src/main/java/com/javaee/fileservice/" + fileServiceRelativePath);
    }

}
