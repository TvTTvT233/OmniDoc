package com.javaee.fileservice.dto;

import java.util.List;

public record OwnedFileListDTO(List<OwnedFileMetadataDTO> items, boolean hasMore) { }
