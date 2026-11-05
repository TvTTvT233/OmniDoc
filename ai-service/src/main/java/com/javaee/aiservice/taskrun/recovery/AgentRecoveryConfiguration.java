package com.javaee.aiservice.taskrun.recovery;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="ai.agent.recovery.enabled",havingValue="true")
public class AgentRecoveryConfiguration {
    @Bean
    public AgentRunStore agentRunStore(DataSource source, JdbcTemplate jdbc, PlatformTransactionManager tx,
            @Value("${ai.agent.recovery.schema-auto-migration:false}") boolean migrate,
            @Value("${ai.agent.recovery.lease-millis:120000}") long leaseMillis) {
        if(migrate) new ResourceDatabasePopulator(new ClassPathResource("db/task-runtime/V001__agent_run_operation.sql")).execute(source);
        Integer version=jdbc.queryForObject("SELECT MAX(version) FROM ai_task_runtime_schema_version",Integer.class);
        if(version==null || version!=1) throw new IllegalStateException("TASK_RUNTIME_SCHEMA_VERSION_MISMATCH");
        jdbc.queryForList("SELECT run_id,epoch,lease_until,request_json,checkpoint_json FROM ai_agent_run WHERE 1=0");
        jdbc.queryForList("SELECT run_id,operation_id,params_digest,replay_safe,approval_hash FROM ai_agent_operation WHERE 1=0");
        return new AgentRunStore(jdbc,tx,leaseMillis);
    }
}
