package com.javaee.fileservice.controller;

import com.javaee.fileservice.dto.OwnedFileMetadataDTO;
import com.javaee.fileservice.service.OwnerAwareFileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** No arbitrary object keys, mutation, or owner-bypass exists on this worker channel. */
@RestController
@RequestMapping("/internal/review-files")
public final class InternalReviewFileController {
    private final OwnerAwareFileService files;

    public InternalReviewFileController(OwnerAwareFileService files) {
        this.files = files;
    }

    @GetMapping("/{fileId}/metadata")
    public OwnedFileMetadataDTO metadata(@PathVariable String fileId,
            @RequestHeader("X-OmniDoc-Review-OwnerId") String ownerId) {
        return files.getMetadata(validOwner(ownerId), fileId);
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String fileId,
            @RequestHeader("X-OmniDoc-Review-OwnerId") String ownerId) {
        return OwnerAwareFileController.contentResponse(files.open(validOwner(ownerId), fileId));
    }

    private static String validOwner(String ownerId) {
        if (ownerId == null || !ownerId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid internal owner identifier");
        }
        return ownerId;
    }
}
