package com.javaee.fileservice.security;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class MyBatisRuntimeLoggingTest {
    @AfterEach
    void restoreLoggerFactory() {
        LogFactory.useSlf4jLogging();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-local.yml"})
    void configuredMyBatisDoesNotPrintMetadataValuesOrSql(String resource, CapturedOutput output)
            throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String logger = properties.getProperty("mybatis-plus.configuration.log-impl");
        assertThat(logger).isEqualTo(NoLoggingImpl.class.getName());
        assertThat(properties.getProperty("logging.level.com.javaee.fileservice")).isEqualTo("info");
        assertThat(properties.getProperty("logging.exception-conversion-word")).isEqualTo("%nopex");
        String marker = "private-metadata-" + UUID.randomUUID();
        query(logger, marker);
        assertThat(output).doesNotContain(marker, "Preparing:", "Parameters:", "<==", "==>");
    }

    @Test
    void probeDetectsTheFormerStdoutConfiguration(CapturedOutput output) throws Exception {
        String marker = "synthetic-detection-" + UUID.randomUUID();
        query(StdOutImpl.class.getName(), marker);
        assertThat(output).contains(marker, "Preparing:", "Parameters:");
    }

    @SuppressWarnings("unchecked")
    private void query(String logger, String marker) throws Exception {
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE file_metadata(file_id VARCHAR(100), object_key VARCHAR(200))");
            try (var insert = connection.prepareStatement("INSERT INTO file_metadata VALUES (?, ?)")) {
                insert.setString(1, "synthetic-id");
                insert.setString(2, marker);
                insert.executeUpdate();
            }
        }
        Configuration configuration = new Configuration(new Environment(
                "logging-probe", new JdbcTransactionFactory(), dataSource));
        configuration.setLogImpl((Class<? extends org.apache.ibatis.logging.Log>) Class.forName(logger));
        configuration.addMapper(MetadataProbe.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            Map<String, Object> row = session.getMapper(MetadataProbe.class).select("synthetic-id");
            assertThat(row.get("OBJECT_KEY")).isEqualTo(marker);
        }
    }

    interface MetadataProbe {
        @Select("SELECT file_id, object_key FROM file_metadata WHERE file_id = #{fileId}")
        Map<String, Object> select(String fileId);
    }
}
