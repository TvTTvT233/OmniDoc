package com.javaee.aiservice.review.m0.baseline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentDocumentServiceFailureBaselineTest {

    @Test
    void documentCreationSwallowsFileAndParserFailuresAndKeepsMetadataOnly() {
        String implementation = RepositorySourceEvidence.read(
                "document-service/src/main/java/com/javaee/documentservice/service/impl/DocumentServiceImpl.java");

        assertThat(implementation)
                .contains("content = DocumentParserUtil.parseDocument(fileContent, fileName)")
                .contains("文件已上传，但文档内容解析失败，将仅创建文档元数据")
                .contains("saveVersion(document, \"初始版本\", content)")
                .contains("vo.setContent(content)");
    }

    @Test
    void documentServiceFileClientReturnsWholeBytesAndHasNoTypedParserResult() {
        String client = RepositorySourceEvidence.read(
                "document-service/src/main/java/com/javaee/documentservice/client/FileServiceClient.java");
        String parser = RepositorySourceEvidence.read(
                "document-service/src/main/java/com/javaee/documentservice/util/DocumentParserUtil.java");

        assertThat(client)
                .contains("ResponseEntity<byte[]> downloadFile")
                .contains("ResponseEntity<String> getFileName")
                .doesNotContain("InputStream", "FileArtifactSnapshot");
        assertThat(parser)
                .contains("public static String parseDocument(byte[] fileContent, String fileName)")
                .contains("return new String(fileContent, StandardCharsets.UTF_8)")
                .doesNotContain("ParseResult", "CannotConfirmReason", "sourceLocator");
    }
}
