package com.javaee.documentservice.consistency;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.mapper.DocumentVersionMapper;
import com.javaee.documentservice.service.DocumentAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Owns the durable outbox, leases and the only authoritative publication pointer. */
@Service
public class DocumentIndexService {
    public record IndexRequest(Integer expectedVersion, String strategy) {}
    public record ClaimRequest(String workerId) {}
    public record LeaseRequest(String leaseToken) {}
    public record FailureRequest(String leaseToken, String errorCode, boolean retryable) {}
    public record Segment(String referenceId,String segmentId,int startOffset,int endOffset,String segmentHash) {}
    public record CompleteRequest(String leaseToken,String generationId,String contentHash,String configHash,List<Segment> segments) {}
    public record Candidate(String documentId,Integer documentVersion,String generationId) {}
    public record VisibilityRequest(Long userId,String knowledgeBaseId,List<Candidate> candidates) {}
    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final DocumentAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CanonicalDocumentText text;
    private final String embeddingModel;
    private final int embeddingDimension;
    private final int leaseSeconds;
    public DocumentIndexService(DocumentMapper documents,DocumentVersionMapper versions,DocumentAccessService access,
                                JdbcTemplate jdbc,ObjectMapper json,CanonicalDocumentText text,
                                @Value("${omnidoc.index.embedding-model:text-embedding-v4}") String model,
                                @Value("${omnidoc.index.embedding-dimension:1024}") int dimension,
                                @Value("${omnidoc.index.lease-seconds:120}") int leaseSeconds) {
        this.documents=documents; this.versions=versions; this.access=access; this.jdbc=jdbc; this.json=json; this.text=text;
        this.embeddingModel=model; this.embeddingDimension=dimension;
        if(leaseSeconds<10 || leaseSeconds>3600) throw new IllegalArgumentException("Invalid index lease duration");
        this.leaseSeconds=leaseSeconds;
    }
    public static DocumentStateException problem(int status,String code,String message) { return new DocumentStateException(status,code,message); }
    public static void expected(Document d,Integer version) {
        if(version==null) throw problem(400,"EXPECTED_VERSION_REQUIRED","请提供当前文档版本");
        if(!version.equals(d.getVersion())) throw problem(409,"DOCUMENT_VERSION_CONFLICT","文档已被修改，请刷新后再保存草稿");
    }
    public Document requireDocument(String id,Long user,boolean write,boolean lock) {
        Document d=lock?documents.selectForUpdate(id):documents.selectById(id);
        if(d==null || !"active".equals(d.getStatus()) || !access.canRead(d,user))
            throw problem(404,"DOCUMENT_NOT_FOUND","文档不存在或无权访问");
        if(write) access.assertCanWrite(d,user);
        return d;
    }
    /** Called inside the same document save transaction. A new row is a full committed revision. */
    @Transactional
    public void snapshotAndSchedule(Document d,String format,String raw,String changeLog,Long editor,String parserVersion) {
        String canonical=text.normalize(raw,format);
        d.setContent(raw); d.setContentFormat(format); d.setContentHash(CanonicalDocumentText.hash(canonical));
        d.setSnapshotState("VERIFIED"); d.setParseState("READY");
        DocumentVersion v=new DocumentVersion();
        v.setDocumentId(d.getId()); v.setVersionNumber(d.getVersion()); v.setVerifiedVersion(d.getVersion());
        v.setTitle(d.getTitle()); v.setContent(raw); v.setContentFormat(format); v.setCanonicalText(canonical);
        v.setContentHash(d.getContentHash()); v.setNormalizationVersion(CanonicalDocumentText.VERSION);
        v.setParserVersion(parserVersion);
        v.setSummary(d.getSummary()); v.setKeywords(d.getKeywords()); v.setChangeLog(changeLog);
        v.setCreatedBy(String.valueOf(editor)); v.setCreateTime(now()); versions.insert(v);
        invalidate(d);
        Map<String,Object> config=config("FIXED_LENGTH");
        d.setIndexConfigHash(CanonicalDocumentText.hash(encode(config)));
        // Explicit SQL clears nullable columns; MyBatis' default NOT_NULL update policy cannot clear a pointer.
        jdbc.update("UPDATE document SET active_generation_id=NULL,index_config_hash=? WHERE id=?",d.getIndexConfigHash(),d.getId());
        if(!canonical.isBlank()) enqueueBuild(d,config);
    }
    @Transactional
    public void invalidate(Document d) {
        d.setActiveGenerationId(null);
        jdbc.update("UPDATE document SET active_generation_id=NULL WHERE id=?",d.getId());
        jdbc.update("UPDATE document_index_task SET state='SUPERSEDED',updated_at=? WHERE document_id=? AND operation='BUILD' AND state IN ('PENDING','RUNNING','FAILED')",now(),d.getId());
        for(Map<String,Object> g:jdbc.queryForList("SELECT generation_id FROM document_index_generation WHERE document_id=? AND state<>'CLEANED'",d.getId()))
            queueCleanup(d,str(g,"generation_id"),false);
    }
    public int nextVersion(Document d) {
        Integer max=versions.selectMaxVersionNumber(d.getId());
        int current=Math.max(d.getVersion()==null?0:d.getVersion(),max==null?0:max);
        if(current==Integer.MAX_VALUE) throw problem(409,"VERSION_LIMIT","文档版本号已达到上限");
        return current+1;
    }
    public DocumentVersion snapshot(String documentId,int version) {
        List<DocumentVersion> rows=jdbc.query("SELECT * FROM document_version WHERE document_id=? AND verified_version=?",(rs,n)->{
            DocumentVersion v=new DocumentVersion();v.setId(rs.getString("id"));v.setDocumentId(documentId);
            v.setVersionNumber(rs.getInt("version_number"));v.setVerifiedVersion(rs.getInt("verified_version"));
            v.setTitle(rs.getString("title"));v.setContent(rs.getString("content"));v.setContentFormat(rs.getString("content_format"));
            v.setCanonicalText(rs.getString("canonical_text"));v.setContentHash(rs.getString("content_hash"));
            v.setNormalizationVersion(rs.getString("normalization_version"));v.setParserVersion(rs.getString("parser_version"));v.setSummary(rs.getString("summary"));v.setKeywords(rs.getString("keywords"));return v;
        },documentId,version);
        if(rows.size()!=1) throw problem(409,"MIGRATION_REQUIRED","历史版本尚未建立可验证快照，请明确保存正文格式");
        DocumentVersion v=rows.get(0);
        if(v.getCanonicalText()==null || !CanonicalDocumentText.hash(v.getCanonicalText()).equals(v.getContentHash()))
            throw problem(503,"SNAPSHOT_INTEGRITY_ERROR","文档快照校验失败");
        return v;
    }
    public Map<String,Object> state(String id,Long user) {
        Document d=requireDocument(id,user,false,false);
        return state(d);
    }
    private Map<String,Object> state(Document d) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("documentId",d.getId());out.put("currentVersion",d.getVersion());
        out.put("contentHash",d.getContentHash());out.put("activeGenerationId",d.getActiveGenerationId());
        out.put("configHash",d.getIndexConfigHash());out.put("queryable",false);
        out.put("indexedVersion",null);out.put("errorCode",null);out.put("taskId",null);out.put("retryable",false);
        String status;
        if("FAILED".equals(d.getParseState())) {status="FAILED";out.put("errorCode","PARSE_FAILED");}
        else if(!"VERIFIED".equals(d.getSnapshotState())) status="MIGRATION_REQUIRED";
        else if(snapshot(d.getId(),d.getVersion()).getCanonicalText().isBlank()) {status="DISABLED";out.put("errorCode","EMPTY_CONTENT");}
        else if(d.getActiveGenerationId()!=null && generationMatches(d,d.getActiveGenerationId(),d.getVersion())) {
            status="READY";out.put("queryable",true);out.put("indexedVersion",d.getVersion());
        } else {
            List<Map<String,Object>> tasks=jdbc.queryForList("SELECT task_id,state,error_code FROM document_index_task WHERE document_id=? AND document_version=? AND config_hash=? AND operation='BUILD' ORDER BY created_at DESC LIMIT 1",d.getId(),d.getVersion(),d.getIndexConfigHash());
            status="NOT_INDEXED";
            if(!tasks.isEmpty()) {
                String taskState=str(tasks.get(0),"state");
                status=switch(taskState){case "PENDING"->"QUEUED";case "RUNNING"->"BUILDING";case "FAILED"->"FAILED";default->"NOT_INDEXED";};
                out.put("errorCode",tasks.get(0).get("error_code"));
                out.put("taskId",tasks.get(0).get("task_id"));out.put("retryable","FAILED".equals(status));
            }
        }
        out.put("indexState",status);return out;
    }
    @Transactional
    public Map<String,Object> request(String id,Long user,IndexRequest request,boolean retry) {
        Document d=requireDocument(id,user,true,true);expected(d,request.expectedVersion());
        if(!"VERIFIED".equals(d.getSnapshotState())) throw problem(409,"MIGRATION_REQUIRED","请先明确格式并保存文档");
        DocumentVersion v=snapshot(id,d.getVersion());
        if(v.getCanonicalText().isBlank()) throw problem(409,"EMPTY_DOCUMENT","空白正文无需构建索引");
        Map<String,Object> config=config(request.strategy());
        if(retry&&request.strategy()==null) {
            List<String> stored=jdbc.query("SELECT config_json FROM document_index_task WHERE document_id=? AND document_version=? AND config_hash=? AND operation='BUILD'",(rs,n)->rs.getString(1),id,d.getVersion(),d.getIndexConfigHash());
            if(!stored.isEmpty()) config=decodeMap(stored.get(0));
        }
        String configHash=CanonicalDocumentText.hash(encode(config));
        boolean configChanged=!configHash.equals(d.getIndexConfigHash());
        if(configChanged) {
            invalidate(d);d.setIndexConfigHash(configHash);
            jdbc.update("UPDATE document SET index_config_hash=? WHERE id=?",configHash,id);
        }
        String key=buildKey(d);
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT task_id,state FROM document_index_task WHERE idempotency_key=?",key);
        if(existing.isEmpty()) enqueueBuild(d,config);
        else if(retry || configChanged || "SUPERSEDED".equals(str(existing.get(0),"state"))) {
            String current=str(existing.get(0),"state");
            if("PENDING".equals(current)||"RUNNING".equals(current)) return state(d);
            // An explicit retry always invalidates a ready generation before it schedules a replacement.
            invalidate(d);
            jdbc.update("UPDATE document_index_task SET state='PENDING',attempt=0,error_code=NULL,lease_token=NULL,lease_until=NULL,next_attempt_at=?,updated_at=? WHERE task_id=?",now(),now(),str(existing.get(0),"task_id"));
        }
        return state(d);
    }
    private Map<String,Object> config(String strategy) {
        String selected=strategy==null?"FIXED_LENGTH":strategy;
        if(!Set.of("FIXED_LENGTH","CHAPTER","AUTO","SEMANTIC","HYBRID").contains(selected)) throw problem(400,"UNSUPPORTED_STRATEGY","不支持的可定位分段策略");
        Map<String,Object> c=new LinkedHashMap<>();c.put("normalizationVersion",CanonicalDocumentText.VERSION);c.put("segmenterVersion","offset-v1");
        c.put("strategy",selected);c.put("chunkSize",800);c.put("overlap",120);c.put("embeddingModel",embeddingModel);
        c.put("embeddingDimension",embeddingDimension);c.put("schemaVersion",1);return c;
    }
    private String buildKey(Document d) { return "BUILD:"+d.getId()+":"+d.getVersion()+":"+d.getIndexConfigHash(); }
    private void enqueueBuild(Document d,Map<String,Object> config) {
        jdbc.update("INSERT INTO document_index_task(task_id,idempotency_key,document_id,document_version,operation,content_hash,config_hash,config_json,state,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?, 'PENDING',?,?,?)",
                uuid(),buildKey(d),d.getId(),d.getVersion(),"BUILD",d.getContentHash(),d.getIndexConfigHash(),encode(config),now(),now(),now());
    }
    private void queueCleanup(Document d,String generation,boolean requeue) {
        String key="CLEANUP:"+generation;
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT task_id,state FROM document_index_task WHERE idempotency_key=?",key);
        if(existing.isEmpty()) jdbc.update("INSERT INTO document_index_task(task_id,idempotency_key,document_id,document_version,operation,state,cleanup_generation_ids,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?, 'CLEANUP','PENDING',?,?,?,?)",uuid(),key,d.getId(),d.getVersion(),encode(List.of(generation)),now(),now(),now());
        else if(requeue && "SUCCEEDED".equals(str(existing.get(0),"state")))
            jdbc.update("UPDATE document_index_task SET state='PENDING',next_attempt_at=?,updated_at=? WHERE task_id=?",now(),now(),str(existing.get(0),"task_id"));
    }
    @Transactional
    public Map<String,Object> claim(ClaimRequest request) {
        if(request.workerId()==null || !request.workerId().matches("[A-Za-z0-9_.-]{1,128}")) throw problem(400,"INVALID_WORKER","工作者标识无效");
        List<Map<String,Object>> candidates=jdbc.queryForList("SELECT task_id,document_id FROM document_index_task WHERE (state='PENDING' AND next_attempt_at<=?) OR (state='RUNNING' AND lease_until<=?) ORDER BY created_at LIMIT 20",now(),now());
        for(Map<String,Object> candidate:candidates) {
            Document d=documents.selectForUpdate(str(candidate,"document_id"));
            if(d==null) continue;
            Map<String,Object> task=task(str(candidate,"task_id"),true);
            String current=str(task,"state");
            if(!"PENDING".equals(current)&&!("RUNNING".equals(current)&&time(task,"lease_until").isBefore(now()))) continue;
            if("PENDING".equals(current)&&time(task,"next_attempt_at").isAfter(now())) continue;
            boolean build="BUILD".equals(str(task,"operation"));
            if(build && "RUNNING".equals(current) && integer(task,"attempt")>=3) {
                jdbc.update("UPDATE document_index_task SET state='FAILED',error_code='LEASE_EXHAUSTED',updated_at=? WHERE task_id=?",now(),str(task,"task_id"));
                if(task.get("generation_id")!=null) queueCleanup(d,str(task,"generation_id"),false);
                continue;
            }
            if(build && (!"active".equals(d.getStatus()) || !Objects.equals(d.getVersion(),integer(task,"document_version")) || !Objects.equals(d.getIndexConfigHash(),str(task,"config_hash")))) {
                jdbc.update("UPDATE document_index_task SET state='SUPERSEDED',updated_at=? WHERE task_id=?",now(),str(task,"task_id"));
                if(task.get("generation_id")!=null) queueCleanup(d,str(task,"generation_id"),false);
                continue;
            }
            if(build && task.get("generation_id")!=null) queueCleanup(d,str(task,"generation_id"),false);
            String lease=uuid(),generation=build?uuid():null;
            LocalDateTime until=now().plusSeconds(leaseSeconds);
            jdbc.update("UPDATE document_index_task SET state='RUNNING',attempt=attempt+1,lease_token=?,lease_until=?,worker_id=?,generation_id=?,updated_at=? WHERE task_id=?",lease,until,request.workerId(),generation,now(),str(task,"task_id"));
            if(build) jdbc.update("INSERT INTO document_index_generation(generation_id,task_id,document_id,document_version,content_hash,config_hash,state,created_at) VALUES(?,?,?,?,?,?,'BUILDING',?)",generation,str(task,"task_id"),d.getId(),d.getVersion(),d.getContentHash(),d.getIndexConfigHash(),now());
            Map<String,Object> out=new LinkedHashMap<>();out.put("taskId",str(task,"task_id"));out.put("leaseToken",lease);out.put("leaseExpiresAt",until.toInstant(ZoneOffset.UTC).toString());out.put("operation",str(task,"operation"));
            out.put("documentId",d.getId());out.put("documentVersion",integer(task,"document_version"));out.put("generationId",generation);
            out.put("ownerUserId",String.valueOf(d.getUserId()));out.put("knowledgeBaseId","default");out.put("fileId",d.getFileId());out.put("documentTitle",d.getTitle());
            out.put("contentHash",task.get("content_hash"));out.put("configHash",task.get("config_hash"));
            out.put("canonicalText",build?snapshot(d.getId(),d.getVersion()).getCanonicalText():null);
            out.put("config",build?decodeMap(str(task,"config_json")):null);
            out.put("cleanupGenerationIds",build?List.of():decodeList(str(task,"cleanup_generation_ids")));
            return out;
        }
        return null;
    }
    @Transactional
    public Map<String,Object> renew(String id,LeaseRequest request) {
        Map<String,Object> preview=task(id,false);Document d=documents.selectForUpdate(str(preview,"document_id"));Map<String,Object> t=task(id,true);
        boolean valid=validLease(t,request.leaseToken())&&targetCurrent(d,t);
        if(valid) jdbc.update("UPDATE document_index_task SET lease_until=?,updated_at=? WHERE task_id=?",now().plusSeconds(leaseSeconds),now(),id);
        return Map.of("renewed",valid);
    }
    @Transactional
    public Map<String,Object> fail(String id,FailureRequest request) {
        Map<String,Object> preview=task(id,false);Document d=documents.selectForUpdate(str(preview,"document_id"));Map<String,Object> t=task(id,true);
        if(!validLease(t,request.leaseToken())) return Map.of("accepted",false);
        String code=request.errorCode();if(code==null||!code.matches("[A-Z0-9_]{1,80}")) code="BUILD_FAILED";
        boolean cleanup="CLEANUP".equals(str(t,"operation"));
        boolean retry=cleanup || (request.retryable()&&integer(t,"attempt")<3&&targetCurrent(d,t));
        jdbc.update("UPDATE document_index_task SET state=?,error_code=?,lease_until=NULL,next_attempt_at=?,updated_at=? WHERE task_id=?",retry?"PENDING":"FAILED",code,now().plusSeconds(Math.min(300,5L*integer(t,"attempt"))),now(),id);
        if(!cleanup && t.get("generation_id")!=null) { jdbc.update("UPDATE document_index_generation SET state='FAILED' WHERE generation_id=?",str(t,"generation_id"));queueCleanup(d,str(t,"generation_id"),false); }
        return Map.of("accepted",true);
    }
    @Transactional
    public Map<String,Object> complete(String id,CompleteRequest request) {
        Map<String,Object> preview=task(id,false);Document d=documents.selectForUpdate(str(preview,"document_id"));Map<String,Object> t=task(id,true);
        boolean cleanup="CLEANUP".equals(str(t,"operation"));
        if("SUCCEEDED".equals(str(t,"state")) && Objects.equals(str(t,"lease_token"),request.leaseToken()))
            return Map.of("published",!cleanup&&Objects.equals(d.getActiveGenerationId(),request.generationId()),"accepted",true);
        if(!validLease(t,request.leaseToken()) || !targetCurrent(d,t)) {
            // Only a generation recorded for this task may ever be scheduled for cleanup.
            if(!cleanup && request.generationId()!=null && !Objects.equals(request.generationId(),d.getActiveGenerationId()) &&
                    jdbc.queryForObject("SELECT COUNT(*) FROM document_index_generation WHERE generation_id=? AND task_id=?",Integer.class,request.generationId(),id)==1)
                queueCleanup(d,request.generationId(),true);
            return Map.of("published",false,"accepted",false);
        }
        if(cleanup) {
            for(String generation:decodeList(str(t,"cleanup_generation_ids"))) {
                if(Objects.equals(generation,d.getActiveGenerationId())) throw problem(409,"GENERATION_ACTIVE","不能清理当前索引");
                jdbc.update("UPDATE document_index_generation SET state='CLEANED' WHERE generation_id=?",generation);
            }
        } else {
            if(!Objects.equals(request.generationId(),str(t,"generation_id")) || !Objects.equals(request.contentHash(),d.getContentHash()) || !Objects.equals(request.configHash(),d.getIndexConfigHash()))
                throw problem(409,"GENERATION_MISMATCH","构建目标不匹配");
            DocumentVersion v=snapshot(d.getId(),d.getVersion());
            List<Segment> segments=request.segments();
            if(segments==null||segments.isEmpty()||segments.size()>10000) throw problem(400,"INVALID_SEGMENTS","分段清单无效");
            Set<String> ids=new HashSet<>(),references=new HashSet<>();
            for(Segment segment:segments) {
                if(segment.referenceId()==null||!segment.referenceId().matches("[A-Za-z0-9_-]{1,64}")||segment.segmentId()==null||segment.segmentId().length()>255||!ids.add(segment.segmentId())||!references.add(segment.referenceId())
                        || segment.startOffset()>=segment.endOffset() || !CanonicalDocumentText.boundary(v.getCanonicalText(),segment.startOffset()) || !CanonicalDocumentText.boundary(v.getCanonicalText(),segment.endOffset())
                        || !CanonicalDocumentText.hash(v.getCanonicalText().substring(segment.startOffset(),segment.endOffset())).equals(segment.segmentHash()))
                    throw problem(400,"INVALID_SEGMENT_REFERENCE","分段引用校验失败");
            }
            for(Segment segment:segments) jdbc.update("INSERT INTO document_index_reference(reference_id,document_id,document_version,generation_id,segment_id,start_offset,end_offset,segment_hash,content_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    segment.referenceId(),d.getId(),d.getVersion(),request.generationId(),segment.segmentId(),segment.startOffset(),segment.endOffset(),segment.segmentHash(),d.getContentHash(),now());
            jdbc.update("UPDATE document_index_generation SET state='PUBLISHED' WHERE generation_id=?",request.generationId());
            jdbc.update("UPDATE document SET active_generation_id=? WHERE id=? AND version=? AND status='active'",request.generationId(),d.getId(),d.getVersion());
        }
        jdbc.update("UPDATE document_index_task SET state='SUCCEEDED',error_code=NULL,updated_at=? WHERE task_id=?",now(),id);
        return Map.of("published",!cleanup,"accepted",true);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> visibility(VisibilityRequest request) {
        if(request.userId()==null||!"default".equals(request.knowledgeBaseId())||request.candidates()==null||request.candidates().size()>100)
            throw problem(400,"INVALID_VISIBILITY_REQUEST","可见性请求无效");
        List<Map<String,Object>> visible=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Candidate candidate:request.candidates()) {
            Document d=documents.selectById(candidate.documentId());
            if(d==null||!"active".equals(d.getStatus())||!Objects.equals(d.getUserId(),request.userId())||!Objects.equals(d.getVersion(),candidate.documentVersion())||!Objects.equals(d.getActiveGenerationId(),candidate.generationId())||candidate.generationId()==null||!generationMatches(d,candidate.generationId(),candidate.documentVersion())||!seen.add(candidate.generationId())) continue;
            Map<String,Object> item=new LinkedHashMap<>();item.put("documentId",d.getId());item.put("documentVersion",d.getVersion());item.put("generationId",d.getActiveGenerationId());item.put("ownerUserId",String.valueOf(d.getUserId()));item.put("knowledgeBaseId","default");
            item.put("contentHash",d.getContentHash());item.put("configHash",d.getIndexConfigHash());item.put("documentTitle",d.getTitle());item.put("fileId",d.getFileId());
            item.put("references",jdbc.queryForList("SELECT reference_id,document_id,document_version,generation_id,segment_id,start_offset,end_offset,segment_hash,content_hash FROM document_index_reference WHERE generation_id=? ORDER BY start_offset",d.getActiveGenerationId()).stream().map(r->referenceMap(r,d.getTitle())).toList());visible.add(item);
        }
        return Map.of("documents",visible);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> resolve(String id,String reference,Long user) {
        Document d=requireDocument(id,user,false,false);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM document_index_reference WHERE reference_id=? AND document_id=?",reference,id);
        if(rows.size()!=1) throw problem(404,"REFERENCE_NOT_FOUND","引用不存在或无权访问");
        Map<String,Object> row=rows.get(0);DocumentVersion v=snapshot(id,integer(row,"document_version"));
        int start=integer(row,"start_offset"),end=integer(row,"end_offset");
        if(!CanonicalDocumentText.boundary(v.getCanonicalText(),start)||!CanonicalDocumentText.boundary(v.getCanonicalText(),end)||start>=end) throw problem(409,"REFERENCE_INTEGRITY_ERROR","引用校验失败");
        String excerpt=v.getCanonicalText().substring(start,end);
        if(!CanonicalDocumentText.hash(excerpt).equals(str(row,"segment_hash")) || !v.getContentHash().equals(str(row,"content_hash"))) throw problem(409,"REFERENCE_INTEGRITY_ERROR","引用校验失败");
        Map<String,Object> out=referenceMap(row,v.getTitle());out.put("content",excerpt);out.put("currentVersion",d.getVersion());out.put("status",Objects.equals(d.getVersion(),v.getVersionNumber())?"CURRENT":"HISTORICAL");out.put("offsetUnit","UTF16");return out;
    }
    private Map<String,Object> referenceMap(Map<String,Object> row,String title) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("referenceId",str(row,"reference_id"));out.put("documentId",str(row,"document_id"));out.put("documentVersion",integer(row,"document_version"));out.put("generationId",str(row,"generation_id"));out.put("segmentId",str(row,"segment_id"));out.put("startOffset",integer(row,"start_offset"));out.put("endOffset",integer(row,"end_offset"));out.put("segmentHash",str(row,"segment_hash"));out.put("contentHash",str(row,"content_hash"));out.put("documentTitle",title);return out;
    }
    private boolean generationMatches(Document d,String generation,Integer version) {
        if(generation==null||version==null) return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM document_index_generation WHERE generation_id=? AND document_id=? AND document_version=? AND content_hash=? AND config_hash=? AND state='PUBLISHED'",Integer.class,generation,d.getId(),version,d.getContentHash(),d.getIndexConfigHash())==1;
    }
    private boolean targetCurrent(Document d,Map<String,Object> task) {
        return d!=null && ("CLEANUP".equals(str(task,"operation")) || ("active".equals(d.getStatus()) && Objects.equals(d.getVersion(),integer(task,"document_version")) && Objects.equals(d.getIndexConfigHash(),str(task,"config_hash"))));
    }
    private boolean validLease(Map<String,Object> task,String token) { return token!=null&&"RUNNING".equals(str(task,"state"))&&token.equals(str(task,"lease_token"))&&time(task,"lease_until").isAfter(now()); }
    private Map<String,Object> task(String id,boolean lock) { List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM document_index_task WHERE task_id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty()) throw problem(404,"TASK_NOT_FOUND","任务不存在");return rows.get(0); }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot serialize index contract",e);} }
    private Map<String,Object> decodeMap(String value) { try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw problem(503,"TASK_INTEGRITY_ERROR","索引任务配置损坏");} }
    private List<String> decodeList(String value) { try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw problem(503,"TASK_INTEGRITY_ERROR","清理任务配置损坏");} }
    private static String str(Map<String,Object> row,String key) {Object value=row.get(key);return value==null?null:value.toString();}
    private static int integer(Map<String,Object> row,String key) {return ((Number)row.get(key)).intValue();}
    private static LocalDateTime time(Map<String,Object> row,String key) {Object v=row.get(key);return v instanceof java.sql.Timestamp ts?ts.toLocalDateTime():(LocalDateTime)v;}
    private static LocalDateTime now() {return LocalDateTime.now(ZoneOffset.UTC);}
    private static String uuid() {return UUID.randomUUID().toString();}
}
