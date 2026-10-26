package com.javaee.fileservice.service;

import com.javaee.fileservice.dto.OwnedFileMetadataDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface OwnerAwareFileService {
    OwnedFileMetadataDTO upload(String ownerId, MultipartFile file);

    OwnedFileMetadataDTO getMetadata(String ownerId, String fileId);

    OwnedFileStream open(String ownerId, String fileId);

    record OwnedFileStream(OwnedFileMetadataDTO metadata, InputStream input) { }
}
