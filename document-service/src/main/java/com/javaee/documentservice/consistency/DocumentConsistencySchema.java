package com.javaee.documentservice.consistency;

import java.sql.*;

/** Additive migration: legacy version rows remain untouched and are never assigned verified_version. */
public final class DocumentConsistencySchema {
    private DocumentConsistencySchema() {}
    public static void migrate(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            column(c,s,"document","content_format","VARCHAR(24) NULL");
            column(c,s,"document","content_hash","VARCHAR(64) NULL");
            column(c,s,"document","snapshot_state","VARCHAR(32) NULL");
            column(c,s,"document","parse_state","VARCHAR(24) NULL");
            column(c,s,"document","active_generation_id","VARCHAR(64) NULL");
            column(c,s,"document","index_config_hash","VARCHAR(64) NULL");
            column(c,s,"document_version","verified_version","INT NULL");
            column(c,s,"document_version","content_format","VARCHAR(24) NULL");
            column(c,s,"document_version","canonical_text","LONGTEXT NULL");
            column(c,s,"document_version","content_hash","VARCHAR(64) NULL");
            column(c,s,"document_version","normalization_version","VARCHAR(32) NULL");
            column(c,s,"document_version","parser_version","VARCHAR(32) NULL");
            longText(c,s,"document","content");
            longText(c,s,"document_version","content");
            java.util.Map<String,java.util.Set<String>> uniqueIndexes=new java.util.HashMap<>();
            try(ResultSet rs=c.getMetaData().getIndexInfo(c.getCatalog(),null,"document_version",true,false)) {
                while(rs.next()) {
                    String name=rs.getString("INDEX_NAME"),column=rs.getString("COLUMN_NAME");
                    if(name!=null&&column!=null) uniqueIndexes.computeIfAbsent(name,key->new java.util.HashSet<>()).add(column.toLowerCase(java.util.Locale.ROOT));
                }
            }
            boolean unique=uniqueIndexes.values().stream().anyMatch(columns->columns.equals(java.util.Set.of("document_id","verified_version")));
            if(!unique) s.execute("ALTER TABLE document_version ADD UNIQUE KEY uk_verified_document_version(document_id,verified_version)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS document_index_task (
                  task_id VARCHAR(64) PRIMARY KEY, idempotency_key VARCHAR(255) NOT NULL,
                  document_id VARCHAR(64) NOT NULL, document_version INT NOT NULL,
                  operation VARCHAR(16) NOT NULL, content_hash VARCHAR(64), config_hash VARCHAR(64), config_json TEXT,
                  state VARCHAR(24) NOT NULL, attempt INT NOT NULL DEFAULT 0,
                  generation_id VARCHAR(64), cleanup_generation_ids TEXT,
                  lease_token VARCHAR(64), lease_until DATETIME(6), worker_id VARCHAR(128),
                  error_code VARCHAR(80), next_attempt_at DATETIME(6) NOT NULL,
                  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
                  UNIQUE KEY uk_index_task_idempotency(idempotency_key),
                  KEY idx_index_task_due(state,next_attempt_at), KEY idx_index_task_doc(document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS document_index_generation (
                  generation_id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL,
                  document_id VARCHAR(64) NOT NULL, document_version INT NOT NULL,
                  content_hash VARCHAR(64) NOT NULL, config_hash VARCHAR(64) NOT NULL,
                  state VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL,
                  KEY idx_generation_doc(document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS document_index_reference (
                  reference_id VARCHAR(64) PRIMARY KEY, document_id VARCHAR(64) NOT NULL,
                  document_version INT NOT NULL, generation_id VARCHAR(64) NOT NULL,
                  segment_id VARCHAR(255) NOT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL,
                  segment_hash VARCHAR(64) NOT NULL, content_hash VARCHAR(64) NOT NULL,
                  created_at DATETIME(6) NOT NULL,
                  UNIQUE KEY uk_generation_segment(generation_id,segment_id), KEY idx_reference_generation(generation_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
    }
    private static void column(Connection c,Statement s,String table,String column,String type) throws SQLException {
        try(ResultSet rs=c.getMetaData().getColumns(c.getCatalog(),null,table,column)) {
            if(rs.next()) return;
        }
        s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+type);
    }
    private static void longText(Connection c,Statement s,String table,String column) throws SQLException {
        try(ResultSet rs=c.getMetaData().getColumns(c.getCatalog(),null,table,column)) {
            if(rs.next() && "LONGTEXT".equalsIgnoreCase(rs.getString("TYPE_NAME"))) return;
        }
        s.execute("ALTER TABLE "+table+" MODIFY "+column+" LONGTEXT NULL");
    }
}
