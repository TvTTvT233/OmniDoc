package com.javaee.aiservice.taskrun;
import com.javaee.aiservice.taskrun.recovery.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class AgentBudgetTest {
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

    @Test void absentNewLimitsPreserveSerializedLegacyRequest() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var request=new com.javaee.aiservice.agent.execution.model.AgentExecutionRequest();
        Map<?,?> payload=mapper.convertValue(request,Map.class);
        assertThat(payload.containsKey("maxRetries")).isFalse(); assertThat(payload.containsKey("maxDurationSeconds")).isFalse();
        assertThatThrownBy(()->store.submit("u","c","invalid",null,Map.of("maxRetries",-1))).hasMessageContaining("INVALID_RUN_BUDGET");
        var r=run(); assertThat(run().id()).isEqualTo(r.id());
        assertThat(store.budget("u",r.id()).get("maxRetries")).isEqualTo(8);
    }
    @Test void aggregateCallsPersistAndReuseDoesNotCharge() {
        var r=store.submit("u","c","budget",null,Map.of("maxToolCalls",1));
        var l=store.claim("u",r.id(),"a").lease();
        store.beginOperation(l,"one","read",Map.of(),true);
        store.completeOperation(l,"one",Map.of(),true,true);
        expire(r.id()); var next=store.claim("u",r.id(),"b").lease();
        assertThat(store.beginOperation(next,"one","read",Map.of(),true).decision()).isEqualTo("REUSE");
        assertThatThrownBy(()->store.beginOperation(next,"two","read",Map.of(),true)).hasMessage("BUDGET_TOOL_CALLS_EXCEEDED");
        assertThat(store.budget("u",r.id()).get("toolCallsUsed")).isEqualTo(1);
    }
    @Test void retryBudgetAppliesAcrossOperations() {
        var r=store.submit("u","c","budget",null,Map.of("maxRetries",0)); var l=store.claim("u",r.id(),"a").lease();
        store.beginOperation(l,"one","read",Map.of(),true); store.completeOperation(l,"one",Map.of(),false,true);
        assertThatThrownBy(()->store.beginOperation(l,"one","read",Map.of(),true)).hasMessage("BUDGET_RETRIES_EXCEEDED");
        assertThat(store.budget("u",r.id()).get("retriesUsed")).isEqualTo(0);
    }
    @Test void approvalWaitingExpiresWithoutResettingDeadline() {
        var l=claim(); var token=store.challenge(l,"delete","file-delete",Map.of()).token();
        store.finish(l,"WAITING_INPUT","APPROVAL_REQUIRED",Map.of());
        jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",l.id());
        assertThat(store.decide("u",token,true).reason()).isEqualTo("BUDGET_DEADLINE_EXCEEDED");
        assertThat(store.claim("u",l.id(),"b").lease()).isNull();
    }
    @Test void pollingExpiresQueuedButPreservesUnknownWrite() {
        var l=claim(); store.beginOperation(l,"write","file-delete",Map.of(),false);
        jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",l.id()); expire(l.id());
        assertThat(store.claim("u",l.id(),"b").run().reason()).isEqualTo("OUTCOME_UNKNOWN");
        store.recoverable(20);
        assertThat(store.find(l.id()).orElseThrow().reason()).isEqualTo("OUTCOME_UNKNOWN");
        var r=store.submit("u","c","second",null,Map.of());
        jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",r.id());
        store.recoverable(20);
        assertThat(store.find(r.id()).orElseThrow().reason()).isEqualTo("BUDGET_DEADLINE_EXCEEDED");
    }
    @Test void concurrentDuplicateIntentChargesOnce() throws Exception {
        var l=claim(); var pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(()->store.beginOperation(l,"one","read",Map.of(),true).decision());
            var results=pool.invokeAll(calls); int executed=0;
            for(var result:results) if("EXECUTE".equals(result.get())) executed++;
            assertThat(executed).isEqualTo(1); assertThat(store.budget("u",l.id()).get("toolCallsUsed")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void deadlineCheckAndFinalBudgetFailureKeepUnknownWrite() {
        var l=claim(); store.beginOperation(l,"write","file-delete",Map.of(),false);
        jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",l.id());
        assertThatThrownBy(()->store.checkDeadline(l)).hasMessage("BUDGET_DEADLINE_EXCEEDED");
        store.finishBudget(l,"BUDGET_DEADLINE_EXCEEDED");
        assertThat(store.find(l.id()).orElseThrow().reason()).isEqualTo("OUTCOME_UNKNOWN");
    }
    @Test void expiredApprovalAfterPollReturnsSameTerminalState() {
        var l=claim(); var token=store.challenge(l,"delete","file-delete",Map.of()).token();
        store.finish(l,"WAITING_INPUT","APPROVAL_REQUIRED",Map.of());
        jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",l.id());
        store.recoverable(20);
        assertThat(store.decide("u",token,true).reason()).isEqualTo("BUDGET_DEADLINE_EXCEEDED");
        assertThat(store.budget("u",l.id()).get("toolCallsUsed")).isEqualTo(0);
    }
    @Test void completedRunRemainsCompletedAfterDeadline() {
        var l=claim(); store.finish(l,"SUCCEEDED",null,Map.of("status","success"));
        jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",l.id());
        store.recoverable(20);
        assertThat(store.claim("u",l.id(),"b").run().status()).isEqualTo("SUCCEEDED");
    }
    @Test void executorStopsBeforeFinalModelAfterDeadlineAndDoesNotReplay() {
        var f=new AgentDurableExecutionTest(); f.setup();
        org.mockito.Mockito.when(f.ai.analyze(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            f.fixture.jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",f.trace());
            return new com.javaee.aiservice.vo.TextAnalyzeVO(4,0,4,0,0,0,1,1,1);
        });
        var request=f.request("统计文本"); var result=f.service().execute(request);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("recoveryReason")).isEqualTo("BUDGET_DEADLINE_EXCEEDED");
        f.service().execute(request);
        org.mockito.Mockito.verify(f.ai,org.mockito.Mockito.times(1)).analyze(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(f.chat,org.mockito.Mockito.times(1)).callChatApiWithModelCode(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void lateFinalModelResultCannotBecomeSuccessOrFallbackAnswer() {
        var f=new AgentDurableExecutionTest(); f.setup();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if(calls.incrementAndGet()==1) throw new IllegalStateException("deterministic planner fallback");
            f.fixture.jdbc.update("UPDATE ai_agent_run SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?",f.trace());
            return "late synthetic answer";
        }).when(f.chat).callChatApiWithModelCode(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var result=f.service().execute(f.request("统计文本"));
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("recoveryReason")).isEqualTo("BUDGET_DEADLINE_EXCEEDED");
        assertThat(f.messages.values()).doesNotContain("late synthetic answer");
        assertThat(calls.get()).isEqualTo(2);
    }
}
