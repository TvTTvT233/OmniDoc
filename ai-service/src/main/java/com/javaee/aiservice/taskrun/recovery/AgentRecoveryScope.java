package com.javaee.aiservice.taskrun.recovery;

import java.util.*;
import java.util.concurrent.*;

/** Internal execution state; never accepted from an HTTP request. */
public final class AgentRecoveryScope implements AutoCloseable {
    private static final ScheduledExecutorService HEARTBEATS = Executors.newScheduledThreadPool(1,r -> {
        Thread thread=new Thread(r,"agent-lease-heartbeat"); thread.setDaemon(true); return thread;
    });
    public final AgentRunStore.Run run;
    public final AgentRunStore.Lease lease;
    public Map<String,Object> checkpoint;
    private final ScheduledFuture<?> heartbeat;
    private volatile RuntimeException lost;
    public AgentRecoveryScope(AgentRunStore store, AgentRunStore.Claim claim) {
        run=claim.run(); lease=claim.lease(); checkpoint=new LinkedHashMap<>(run.checkpoint());
        heartbeat=HEARTBEATS.scheduleWithFixedDelay(()->{
            try { store.heartbeat(lease); } catch(RuntimeException e) { lost=e; }
        },store.heartbeatMillis(),store.heartbeatMillis(),TimeUnit.MILLISECONDS);
    }
    public void assertLive() { if(lost!=null) throw new IllegalStateException("LEASE_LOST",lost); }
    @Override public void close() { heartbeat.cancel(false); }
}
