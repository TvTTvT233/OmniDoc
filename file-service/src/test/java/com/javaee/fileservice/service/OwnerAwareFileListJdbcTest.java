package com.javaee.fileservice.service;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.service.impl.OwnerAwareFileServiceImpl;
import com.javaee.fileservice.service.impl.OwnedFileDownloadStaging;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OwnerAwareFileListJdbcTest {
    @Test
    void actualMapperPaginatesOnlyOwnedActiveRowsAndNeverReturnsLegacyOrOtherOwner() throws Exception {
        var source = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE file_metadata(file_id VARCHAR(64), owner_id VARCHAR(64), status VARCHAR(20),
                        original_file_name VARCHAR(255), file_type VARCHAR(128), file_size BIGINT,
                        sha256 VARCHAR(64), storage_version VARCHAR(255), content_signature VARCHAR(64),
                        create_time TIMESTAMP, object_key VARCHAR(255), bucket_name VARCHAR(255))
                    """);
            for (String[] row : new String[][]{{"a", "42", "ACTIVE"}, {"b", "42", "ACTIVE"},
                    {"c", "42", "ACTIVE"}, {"z-other", "99", "ACTIVE"},
                    {"z-deleted", "42", "DELETED"}, {"z-legacy", null, "ACTIVE"}}) {
                try (var insert = connection.prepareStatement("INSERT INTO file_metadata VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, row[0]); insert.setString(2, row[1]); insert.setString(3, row[2]);
                    insert.setString(4, row[0] + ".pdf"); insert.setString(5, "application/pdf");
                    insert.setLong(6, 4); insert.setString(7, "0".repeat(64)); insert.setString(8, "version-1");
                    insert.setString(9, "PDF"); insert.setTimestamp(10, java.sql.Timestamp.valueOf("2026-10-29 12:00:00"));
                    insert.setString(11, "private-storage-location"); insert.setString(12, "private-bucket");
                    insert.executeUpdate();
                }
            }
        }
        var configuration = new Configuration(new Environment("owned-page-test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        try (var xml = new ClassPathResource("mapper/FileMetadataMapper.xml").getInputStream()) {
            new XMLMapperBuilder(xml, configuration, "mapper/FileMetadataMapper.xml", configuration.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var service = new OwnerAwareFileServiceImpl(session.getMapper(FileMetadataMapper.class),
                    mock(OwnedFileObjectStore.class), mock(FileStorageConfig.class), mock(OwnedFileDownloadStaging.class));
            var first = service.list("42", 1, 2);
            assertThat(first.items()).extracting(item -> item.fileId()).containsExactly("c", "b");
            assertThat(first.hasMore()).isTrue();
            var second = service.list("42", 2, 2);
            assertThat(second.items()).extracting(item -> item.fileId()).containsExactly("a");
            assertThat(second.hasMore()).isFalse();
            assertThat(service.list("42", 3, 2).items()).isEmpty();
            assertThat(service.list("99", 1, 20).items()).extracting(item -> item.fileId()).containsExactly("z-other");
            assertThat(service.list("unknown", 1, 20).items()).isEmpty();
            var serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(first);
            assertThat(serialized).doesNotContain("objectKey", "bucketName", "ownerId", "private-storage", "private-bucket");
            assertThatThrownBy(() -> service.list("42", 0, 20)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.list("42", 10001, 20)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.list("42", 1, 101)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.list(null, 1, 20)).isInstanceOf(SecurityException.class);
        }
    }
}
