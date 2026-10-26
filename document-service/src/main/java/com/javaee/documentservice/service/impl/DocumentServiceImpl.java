package com.javaee.documentservice.service.impl;

import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.client.FileServiceClient;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentQueryDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.mapper.DocumentVersionMapper;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.service.DocumentContentService;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.util.DocumentParserUtil;
import com.javaee.documentservice.consistency.DocumentIndexService;
import com.javaee.documentservice.consistency.DocumentStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.javaee.documentservice.vo.DocumentVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档服务实现类
 * 实现文档的CRUD操作和版本控制功能
 * 新保存正文、不可变版本和索引任务以MySQL事务为权威；MinIO仅保存原文件和兼容派生副本。
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentVersionMapper documentVersionMapper;

    @Autowired
    private DocumentContentService documentContentService;

    @Autowired
    private DocumentAccessService documentAccessService;

    @Autowired
    private FileServiceClient fileServiceClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentIndexService indexService;

    /**
     * 创建文档
     * 创建新文档并保存初始版本
     * 通过fileId从file-service获取文件内容，使用Apache POI解析
     * @param dto 创建文档请求参数
     * @param userId 创建用户ID
     * @return 文档VO
     */
    @Override
    @Transactional
    public DocumentVO create(DocumentCreateDTO dto, Long userId) {
        Document document = new Document();
        document.setTitle(dto.getTitle());
        document.setFileId(dto.getFileId());
        document.setCategory(dto.getCategory());
        document.setTags(convertListToJson(dto.getTags()));
        document.setUserId(userId);
        document.setBucketName(documentContentService.getBucketName(userId));
        document.setStatus("active");
        document.setVersion(1);
        document.setCreatedBy(String.valueOf(userId));
        document.setCreateTime(LocalDateTime.now());
        document.setUpdateTime(LocalDateTime.now());
        document.setParseState("READY");

        documentMapper.insert(document);
        document.setObjectName(documentContentService.getObjectName(document.getId()));
        documentMapper.updateById(document);
        documentAccessService.grantOwnerAccess(document.getId(), document.getBucketName(), userId);

        // 通过fileId从file-service获取文件内容并解析
        String content = "";
        String fileName = "";
        if (dto.getFileId() != null && !dto.getFileId().isEmpty()) {
            try {
                // 获取文件名
                ResponseEntity<String> fileNameResponse = fileServiceClient.getFileName(dto.getFileId());
                fileName = fileNameResponse.getBody() != null ? fileNameResponse.getBody() : "unknown";
                
                // 下载文件内容
                ResponseEntity<byte[]> fileResponse = fileServiceClient.downloadFile(dto.getFileId());
                byte[] fileContent = fileResponse.getBody();
                
                content = DocumentParserUtil.parseDocumentStrict(fileContent, fileName);
                log.info("文档解析成功: fileId={}, contentLength={}",dto.getFileId(),content.length());
            } catch (Exception e) {
                // The original uploaded object remains available for an authenticated parse retry.
                log.warn("文档解析失败，保留原始文件和明确失败状态: fileId={}", dto.getFileId());
                document.setParseState("FAILED");
            }
        }

        document.setSummary(DocumentParserUtil.getSummary(content, 200));
        if ("READY".equals(document.getParseState())) {
            indexService.snapshotAndSchedule(document,"PLAIN_TEXT",content,"初始版本",userId,
                    dto.getFileId()!=null&&!dto.getFileId().isEmpty()?DocumentParserUtil.STRICT_PARSER_VERSION:DocumentParserUtil.EXPLICIT_CONTENT_VERSION);
            mirrorAfterCommit(document,content);
        } else {
            document.setContent("");document.setSnapshotState("PARSE_FAILED");
        }
        documentMapper.updateById(document);

        DocumentVO vo = convertToVO(document);
        vo.setContent(content);
        return vo;
    }

    /**
     * 更新文档
     * 以expectedVersion保护更新，并保存完整的新版本与索引任务。
     * @param id 文档ID
     * @param dto 更新文档请求参数
     * @param userId 更新用户ID
     * @return 更新后的文档VO
     */
    @Override
    @Transactional
    public DocumentVO update(String id, DocumentUpdateDTO dto, Long userId) {
        Document document = indexService.requireDocument(id,userId,true,true);
        DocumentIndexService.expected(document,dto.getExpectedVersion());
        boolean migrating=!"VERIFIED".equals(document.getSnapshotState());
        if (migrating && (dto.getContent()==null || dto.getContentFormat()==null))
            throw new DocumentStateException(409,"MIGRATION_REQUIRED","旧文档需明确正文格式并保存后建立快照");
        String content=dto.getContent()==null?document.getContent():dto.getContent();
        String format=dto.getContentFormat()==null?document.getContentFormat():dto.getContentFormat();

        if (dto.getTitle() != null && !dto.getTitle().isBlank()) {
            document.setTitle(dto.getTitle());
        }
        if (dto.getCategory() != null) {
            document.setCategory(dto.getCategory());
        }
        if (dto.getTags() != null) {
            document.setTags(convertListToJson(dto.getTags()));
        }
        document.setVersion(indexService.nextVersion(document));
        document.setUpdateTime(LocalDateTime.now());
        document.setSummary(DocumentParserUtil.getSummary(content,200));
        indexService.snapshotAndSchedule(document,format,content,migrating?"MIGRATION_CHECKPOINT":dto.getChangeLog(),userId,DocumentParserUtil.EXPLICIT_CONTENT_VERSION);
        documentMapper.updateById(document);
        mirrorAfterCommit(document,content);

        DocumentVO vo = convertToVO(document);
        vo.setContent(content);
        return vo;
    }

    /**
     * 删除文档（软删除）
     * 将文档状态标记为已删除，不物理删除
     * @param id 文档ID
     * @param userId 删除用户ID
     */
    @Override
    @Transactional
    public void delete(String id, Long userId) {
        Document document = indexService.requireDocument(id,userId,true,true);

        document.setStatus("deleted");
        document.setUpdateTime(LocalDateTime.now());
        indexService.invalidate(document);
        documentMapper.updateById(document);
        // Keep originals and version snapshots. Vector cleanup is a durable task, not a rollback-prone remote delete.
    }

    @Override
    @Transactional
    public void grantAccess(String id, Long collaboratorUserId, String role, Long operatorUserId) {
        Document document = indexService.requireDocument(id,operatorUserId,true,true);
        if (!documentAccessService.isOwner(document, operatorUserId)) {
            throw new BusinessException("只有文档创建者可以授权协作者");
        }
        documentAccessService.grantAccess(id, storageBucketName(document), collaboratorUserId, role);
    }

    /**
     * 根据ID获取文档详情
     * 可信版本读取数据库正文；尚未迁移的旧文档显式读取MinIO。
     * @param id 文档ID
     * @return 文档VO
     */
    @Override
    public DocumentVO getById(String id, Long userId) {
        Document document = indexService.requireDocument(id,userId,false,false);
        DocumentVO vo = convertToVO(document);
        
        // Only legacy documents may use the original mutable MinIO content path.
        String content = document.getContent();
        if(!"VERIFIED".equals(document.getSnapshotState()) && !"FAILED".equals(document.getParseState())) {
            content=documentContentService.getContent(id,storageBucketName(document));
            if(content==null) throw new DocumentStateException(503,"LEGACY_CONTENT_UNAVAILABLE","旧文档正文暂不可读，请勿覆盖保存");
        }
        vo.setContent(content);
        
        return vo;
    }

    @Override
    public DocumentVO getStorageLocation(String id, Long userId) {
        Document document = indexService.requireDocument(id,userId,true,false);
        return convertToVOWithoutContent(document);
    }

    /**
     * 获取用户的文档列表
     * @param userId 用户ID
     * @return 文档VO列表（不包含内容，如需内容需单独调用getById）
     */
    @Override
    public List<DocumentVO> getByUserId(Long userId) {
        List<Document> documents = documentMapper.selectAccessibleByUserId(userId);
        return documents.stream().map(this::convertToVOWithoutContent).collect(Collectors.toList());
    }

    /**
     * 搜索文档
     * 支持按关键词搜索（标题、分类）或按分类搜索
     * @param dto 查询参数
     * @return 文档VO列表（不包含内容，如需内容需单独调用getById）
     */
    @Override
    public List<DocumentVO> search(DocumentQueryDTO dto, Long userId) {
        List<Document> documents = documentMapper.searchAccessible(userId, dto.getKeyword(), dto.getCategory());
        return documents.stream().map(this::convertToVOWithoutContent).collect(Collectors.toList());
    }

    /**
     * 获取文档的所有版本
     * @param documentId 文档ID
     * @return 文档版本列表（按版本号降序）
     */
    @Override
    public List<DocumentVersion> getVersions(String documentId, Long userId) {
        indexService.requireDocument(documentId,userId,false,false);
        return documentVersionMapper.selectByDocumentId(documentId);
    }

    /**
     * 恢复文档到指定版本
     * 将指定可信快照复制为一个新版本；原快照永不覆盖。
     * @param documentId 文档ID
     * @param versionNumber 版本号
     * @param userId 操作用户ID
     * @return 恢复后的文档VO
     */
    @Override
    @Transactional
    public DocumentVO restoreVersion(String documentId, Integer versionNumber, Integer expectedVersion, Long userId) {
        Document document = indexService.requireDocument(documentId,userId,true,true);
        DocumentIndexService.expected(document,expectedVersion);
        DocumentVersion version = indexService.snapshot(documentId, versionNumber);

        document.setTitle(version.getTitle());
        document.setSummary(version.getSummary());
        document.setKeywords(version.getKeywords());
        document.setVersion(indexService.nextVersion(document));
        document.setUpdateTime(LocalDateTime.now());

        indexService.snapshotAndSchedule(document,version.getContentFormat(),version.getContent(),"恢复到版本"+versionNumber,userId,version.getParserVersion());
        documentMapper.updateById(document);
        mirrorAfterCommit(document,version.getContent());

        DocumentVO vo = convertToVO(document);
        // Return the exact raw content of the selected immutable snapshot.
        vo.setContent(version.getContent());
        return vo;
    }

    /** Best-effort compatibility mirror; never used as the authority for verified documents. */
    private void mirrorAfterCommit(Document d,String content) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { documentContentService.saveContent(d.getId(),storageBucketName(d),content); }
                catch(RuntimeException ignored) { log.warn("派生MinIO副本写入失败，数据库权威快照已保存: documentId={}",d.getId()); }
            }
        });
    }

    @Override
    @Transactional
    public DocumentVO retryParse(String id,Integer expectedVersion,Long userId) {
        Document d=indexService.requireDocument(id,userId,true,true);
        DocumentIndexService.expected(d,expectedVersion);
        if(!"FAILED".equals(d.getParseState())||d.getFileId()==null) throw new DocumentStateException(409,"PARSE_RETRY_NOT_AVAILABLE","文档不需要重新解析");
        final String content;
        try {
            String name=fileServiceClient.getFileName(d.getFileId()).getBody();
            byte[] bytes=fileServiceClient.downloadFile(d.getFileId()).getBody();
            content=DocumentParserUtil.parseDocumentStrict(bytes,name);
        } catch(Exception e) { throw new DocumentStateException(422,"PARSE_FAILED","解析失败，原始文件仍保留"); }
        d.setVersion(indexService.nextVersion(d));d.setUpdateTime(LocalDateTime.now());d.setSummary(DocumentParserUtil.getSummary(content,200));
        indexService.snapshotAndSchedule(d,"PLAIN_TEXT",content,"重新解析原始文件",userId,DocumentParserUtil.STRICT_PARSER_VERSION);
        documentMapper.updateById(d);mirrorAfterCommit(d,content);
        DocumentVO vo=convertToVO(d);vo.setContent(content);return vo;
    }

    /**
     * 将Document实体转换为DocumentVO（包含内容）
     * @param document 文档实体
     * @return 文档VO
     */
    private DocumentVO convertToVO(Document document) {
        DocumentVO vo = new DocumentVO();
        vo.setId(document.getId());
        vo.setContentFormat(document.getContentFormat()==null?"LEGACY_UNKNOWN":document.getContentFormat());
        vo.setContentHash(document.getContentHash());
        vo.setSnapshotState(document.getSnapshotState()==null?"MIGRATION_REQUIRED":document.getSnapshotState());
        vo.setParseState(document.getParseState()==null?"UNKNOWN":document.getParseState());
        vo.setTitle(document.getTitle());
        vo.setSummary(document.getSummary());
        vo.setKeywords(convertJsonToList(document.getKeywords()));
        vo.setFileId(document.getFileId());
        vo.setUserId(document.getUserId());
        vo.setBucketName(storageBucketName(document));
        vo.setObjectName(storageObjectName(document));
        vo.setCategory(document.getCategory());
        vo.setTags(convertJsonToList(document.getTags()));
        vo.setVersion(document.getVersion());
        vo.setStatus(document.getStatus());
        vo.setCreatedBy(document.getCreatedBy());
        vo.setCreateTime(document.getCreateTime());
        vo.setUpdateTime(document.getUpdateTime());
        return vo;
    }

    /**
     * 将Document实体转换为DocumentVO（不包含内容，用于列表查询）
     * @param document 文档实体
     * @return 文档VO
     */
    private DocumentVO convertToVOWithoutContent(Document document) {
        return convertToVO(document);
    }

    private String storageBucketName(Document document) {
        if (document.getBucketName() != null && !document.getBucketName().isBlank()) {
            return document.getBucketName();
        }
        return documentContentService.getBucketName(document.getUserId());
    }

    private String storageObjectName(Document document) {
        if (document.getObjectName() != null && !document.getObjectName().isBlank()) {
            return document.getObjectName();
        }
        return documentContentService.getObjectName(document.getId());
    }

    /**
     * 将List转换为JSON字符串
     * @param list 字符串列表
     * @return JSON字符串
     */
    private String convertListToJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 将JSON字符串转换为List
     * @param json JSON字符串
     * @return 字符串列表
     */
    private List<String> convertJsonToList(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return Arrays.asList(objectMapper.readValue(json, String[].class));
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}
