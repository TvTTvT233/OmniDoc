package com.javaee.documentservice.consistency;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.documentservice.client.FileServiceClient;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.*;
import com.javaee.documentservice.service.*;
import com.javaee.documentservice.service.impl.DocumentServiceImpl;
import com.javaee.documentservice.vo.DocumentVO;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real relational transactions + mapper SQL. No network, models, Redis or MinIO. */
@SpringJUnitConfig(DocumentConsistencyTransactionTest.Config.class)
class DocumentConsistencyTransactionTest {
    @Configuration @EnableTransactionManagement @MapperScan("com.javaee.documentservice.mapper")
    static class Config {
        @Bean DataSource dataSource() throws Exception {
            JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:docconsistency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            JdbcTemplate db=new JdbcTemplate(ds);
            db.execute("CREATE TABLE IF NOT EXISTS document(id VARCHAR(64) PRIMARY KEY,title VARCHAR(255),content TEXT,summary TEXT,keywords TEXT,file_id VARCHAR(64),user_id BIGINT,bucket_name VARCHAR(128),object_name VARCHAR(512),status VARCHAR(20),version INT,category VARCHAR(50),tags TEXT,created_by VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP)");
            db.execute("CREATE TABLE IF NOT EXISTS document_version(id VARCHAR(64) PRIMARY KEY,document_id VARCHAR(64),version_number INT,title VARCHAR(255),content TEXT,summary TEXT,keywords TEXT,change_log VARCHAR(500),created_by VARCHAR(64),create_time TIMESTAMP)");
            db.execute("CREATE TABLE IF NOT EXISTS document_access(id VARCHAR(64) PRIMARY KEY,document_id VARCHAR(64),bucket_name VARCHAR(128),user_id BIGINT,role VARCHAR(20),create_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(document_id,user_id))");
            try(var c=ds.getConnection()){DocumentConsistencySchema.migrate(c);}
            return ds;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);
            f.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));return f.getObject();
        }
        @Bean ObjectMapper objectMapper(){return new ObjectMapper();}
        @Bean CanonicalDocumentText canonical(){return new CanonicalDocumentText(2097152);}
        @Bean DocumentAccessService access(){return new DocumentAccessService();}
        @Bean DocumentContentService content(){return mock(DocumentContentService.class);}
        @Bean FileServiceClient files(){return mock(FileServiceClient.class);}
        @Bean DocumentService documents(){return new DocumentServiceImpl();}
        @Bean DocumentIndexService indexes(DocumentMapper d,DocumentVersionMapper v,DocumentAccessService a,JdbcTemplate jdbc,ObjectMapper json,CanonicalDocumentText t){return new DocumentIndexService(d,v,a,jdbc,json,t,"text-embedding-v4",1024,120);}
    }
    @Autowired DocumentService documents;
    @Autowired DocumentIndexService index;
    @Autowired DocumentMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DocumentContentService content;
    @Autowired FileServiceClient files;
    @BeforeEach void resetState() {
        for(String table:List.of("document_index_reference","document_index_generation","document_index_task","document_version","document_access","document")) jdbc.update("DELETE FROM "+table);
        reset(content,files);when(content.getBucketName(anyLong())).thenAnswer(i->"user-"+i.getArgument(0));when(content.getObjectName(anyString())).thenAnswer(i->"document-content/"+i.getArgument(0)+".txt");
    }
    DocumentVO create() {DocumentCreateDTO dto=new DocumentCreateDTO();dto.setTitle("Synthetic");return documents.create(dto,7L);}
    DocumentVO save(String id,int expected,String raw) {DocumentUpdateDTO dto=new DocumentUpdateDTO();dto.setExpectedVersion(expected);dto.setContentFormat("PLAIN_TEXT");dto.setContent(raw);return documents.update(id,dto,7L);}
    Map<String,Object> claim() {return index.claim(new DocumentIndexService.ClaimRequest("test-worker"));}
    DocumentIndexService.CompleteRequest completion(Map<String,Object> job) {
        String text=(String)job.get("canonicalText"),gen=(String)job.get("generationId");
        return new DocumentIndexService.CompleteRequest((String)job.get("leaseToken"),gen,(String)job.get("contentHash"),(String)job.get("configHash"),List.of(new DocumentIndexService.Segment(UUID.randomUUID().toString(),gen+"_seg_0",0,text.length(),CanonicalDocumentText.hash(text))));
    }
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void createSaveStoresOneImmutableRevisionAndTaskPerVersion() {
        DocumentVO d=create();save(d.getId(),1,"first\n第二段😀");
        assertThat(count("document_version")).isEqualTo(2);assertThat(count("document_index_task")).isEqualTo(1);
        assertThat(index.snapshot(d.getId(),1).getContent()).isEmpty();assertThat(index.snapshot(d.getId(),2).getContent()).isEqualTo("first\n第二段😀");
        assertThat(index.snapshot(d.getId(),2).getParserVersion()).isEqualTo("explicit-content-v1");
        assertThat(documents.getById(d.getId(),7L).getContent()).isEqualTo("first\n第二段😀");
        verify(content,never()).getContent(anyString(),anyString());
    }
    @Test void transactionRollbackLeavesNoRevisionOrOutboxHalfWrite() {
        DocumentVO d=create();
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{save(d.getId(),1,"not committed");throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.selectById(d.getId()).getVersion()).isEqualTo(1);assertThat(count("document_version")).isEqualTo(1);assertThat(count("document_index_task")).isZero();
    }
    @Test void concurrentExpectedVersionHasOneCommitAndOneConflict() throws Exception {
        DocumentVO d=create();CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> work=()->{start.await();try{save(d.getId(),1,UUID.randomUUID().toString());return 200;}catch(DocumentStateException e){return e.getStatus();}};
            Future<Integer> a=pool.submit(work),b=pool.submit(work);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(count("document_version")).isEqualTo(2);
        } finally {pool.shutdownNow();}
    }
    @Test void savingSameContentStillAdvancesRevisionAndRestoreCreatesNewRevision() {
        DocumentVO d=create();save(d.getId(),1,"one");save(d.getId(),2,"one");
        DocumentVO restored=documents.restoreVersion(d.getId(),1,3,7L);
        assertThat(restored.getVersion()).isEqualTo(4);assertThat(restored.getContent()).isEmpty();assertThat(index.snapshot(d.getId(),2).getContent()).isEqualTo("one");
    }
    @Test void missingExpectedVersionAndOversizeLeaveDatabaseUnchanged() {
        DocumentVO d=create();DocumentUpdateDTO dto=new DocumentUpdateDTO();dto.setContent("x");dto.setContentFormat("HTML");
        assertThatThrownBy(()->documents.update(d.getId(),dto,7L)).isInstanceOfSatisfying(DocumentStateException.class,e->assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(()->save(d.getId(),1,"中".repeat(700000))).isInstanceOfSatisfying(DocumentStateException.class,e->assertThat(e.getStatus()).isEqualTo(413));
        assertThat(count("document_version")).isEqualTo(1);
    }
    @Test void publishThenSaveImmediatelyHidesOldGenerationButHistoricalReferenceSurvives() {
        DocumentVO d=create();save(d.getId(),1,"中文😀 evidence");Map<String,Object> job=claim();var complete=completion(job);
        assertThat(index.complete((String)job.get("taskId"),complete).get("published")).isEqualTo(true);
        assertThat(index.state(d.getId(),7L).get("queryable")).isEqualTo(true);
        save(d.getId(),2,"new text");assertThat(index.state(d.getId(),7L).get("queryable")).isEqualTo(false);
        var candidate=new DocumentIndexService.Candidate(d.getId(),2,(String)job.get("generationId"));
        assertThat((List<?>)index.visibility(new DocumentIndexService.VisibilityRequest(7L,"default",List.of(candidate))).get("documents")).isEmpty();
        Map<String,Object> ref=index.resolve(d.getId(),complete.segments().get(0).referenceId(),7L);
        assertThat(ref).containsEntry("status","HISTORICAL").containsEntry("content","中文😀 evidence");assertThat(ref).doesNotContainKey("canonicalText");
    }
    @Test void supersededWorkerCannotPublishAndItsAttemptHasDurableCleanup() {
        DocumentVO d=create();save(d.getId(),1,"v2");Map<String,Object> old=claim();save(d.getId(),2,"v3");
        assertThat(index.complete((String)old.get("taskId"),completion(old)).get("published")).isEqualTo(false);
        assertThat(count("document_index_reference")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_index_task WHERE operation='CLEANUP'",Integer.class)).isEqualTo(1);
    }
    @Test void expiredLeaseReclaimsDifferentGenerationAndRejectsOldToken() {
        DocumentVO d=create();save(d.getId(),1,"lease");Map<String,Object> old=claim();
        jdbc.update("UPDATE document_index_task SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE task_id=?",old.get("taskId"));
        Map<String,Object> next=claim();assertThat(next.get("generationId")).isNotEqualTo(old.get("generationId"));
        assertThat(index.complete((String)old.get("taskId"),completion(old)).get("published")).isEqualTo(false);
        assertThat(index.complete((String)next.get("taskId"),completion(next)).get("published")).isEqualTo(true);
    }
    @Test void repeatedTimeoutIsBoundedAndExplicitRetryResetsAttemptsWithoutChangingConfig() {
        DocumentVO d=create();save(d.getId(),1,"lease");index.request(d.getId(),7L,new DocumentIndexService.IndexRequest(2,"CHAPTER"),false);
        Map<String,Object> job=claim();jdbc.update("UPDATE document_index_task SET attempt=3,lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE task_id=?",job.get("taskId"));
        claim();assertThat(index.state(d.getId(),7L)).containsEntry("indexState","FAILED").containsEntry("errorCode","LEASE_EXHAUSTED");
        String hash=(String)index.state(d.getId(),7L).get("configHash");
        index.request(d.getId(),7L,new DocumentIndexService.IndexRequest(2,null),true);
        assertThat(index.state(d.getId(),7L)).containsEntry("configHash",hash).containsEntry("indexState","QUEUED");
    }
    @Test void indexRequestIsIdempotentAndDifferentStrategySupersedesOldTask() {
        DocumentVO d=create();save(d.getId(),1,"test");index.request(d.getId(),7L,new DocumentIndexService.IndexRequest(2,"FIXED_LENGTH"),false);
        assertThat(count("document_index_task")).isEqualTo(1);
        index.request(d.getId(),7L,new DocumentIndexService.IndexRequest(2,"HYBRID"),false);
        assertThat(count("document_index_task")).isEqualTo(2);
    }
    @Test void invalidHashOrSurrogateSplitCannotRegisterReferenceOrPublish() {
        DocumentVO d=create();save(d.getId(),1,"中😀文");Map<String,Object> job=claim();var good=completion(job);
        var bad=new DocumentIndexService.CompleteRequest(good.leaseToken(),good.generationId(),good.contentHash(),good.configHash(),List.of(new DocumentIndexService.Segment("ref","seg",1,2,CanonicalDocumentText.hash("\ud83d"))));
        assertThatThrownBy(()->index.complete((String)job.get("taskId"),bad)).isInstanceOf(DocumentStateException.class);
        assertThat(count("document_index_reference")).isZero();assertThat(index.state(d.getId(),7L).get("queryable")).isEqualTo(false);
    }
    @Test void deletingImmediatelyBlocksReadsReferencesAndLateCompletionWithoutDeletingSource() {
        DocumentVO d=create();save(d.getId(),1,"kept snapshot");Map<String,Object> job=claim();var complete=completion(job);index.complete((String)job.get("taskId"),complete);
        documents.delete(d.getId(),7L);
        assertThatThrownBy(()->documents.getById(d.getId(),7L)).isInstanceOfSatisfying(DocumentStateException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->index.resolve(d.getId(),complete.segments().get(0).referenceId(),7L)).isInstanceOf(DocumentStateException.class);
        assertThat(index.complete((String)job.get("taskId"),complete).get("published")).isEqualTo(false);
        verify(content,never()).deleteContent(anyString(),anyString());assertThat(index.snapshot(d.getId(),2).getContent()).isEqualTo("kept snapshot");
    }
    @Test void anotherUserCannotReadSnapshotReferenceOrQueueBuild() {
        DocumentVO d=create();save(d.getId(),1,"private");
        assertThatThrownBy(()->index.request(d.getId(),8L,new DocumentIndexService.IndexRequest(2,null),false)).isInstanceOf(DocumentStateException.class);
        assertThatThrownBy(()->documents.getVersions(d.getId(),8L)).isInstanceOf(DocumentStateException.class);
    }
    @Test void parserFailureIsExplicitAndRetryKeepsOriginalFile() {
        when(files.getFileName("file")).thenReturn(ResponseEntity.ok("broken.pdf"));when(files.downloadFile("file")).thenReturn(ResponseEntity.ok("bad".getBytes()));
        DocumentCreateDTO dto=new DocumentCreateDTO();dto.setTitle("Broken");dto.setFileId("file");DocumentVO d=documents.create(dto,7L);
        assertThat(d.getParseState()).isEqualTo("FAILED");assertThat(count("document_version")).isZero();assertThat(count("document_index_task")).isZero();
        assertThat(index.state(d.getId(),7L)).containsEntry("indexState","FAILED").containsEntry("errorCode","PARSE_FAILED");
        when(files.getFileName("file")).thenReturn(ResponseEntity.ok("valid.txt"));when(files.downloadFile("file")).thenReturn(ResponseEntity.ok("parsed".getBytes()));
        assertThat(documents.retryParse(d.getId(),1,7L).getParseState()).isEqualTo("READY");assertThat(count("document_index_task")).isEqualTo(1);
        assertThat(index.snapshot(d.getId(),2).getParserVersion()).isEqualTo("strict-parser-v1");
        save(d.getId(),2,"edited");
        assertThat(index.snapshot(d.getId(),3).getParserVersion()).isEqualTo("explicit-content-v1");
        documents.restoreVersion(d.getId(),2,3,7L);
        assertThat(index.snapshot(d.getId(),4).getParserVersion()).isEqualTo("strict-parser-v1");
    }
    @Test void migrationPreservesDuplicateLegacyRowsAndCannotGuessHistoricalContent() throws Exception {
        DocumentVO d=create();jdbc.update("UPDATE document SET snapshot_state=NULL,content=NULL WHERE id=?",d.getId());jdbc.update("UPDATE document_version SET verified_version=NULL WHERE document_id=?",d.getId());
        jdbc.update("INSERT INTO document_version(id,document_id,version_number,title,content) VALUES('legacy-duplicate',?,1,'old','different')",d.getId());
        try(var c=ds.getConnection()){DocumentConsistencySchema.migrate(c);}
        assertThat(count("document_version")).isEqualTo(2);assertThat(index.state(d.getId(),7L).get("indexState")).isEqualTo("MIGRATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT parser_version FROM document_version WHERE id='legacy-duplicate'",String.class)).isNull();
        assertThatThrownBy(()->documents.restoreVersion(d.getId(),1,1,7L)).isInstanceOf(DocumentStateException.class);
        assertThat(save(d.getId(),1,"explicit checkpoint").getVersion()).isEqualTo(2);assertThat(count("document_version")).isEqualTo(3);
    }
    @Test void failedDerivedMinioWriteDoesNotUndoCommittedDatabaseVersion() {
        DocumentVO d=create();when(content.saveContent(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatCode(()->save(d.getId(),1,"authoritative")).doesNotThrowAnyException();assertThat(index.snapshot(d.getId(),2).getContent()).isEqualTo("authoritative");
    }
}
