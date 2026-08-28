package com.javaee.fileservice.security;

import com.javaee.fileservice.FileServiceApplication;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.listener.FileMessageListener;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.service.impl.FileMetadataServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SensitiveLoggingR1Test {

    private static final Pattern OUTPUT_CALL = Pattern.compile(
            "(?s)(?:log\\.(?:trace|debug|info|warn|error)|System\\.(?:out|err)\\.print(?:ln)?)\\s*\\([^;]*?\\);");
    private static final Pattern FORBIDDEN_OUTPUT = Pattern.compile(
            "(?i)(access.?key|secret.?key|jwt|bearer|authorization|object.?key|objectName|"
                    + "original.?file.?name|bucketName|fileUrl|presign|password|username|endpoint|"
                    + "getMessage\\(\\)|printStackTrace)");

    @Test
    void defaultGeneratedUserAutoConfigurationIsExplicitlyExcluded() {
        SpringBootApplication annotation = FileServiceApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(annotation).isNotNull();
        assertThat(Arrays.asList(annotation.exclude()))
                .contains(UserDetailsServiceAutoConfiguration.class);
    }

    @Test
    void metadataFailureDoesNotPrintExceptionBody(CapturedOutput output) {
        String marker = "r1-metadata-" + UUID.randomUUID();
        FileMetadataMapper mapper = mock(FileMetadataMapper.class);
        when(mapper.selectByFileId("synthetic-id")).thenThrow(new RuntimeException(marker));
        FileMetadataServiceImpl service = new FileMetadataServiceImpl();
        ReflectionTestUtils.setField(service, "fileMetadataMapper", mapper);
        ReflectionTestUtils.setField(service, "fileStorageConfig", mock(FileStorageConfig.class));

        assertThat(service.getMetadata("synthetic-id")).isNull();
        assertThat(output).doesNotContain(marker);
    }

    @Test
    void messageListenerDoesNotLogPayloadValues(CapturedOutput output) {
        String markerOne = "r1-file-" + UUID.randomUUID();
        String markerTwo = "r1-name-" + UUID.randomUUID();
        String markerThree = "r1-user-" + UUID.randomUUID();
        Map<String, Object> message = new HashMap<>();
        message.put("fileId", markerOne);
        message.put("fileName", markerTwo);
        message.put("fileSize", 17L);
        message.put("userId", markerThree);

        FileMessageListener listener = new FileMessageListener();
        listener.handleFileUploadMessage(message);
        listener.handleFileDownloadMessage(message);

        assertThat(output).doesNotContain(markerOne, markerTwo, markerThree);
    }

    @Test
    void hardenedOutputCallsContainNoSensitiveArgumentsOrExceptionBodies() throws IOException {
        for (String relative : List.of(
                "com/javaee/fileservice/controller/FileController.java",
                "com/javaee/fileservice/listener/FileMessageListener.java",
                "com/javaee/fileservice/service/impl/FileMetadataServiceImpl.java",
                "com/javaee/fileservice/service/impl/FileServiceImpl.java")) {
            String source = Files.readString(sourceRoot().resolve(relative));
            Matcher calls = OUTPUT_CALL.matcher(source);
            while (calls.find()) {
                assertThat(calls.group())
                        .as("safe output call in %s", relative)
                        .doesNotMatch(FORBIDDEN_OUTPUT);
            }
        }
    }

    private static Path sourceRoot() {
        return Path.of(System.getProperty("basedir"), "src", "main", "java");
    }
}
