package com.javaee.aiservice.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.javaee.aiservice.AiServiceApplication;
import com.javaee.aiservice.service.impl.MinIOServiceImpl;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveLoggingR1Test {

    private static final Pattern OUTPUT_CALL = Pattern.compile(
            "(?s)(?:log\\.(?:trace|debug|info|warn|error)|System\\.(?:out|err)\\.print(?:ln)?)\\s*\\([^;]*?\\);");
    private static final Pattern FORBIDDEN_OUTPUT = Pattern.compile(
            "(?i)(access.?key|secret.?key|jwt|bearer|authorization|object.?key|objectName|"
                    + "original.?file.?name|bucketName|fileUrl|presign|password|username|endpoint|"
                    + "getMessage\\(\\)|printStackTrace)");

    @Test
    void defaultGeneratedUserAutoConfigurationIsExplicitlyExcluded() {
        SpringBootApplication annotation = AiServiceApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(annotation).isNotNull();
        assertThat(Arrays.asList(annotation.exclude()))
                .contains(UserDetailsServiceAutoConfiguration.class);
    }

    @Test
    void minioInitializationLogsNeitherRuntimeMarkersNorExceptionBody() {
        String markerOne = "r1-one-" + UUID.randomUUID();
        String markerTwo = "r1-two-" + UUID.randomUUID();
        MinIOServiceImpl service = new MinIOServiceImpl();
        ReflectionTestUtils.setField(service, "endpoint", "http://127.0.0.1:19000");
        ReflectionTestUtils.setField(service, "accessKey", markerOne);
        ReflectionTestUtils.setField(service, "secretKey", markerTwo);

        Logger logger = (Logger) LoggerFactory.getLogger(MinIOServiceImpl.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            service.init();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }

        String output = captured.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(output).doesNotContain(markerOne, markerTwo);
    }

    @Test
    void hardenedOutputCallsContainNoSensitiveArgumentsOrExceptionBodies() throws IOException {
        for (String relative : List.of(
                "com/javaee/aiservice/agent/execution/AgentExecutionService.java",
                "com/javaee/aiservice/controller/SkillController.java",
                "com/javaee/aiservice/internal/InternalService.java",
                "com/javaee/aiservice/internal/SkillExecutor.java",
                "com/javaee/aiservice/service/FileDeleteService.java",
                "com/javaee/aiservice/service/FileDownloadService.java",
                "com/javaee/aiservice/service/FileUploadService.java",
                "com/javaee/aiservice/service/FileVersionService.java",
                "com/javaee/aiservice/service/RecycleBinService.java",
                "com/javaee/aiservice/service/impl/MinIOServiceImpl.java",
                "com/javaee/aiservice/skills/FileDownloadSkill.java")) {
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
