package com.javaee.aiservice.taskrun.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Durable execution facts. Redis is only a compatibility projection when this store is enabled. */
public class AgentRunStore {
    public record Run(String id, String owner, String conversationId, String clientRequestId,
                      String asyncJobId, String status, String reason, long epoch, int attempt,
                      Map<String, Object> request, Map<String, Object> checkpoint, Map<String, Object> result,
                      Instant createdAt, Instant updatedAt) {}
    public record Lease(String id, String owner, String worker, long epoch) {}
    public record Claim(Run run, Lease lease) {}
    public record Operation(String decision, Map<String, Object> result) {}
    public record Challenge(String token, long expiresAt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final long leaseMillis;
    private final boolean mysql;
    public AgentRunStore(JdbcTemplate jdbc, PlatformTransactionManager manager, long leaseMillis) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.leaseMillis = Math.max(1000, leaseMillis);
        this.mysql=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")));
    }
    public Optional<Run> find(String id) {
        return jdbc.query("SELECT "+columns("")+" FROM ai_agent_run WHERE run_id=? AND deleted=FALSE", (r, n) -> read(r), id).stream().findFirst();
    }
    public Optional<Run> findOwned(String owner, String id) { return find(id).filter(r -> r.owner().equals(owner)); }
    public boolean known(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_run WHERE run_id=?",Integer.class,id)>0; }
    public void deleteOwned(String owner,String id) {
        locked(owner,id,row -> {
            jdbc.update("DELETE FROM ai_agent_operation WHERE run_id=?",id);
            return jdbc.update("UPDATE ai_agent_run SET deleted=TRUE,status='CANCELLED',epoch=epoch+1,request_json='{}',checkpoint_json='{}',result_json='{}',worker_id=NULL,lease_until=NULL WHERE run_id=?",id);
        });
    }
    public Optional<Run> findRequest(String owner, String key) {
        return jdbc.query("SELECT "+columns("")+" FROM ai_agent_run WHERE user_id=? AND client_request_id=? AND deleted=FALSE",
                (r,n) -> read(r), owner, key).stream().findFirst();
    }
    public Optional<Run> findAsync(String jobId) {
        return jdbc.query("SELECT "+columns("")+" FROM ai_agent_run WHERE async_job_id=? AND deleted=FALSE", (r,n) -> read(r), jobId).stream().findFirst();
    }
    public List<Run> listOwned(String owner) {
        return jdbc.query("SELECT "+columns("")+" FROM ai_agent_run WHERE user_id=? AND deleted=FALSE ORDER BY created_at DESC LIMIT 200",(r,n)->read(r),owner);
    }
    public Optional<Run> findApproval(String owner,String token) {
        if(token==null) return Optional.empty();
        return jdbc.query("SELECT "+columns("r.")+" FROM ai_agent_run r JOIN ai_agent_operation o ON r.run_id=o.run_id WHERE r.user_id=? AND o.approval_hash=? AND r.deleted=FALSE",
                (r,n)->read(r),owner,RecoveryJson.hash(token)).stream().findFirst();
    }
    public Run submit(String owner, String conversation, String key, String asyncId, Map<String,Object> request) {
        if (key == null || key.isBlank() || key.length() > 128) throw conflict("INVALID_CLIENT_REQUEST_ID");
        String digest = RecoveryJson.digest(request);
        try { return tx.execute(s -> {
            lockConversation(owner, conversation);
            List<Run> matches = jdbc.query("SELECT "+columns("")+" FROM ai_agent_run WHERE user_id=? AND client_request_id=? FOR UPDATE",
                    (r,n)->read(r), owner,key);
            if (!matches.isEmpty()) {
                Run old = matches.get(0);
                if (!RecoveryJson.digest(old.request()).equals(digest)) throw conflict("CLIENT_REQUEST_CONFLICT");
                return findOwned(owner, old.id()).orElseThrow(() -> conflict("TASK_DELETED"));
            }
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO ai_agent_run(run_id,user_id,conversation_id,client_request_id,request_digest,async_job_id,
                    request_json,checkpoint_json,result_json,status,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,'{}','{}','QUEUED',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """, id,owner,conversation,key,digest,asyncId,RecoveryJson.json(request));
            return find(id).orElseThrow();
        }); } catch(org.springframework.dao.DuplicateKeyException collision) {
            Run old=findRequest(owner,key).orElseThrow(()->conflict("CLIENT_REQUEST_CONFLICT"));
            if(!RecoveryJson.digest(old.request()).equals(digest)) throw conflict("CLIENT_REQUEST_CONFLICT");
            return old;
        }
    }
    public Claim claim(String owner, String id, String worker) {
        return locked(owner,id,row -> {
            Run run = find(id).orElseThrow();
            if (!Set.of("QUEUED","RUNNING").contains(run.status())
                    || "RUNNING".equals(run.status()) && leaseValid(id)) return new Claim(run,null);
            if (run.attempt() >= 5) {
                jdbc.update("UPDATE ai_agent_run SET status='WAITING_INPUT',waiting_reason='RECOVERY_LIMIT' WHERE run_id=?", id);
                return new Claim(find(id).orElseThrow(),null);
            }
            // A remote mutation may have committed even when its response never reached this process.
            int unknown = jdbc.update("UPDATE ai_agent_operation SET status='OUTCOME_UNKNOWN' WHERE run_id=? AND replay_safe=FALSE AND status='SEND_INTENT'", id);
            if (unknown > 0 || countUnknown(id) > 0) {
                jdbc.update("UPDATE ai_agent_run SET status='WAITING_INPUT',waiting_reason='OUTCOME_UNKNOWN',epoch=epoch+1,worker_id=NULL,lease_until=NULL WHERE run_id=?", id);
                return new Claim(find(id).orElseThrow(),null);
            }
            long epoch = run.epoch()+1;
            jdbc.update("UPDATE ai_agent_run SET status='RUNNING',waiting_reason=NULL,epoch=?,worker_id=?,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),attempt=attempt+1,updated_at=CURRENT_TIMESTAMP(3) WHERE run_id=?",
                    epoch,worker,Math.max(1,leaseMillis/1000),id);
            return new Claim(find(id).orElseThrow(),new Lease(id,owner,worker,epoch));
        });
    }
    public List<Run> recoverable(int limit) {
        return jdbc.query("SELECT "+columns("")+" FROM ai_agent_run WHERE deleted=FALSE AND (status='QUEUED' OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3))) ORDER BY created_at LIMIT ?",
                (r,n)->read(r), Math.max(1,Math.min(limit,20)));
    }
    public void heartbeat(Lease lease) {
        mutate(lease,row -> jdbc.update("UPDATE ai_agent_run SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)) WHERE run_id=?",
                Math.max(1,leaseMillis/1000),lease.id()));
    }
    public long heartbeatMillis() { return Math.max(250,leaseMillis/3); }
    public void checkpoint(Lease lease, Map<String,Object> checkpoint) {
        mutate(lease,row -> jdbc.update("UPDATE ai_agent_run SET checkpoint_json=?,updated_at=CURRENT_TIMESTAMP(3) WHERE run_id=?",
                RecoveryJson.json(checkpoint),lease.id()));
    }
    public Operation beginOperation(Lease lease, String opId, String tool, Map<String,Object> params, boolean replaySafe) {
        return mutate(lease,row -> {
            String digest = RecoveryJson.digest(params);
            List<Map<String,Object>> found = jdbc.queryForList("SELECT * FROM ai_agent_operation WHERE run_id=? AND operation_id=? FOR UPDATE",lease.id(),opId);
            if (!found.isEmpty()) {
                Map<String,Object> op=found.get(0);
                if (!digest.equals(op.get("params_digest")) || !tool.equals(op.get("tool_name"))) throw conflict("OPERATION_PARAMS_CHANGED");
                String state=String.valueOf(op.get("status"));
                if ("SUCCEEDED".equals(state)) return new Operation("REUSE",RecoveryJson.map(String.valueOf(op.get("result_json"))));
                if ("OUTCOME_UNKNOWN".equals(state) || "SEND_INTENT".equals(state) && !replaySafe) return new Operation("OUTCOME_UNKNOWN",Map.of());
                if (Set.of("WAIT_APPROVAL","REJECTED").contains(state)) return new Operation("WAIT_APPROVAL",Map.of());
                if (((Number)op.get("attempt")).intValue() >= 3) return new Operation("RETRY_LIMIT",Map.of());
                jdbc.update("UPDATE ai_agent_operation SET status='SEND_INTENT',epoch=?,attempt=attempt+1 WHERE run_id=? AND operation_id=?",lease.epoch(),lease.id(),opId);
            } else {
                jdbc.update("INSERT INTO ai_agent_operation(run_id,operation_id,tool_name,params_digest,replay_safe,status,epoch,attempt,result_json) VALUES(?,?,?,?,?,'SEND_INTENT',?,1,'{}')",
                        lease.id(),opId,tool,digest,replaySafe,lease.epoch());
            }
            return new Operation("EXECUTE",Map.of());
        });
    }
    public void completeOperation(Lease lease, String opId, Map<String,Object> result, boolean success, boolean replaySafe) {
        mutate(lease,row -> {
            String status=success?"SUCCEEDED":replaySafe?"FAILED":"OUTCOME_UNKNOWN";
            int changed=jdbc.update("UPDATE ai_agent_operation SET status=?,result_json=? WHERE run_id=? AND operation_id=? AND epoch=? AND status='SEND_INTENT'",
                    status,RecoveryJson.json(result),lease.id(),opId,lease.epoch());
            if(changed!=1) throw conflict("OPERATION_FENCED");
            return changed;
        });
    }
    public Challenge challenge(Lease lease,String opId,String tool,Map<String,Object> params) {
        return mutate(lease,row -> {
            String digest=RecoveryJson.digest(params);
            List<Map<String,Object>> ops=jdbc.queryForList("SELECT * FROM ai_agent_operation WHERE run_id=? AND operation_id=? FOR UPDATE",lease.id(),opId);
            String token=UUID.randomUUID().toString(); Instant expires=now().plusSeconds(86400);
            if(!ops.isEmpty()) {
                String state=String.valueOf(ops.get(0).get("status"));
                if(Set.of("SEND_INTENT","SUCCEEDED","OUTCOME_UNKNOWN").contains(state)) throw conflict("OPERATION_ALREADY_DISPATCHED");
                jdbc.update("UPDATE ai_agent_operation SET status='WAIT_APPROVAL',params_digest=?,approval_hash=?,approval_until=TIMESTAMPADD(SECOND,86400,CURRENT_TIMESTAMP(3)) WHERE run_id=? AND operation_id=?",
                        digest,RecoveryJson.hash(token),lease.id(),opId);
            } else jdbc.update("INSERT INTO ai_agent_operation(run_id,operation_id,tool_name,params_digest,replay_safe,status,epoch,result_json,approval_hash,approval_until) VALUES(?,?,?,?,FALSE,'WAIT_APPROVAL',?,'{}',?,TIMESTAMPADD(SECOND,86400,CURRENT_TIMESTAMP(3)))",
                    lease.id(),opId,tool,digest,lease.epoch(),RecoveryJson.hash(token));
            return new Challenge(token,expires.toEpochMilli());
        });
    }
    public boolean approved(Lease lease,String opId,String tool,Map<String,Object> params) {
        return mutate(lease,row -> !jdbc.queryForList("SELECT operation_id FROM ai_agent_operation WHERE run_id=? AND operation_id=? AND tool_name=? AND params_digest=? AND status IN ('APPROVED','SUCCEEDED')",
                lease.id(),opId,tool,RecoveryJson.digest(params)).isEmpty());
    }
    public Run decide(String owner,String token,boolean approve) {
        if(token==null || token.isBlank()) throw conflict("APPROVAL_REQUIRED");
        List<String> ids=jdbc.query("SELECT run_id FROM ai_agent_operation WHERE approval_hash=?",(r,n)->r.getString(1),RecoveryJson.hash(token));
        if(ids.size()!=1) throw conflict("APPROVAL_NOT_FOUND");
        return locked(owner,ids.get(0), row -> {
            List<Map<String,Object>> ops=jdbc.queryForList("SELECT * FROM ai_agent_operation WHERE run_id=? AND approval_hash=? FOR UPDATE",ids.get(0),RecoveryJson.hash(token));
            if(ops.size()!=1) throw conflict("APPROVAL_NOT_FOUND");
            Map<String,Object> op=ops.get(0); String state=String.valueOf(op.get("status"));
            if(!"WAIT_APPROVAL".equals(state)) return find(ids.get(0)).orElseThrow();
            if(!Set.of("WAITING_INPUT","QUEUED").contains(String.valueOf(row.get("status")))) throw conflict("TASK_NOT_WAITING");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_operation WHERE run_id=? AND operation_id=? AND approval_until>CURRENT_TIMESTAMP(3)",Integer.class,ids.get(0),op.get("operation_id"))!=1) throw conflict("APPROVAL_EXPIRED");
            jdbc.update("UPDATE ai_agent_operation SET status=? WHERE run_id=? AND operation_id=?",approve?"APPROVED":"REJECTED",ids.get(0),op.get("operation_id"));
            Map<String,Object> checkpoint=new LinkedHashMap<>(find(ids.get(0)).orElseThrow().checkpoint());
            checkpoint.put("approvalClientRequestId","approval-"+RecoveryJson.hash(token).substring(0,40));
            checkpoint.remove("finalAnswer"); checkpoint.put("phase","TOOLS");
            jdbc.update("UPDATE ai_agent_run SET checkpoint_json=? WHERE run_id=?",RecoveryJson.json(checkpoint),ids.get(0));
            jdbc.update("UPDATE ai_agent_run SET status=?,waiting_reason=NULL,epoch=epoch+1,attempt=0,worker_id=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE run_id=?",approve?"QUEUED":"CANCELLED",ids.get(0));
            return find(ids.get(0)).orElseThrow();
        });
    }
    public void finish(Lease lease,String status,String reason,Map<String,Object> result) {
        mutate(lease,row -> jdbc.update("UPDATE ai_agent_run SET status=?,waiting_reason=?,result_json=?,worker_id=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE run_id=?",
                status,reason,RecoveryJson.json(result),lease.id()));
    }
    public Run resume(String owner,String id,Map<String,Object> input) {
        return locked(owner,id,row -> {
            if(countUnknown(id)>0 || "OUTCOME_UNKNOWN".equals(row.get("waiting_reason"))) throw conflict("OUTCOME_UNKNOWN_REQUIRES_RECONCILIATION");
            Run run=find(id).orElseThrow();
            if(run.checkpoint().get("userContinuation") instanceof Map<?,?> prior && Objects.equals(prior.get("clientRequestId"),input.get("clientRequestId"))) {
                if(!RecoveryJson.digest(prior).equals(RecoveryJson.digest(input))) throw conflict("CONTINUATION_REQUEST_CONFLICT");
                return run;
            }
            if(!"WAITING_INPUT".equals(row.get("status"))) return run;
            Map<String,Object> cp=new LinkedHashMap<>(run.checkpoint()); cp.put("userContinuation",input);
            cp.put("phase","TOOLS"); cp.remove("finalAnswer");
            cp.remove("approvalClientRequestId");
            jdbc.update("UPDATE ai_agent_run SET status='QUEUED',waiting_reason=NULL,checkpoint_json=?,epoch=epoch+1,attempt=0 WHERE run_id=?",RecoveryJson.json(cp),id);
            return find(id).orElseThrow();
        });
    }
    public boolean cancel(String owner,String id) {
        return locked(owner,id,row -> {
            if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(String.valueOf(row.get("status")))) return false;
            jdbc.update("UPDATE ai_agent_run SET status='CANCELLED',epoch=epoch+1,worker_id=NULL,lease_until=NULL WHERE run_id=?",id); return true;
        });
    }
    /** Called inside the conversation deletion transaction after its row lock. */
    public void tombstoneConversation(String owner,String conversation) {
        tx.executeWithoutResult(s -> {
            if(jdbc.queryForList("SELECT conversation_id FROM ai_conversation WHERE conversation_id=? AND user_id=? FOR UPDATE",conversation,owner).isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND");
            jdbc.update("DELETE FROM ai_agent_operation WHERE run_id IN (SELECT run_id FROM ai_agent_run WHERE user_id=? AND conversation_id=?)",owner,conversation);
            jdbc.update("UPDATE ai_agent_run SET deleted=TRUE,status='CANCELLED',epoch=epoch+1,request_json='{}',checkpoint_json='{}',result_json='{}',worker_id=NULL,lease_until=NULL WHERE user_id=? AND conversation_id=?",owner,conversation);
        });
    }
    private int countUnknown(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_operation WHERE run_id=? AND status='OUTCOME_UNKNOWN'",Integer.class,id); }
    private <T> T mutate(Lease lease,Function<Map<String,Object>,T> operation) {
        return locked(lease.owner(),lease.id(),row -> {
            if(!"RUNNING".equals(row.get("status")) || ((Number)row.get("epoch")).longValue()!=lease.epoch()
                    || !lease.worker().equals(row.get("worker_id")) || !leaseValid(lease.id())) throw conflict("LEASE_LOST");
            return operation.apply(row);
        });
    }
    private <T> T locked(String owner,String id,Function<Map<String,Object>,T> operation) {
        Run before=findOwned(owner,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND"));
        return tx.execute(s -> {
            lockConversation(owner,before.conversationId());
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ai_agent_run WHERE run_id=? AND user_id=? AND deleted=FALSE FOR UPDATE",id,owner);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND");
            return operation.apply(rows.get(0));
        });
    }
    private void lockConversation(String owner,String conversation) {
        if(jdbc.queryForList("SELECT conversation_id FROM ai_conversation WHERE conversation_id=? AND user_id=? AND status='ACTIVE' FOR UPDATE",conversation,owner).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ACTIVE_CONVERSATION_NOT_FOUND");
    }
    private boolean leaseValid(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_run WHERE run_id=? AND lease_until>CURRENT_TIMESTAMP(3)",Integer.class,id)==1; }
    private Instant now() {
        return mysql?Instant.ofEpochMilli(jdbc.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)",Long.class)):
                jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant();
    }
    private String columns(String prefix) {
        return prefix+"*"+(mysql?", CAST(UNIX_TIMESTAMP("+prefix+"created_at)*1000 AS SIGNED) AS created_epoch, CAST(UNIX_TIMESTAMP("+prefix+"updated_at)*1000 AS SIGNED) AS updated_epoch":"");
    }
    private Run read(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Run(r.getString("run_id"),r.getString("user_id"),r.getString("conversation_id"),r.getString("client_request_id"),r.getString("async_job_id"),r.getString("status"),r.getString("waiting_reason"),r.getLong("epoch"),r.getInt("attempt"),RecoveryJson.map(r.getString("request_json")),RecoveryJson.map(r.getString("checkpoint_json")),RecoveryJson.map(r.getString("result_json")),mysql?Instant.ofEpochMilli(r.getLong("created_epoch")):r.getTimestamp("created_at").toInstant(),mysql?Instant.ofEpochMilli(r.getLong("updated_epoch")):r.getTimestamp("updated_at").toInstant());
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
