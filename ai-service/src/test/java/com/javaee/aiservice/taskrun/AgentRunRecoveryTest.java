package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.taskrun.recovery.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AgentRunRecoveryTest {
    JdbcTemplate jdbc; AgentRunStore store;
    @BeforeEach void setup() {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:recovery_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ai_conversation(conversation_id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(128),status VARCHAR(16))");
        jdbc.update("INSERT INTO ai_conversation VALUES('c','u','ACTIVE')");
        store=new AgentRecoveryConfiguration().agentRunStore(ds,jdbc,new DataSourceTransactionManager(ds),true,120000);
    }
    AgentRunStore.Run run() { return store.submit("u","c","key",null,Map.of("task","test")); }
    AgentRunStore.Lease claim() { return store.claim("u",run().id(),"worker").lease(); }
    void expire(String id) { jdbc.update("UPDATE ai_agent_run SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",id); }
    @Test void sameSubmissionReturnsSameRunAndDifferentPayloadConflicts() {
        var first=run(); assertThat(run().id()).isEqualTo(first.id());
        assertThatThrownBy(()->store.submit("u","c","key",null,Map.of("task","changed"))).hasMessageContaining("CLIENT_REQUEST_CONFLICT");
        assertThat(store.findOwned("other",first.id())).isEmpty();
    }
    @Test void concurrentClaimHasOneWinner() throws Exception {
        var run=run(); var pool=Executors.newFixedThreadPool(8);
        try { var start=new CountDownLatch(1); List<Future<AgentRunStore.Claim>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) { String worker="w"+i; futures.add(pool.submit(()->{start.await();return store.claim("u",run.id(),worker);})); }
            start.countDown(); int winners=0;
            for(var future:futures) if(future.get(10,TimeUnit.SECONDS).lease()!=null) winners++;
            assertThat(winners).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void oldEpochCannotCheckpointOrFinish() {
        var old=claim(); expire(old.id()); var next=store.claim("u",old.id(),"new");
        assertThat(next.lease().epoch()).isGreaterThan(old.epoch());
        assertThatThrownBy(()->store.checkpoint(old,Map.of())).hasMessageContaining("LEASE_LOST");
        assertThatThrownBy(()->store.finish(old,"SUCCEEDED",null,Map.of())).hasMessageContaining("LEASE_LOST");
    }
    @Test void completedOperationReplaysAcrossStoreInstances() {
        var lease=claim(); assertThat(store.beginOperation(lease,"step","file-delete",Map.of("objectName","x"),false).decision()).isEqualTo("EXECUTE");
        store.completeOperation(lease,"step",Map.of("status","success"),true,false);
        expire(lease.id()); var next=store.claim("u",lease.id(),"new").lease();
        assertThat(store.beginOperation(next,"step","file-delete",Map.of("objectName","x"),false).decision()).isEqualTo("REUSE");
    }
    @Test void crashAfterWriteIntentNeverResends() {
        var lease=claim(); store.beginOperation(lease,"step","file-delete",Map.of(),false); expire(lease.id());
        var next=store.claim("u",lease.id(),"new");
        assertThat(next.lease()).isNull(); assertThat(next.run().reason()).isEqualTo("OUTCOME_UNKNOWN");
        assertThatThrownBy(()->store.resume("u",lease.id(),Map.of())).hasMessageContaining("RECONCILIATION");
        assertThatThrownBy(()->store.completeOperation(lease,"step",Map.of(),true,false)).hasMessageContaining("LEASE_LOST");
    }
    @Test void readIntentCanRetryWithBoundedAttempts() {
        var lease=claim(); store.beginOperation(lease,"step","text-analyze",Map.of(),true); expire(lease.id());
        var next=store.claim("u",lease.id(),"new").lease();
        assertThat(store.beginOperation(next,"step","text-analyze",Map.of(),true).decision()).isEqualTo("EXECUTE");
        store.completeOperation(next,"step",Map.of(),false,true);
        assertThat(store.beginOperation(next,"step","text-analyze",Map.of(),true).decision()).isEqualTo("EXECUTE");
        store.completeOperation(next,"step",Map.of(),false,true);
        assertThat(store.beginOperation(next,"step","text-analyze",Map.of(),true).decision()).isEqualTo("RETRY_LIMIT");
    }
    @Test void approvalBindsOwnerRunStepAndAllParamsAndRepeatedDecisionIsHarmless() {
        var lease=claim(); Map<String,Object> params=Map.of("bucketName","b","documentId",4,"newObjectName","x");
        var token=store.challenge(lease,"step","file-delete",params);
        store.finish(lease,"WAITING_INPUT","APPROVAL_REQUIRED",Map.of());
        assertThatThrownBy(()->store.decide("other",token.token(),true)).hasMessageContaining("TASK_NOT_FOUND");
        store.decide("u",token.token(),true); store.decide("u",token.token(),true);
        var next=store.claim("u",lease.id(),"new").lease();
        assertThat(store.approved(next,"step","file-delete",params)).isTrue();
        assertThat(store.approved(next,"step","file-delete",Map.of("bucketName","changed","documentId",4,"newObjectName","x"))).isFalse();
        assertThat(store.approved(next,"other-step","file-delete",params)).isFalse();
        assertThat(store.beginOperation(next,"step","file-delete",params,false).decision()).isEqualTo("EXECUTE");
        assertThat(store.beginOperation(next,"step","file-delete",params,false).decision()).isEqualTo("OUTCOME_UNKNOWN");
    }
    @Test void cancelFencesActiveWorkerAndDeletionErasesPayloads() {
        var lease=claim(); store.checkpoint(lease,Map.of("body","private"));
        store.cancel("u",lease.id()); assertThatThrownBy(()->store.checkpoint(lease,Map.of())).hasMessageContaining("LEASE_LOST");
        store.tombstoneConversation("u","c"); assertThat(store.find(lease.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT checkpoint_json FROM ai_agent_run",String.class)).isEqualTo("{}");
        assertThat(store.recoverable(10)).isEmpty();
    }
    @Test void credentialsAreStrippedAndCanonicalDigestIgnoresMapOrder() {
        assertThat(RecoveryJson.json(Map.of("context",Map.of("Authorization","secret","agentApprovalToken","secret","content","text"))))
                .doesNotContain("secret").contains("text");
        assertThat(RecoveryJson.digest(Map.of("a",1,"b",2))).isEqualTo(RecoveryJson.digest(new LinkedHashMap<>(Map.of("b",2,"a",1))));
        assertThat(ToolReplayPolicy.replaySafe("file-delete")).isFalse();
        assertThat(ToolReplayPolicy.replaySafe("unknown-mutator")).isFalse();
    }
}
