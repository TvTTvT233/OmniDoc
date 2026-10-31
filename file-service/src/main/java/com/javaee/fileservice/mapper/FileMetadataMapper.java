package com.javaee.fileservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaee.fileservice.entity.FileMetadata;
import org.apache.ibatis.annotations.Param;

/**
 * 文件元数据数据访问接口
 */
public interface FileMetadataMapper extends BaseMapper<FileMetadata> {

    java.util.List<FileMetadata> selectOwnedActivePage(
            @Param("ownerId") String ownerId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 根据文件ID获取文件元数据
     */
    FileMetadata selectByFileId(String fileId);

    /**
     * Owner-aware lookup. owner_id NULL historical rows deliberately do not match.
     */
    FileMetadata selectOwnedActiveByFileId(
            @Param("ownerId") String ownerId,
            @Param("fileId") String fileId);

    /**
     * 根据文件名搜索文件
     */
    java.util.List<FileMetadata> searchByFileName(String keyword);

}
