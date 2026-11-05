package com.javaee.aiservice.taskrun.recovery;

import com.javaee.aiservice.agent.execution.AgentExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;

@Component
@ConditionalOnProperty(name="ai.agent.recovery.enabled",havingValue="true")
public class AgentRecoveryWorker {
    private final AgentRunStore store;
    private final AgentExecutionService execution;
    public AgentRecoveryWorker(AgentRunStore store,AgentExecutionService execution) { this.store=store;this.execution=execution; }
    public boolean handleAsync(String jobId) {
        var run=store.findAsync(jobId); if(run.isEmpty()) return false;
        execute(run.get()); return true;
    }
    @Scheduled(fixedDelayString="${ai.agent.recovery.poll-millis:5000}",initialDelayString="${ai.agent.recovery.poll-millis:5000}")
    public void poll() {
        try { for(var run:store.recoverable(5)) execute(run); }
        catch(RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("AGENT_RECOVERY_POLL_UNAVAILABLE type={}",error.getClass().getSimpleName());
        }
    }
    private void execute(AgentRunStore.Run run) {
        var previous=SecurityContextHolder.getContext(); var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(run.owner(),null,List.of(new SimpleGrantedAuthority("ROLE_user"))));
        SecurityContextHolder.setContext(context);
        try { execution.executeDurable(run.owner(),run.id()); }
        catch(RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("AGENT_RECOVERY_EXECUTION_INTERRUPTED type={}",error.getClass().getSimpleName());
        } finally { SecurityContextHolder.setContext(previous); }
    }
}
