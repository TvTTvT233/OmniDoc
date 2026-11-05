package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.taskrun.recovery.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit isolated MySQL gate; missing configuration is an error, never a passing skip. */
class AgentRecoveryMysqlIT {
    @Test void databaseRestartAndConcurrentClaims() throws Exception {
        String url=Objects.requireNonNull(System.getenv("RECOVERY_MYSQL_URL"),"dedicated MySQL URL required");
        if(!url.contains("/agent_recovery_acceptance?")) throw new IllegalArgumentException("DEDICATED_DATABASE_REQUIRED");
        // Intentionally disagree with the UTC database: leases must use SQL time, public times epoch values.
        var ds=new DriverManagerDataSource(url+"&connectionTimeZone=Asia%2FShanghai","root",Objects.requireNonNull(System.getenv("RECOVERY_MYSQL_PASSWORD")));
        var jdbc=new JdbcTemplate(ds);
        boolean seed="seed".equals(System.getenv("RECOVERY_MYSQL_PHASE"));
        Path fixture=Path.of(Objects.requireNonNull(System.getenv("RECOVERY_MYSQL_FIXTURE")));
        if(seed) {
            jdbc.execute("CREATE TABLE ai_conversation(conversation_id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(128),status VARCHAR(16))");
            jdbc.update("INSERT INTO ai_conversation VALUES('c','u','ACTIVE')");
        }
        var store=new AgentRecoveryConfiguration().agentRunStore(ds,jdbc,new DataSourceTransactionManager(ds),seed,120000);
        if(seed) {
            var completed=store.submit("u","c","completed",null,Map.of("task","fixture"));
            assertThat(Math.abs(completed.createdAt().toEpochMilli()-System.currentTimeMillis())).isLessThan(5000);
            var lease=store.claim("u",completed.id(),"old").lease();
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP(3),lease_until) FROM ai_agent_run WHERE run_id=?",Integer.class,completed.id())).isBetween(118,120);
            store.checkpoint(lease,Map.of("phase","TOOLS","saved","fixture-result"));
            store.beginOperation(lease,"step","file-delete",Map.of("objectName","synthetic"),false);
            store.completeOperation(lease,"step",Map.of("status","success"),true,false);
            var uncertain=store.submit("u","c","uncertain",null,Map.of("task","fixture"));
            store.beginOperation(store.claim("u",uncertain.id(),"old").lease(),"step","file-delete",Map.of(),false);
            var waiting=store.submit("u","c","waiting",null,Map.of("task","fixture"));
            var waitingLease=store.claim("u",waiting.id(),"old").lease();
            var token=store.challenge(waitingLease,"step","file-delete",Map.of("objectName","synthetic"));
            assertThat(Math.abs(token.expiresAt()-System.currentTimeMillis()-86400000L)).isLessThan(5000);
            store.finish(waitingLease,"WAITING_INPUT","APPROVAL_REQUIRED",Map.of());
            Files.writeString(fixture,RecoveryJson.json(Map.of("token",token.token())));
            jdbc.update("UPDATE ai_agent_run SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE status='RUNNING'");
        } else {
            var completed=store.findRequest("u","completed").orElseThrow();
            assertThat(completed.checkpoint()).containsEntry("saved","fixture-result");
            var lease=store.claim("u",completed.id(),"new").lease();
            assertThat(store.beginOperation(lease,"step","file-delete",Map.of("objectName","synthetic"),false).decision()).isEqualTo("REUSE");
            var uncertain=store.findRequest("u","uncertain").orElseThrow();
            assertThat(store.claim("u",uncertain.id(),"new").run().reason()).isEqualTo("OUTCOME_UNKNOWN");
            String token=String.valueOf(RecoveryJson.map(Files.readString(fixture)).get("token"));
            var waiting=store.decide("u",token,true); store.decide("u",token,true);
            var fresh=store.claim("u",waiting.id(),"new").lease();
            assertThat(store.approved(fresh,"step","file-delete",Map.of("objectName","synthetic"))).isTrue();
            var concurrent=store.submit("u","c","concurrent",null,Map.of("task","fixture"));
            var pool=Executors.newFixedThreadPool(8);
            try {
                List<Callable<AgentRunStore.Claim>> calls=new ArrayList<>();
                for(int i=0;i<16;i++) { String worker="worker-"+i;calls.add(()->store.claim("u",concurrent.id(),worker)); }
                int winners=0; for(var result:pool.invokeAll(calls)) if(result.get().lease()!=null) winners++;
                assertThat(winners).isEqualTo(1);
            } finally { pool.shutdownNow(); }
            assertThat(jdbc.queryForObject("SELECT MAX(version) FROM ai_task_runtime_schema_version",Integer.class)).isEqualTo(1);
        }
    }
}
