package com.javaee.fileservice.controller;

import com.javaee.fileservice.dto.OwnedFileMetadataDTO;
import com.javaee.fileservice.security.AuthenticatedFileOwner;
import com.javaee.fileservice.service.OwnerAwareFileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/files/owned")
public class OwnerAwareFileController {
    private final AuthenticatedFileOwner owners;
    private final OwnerAwareFileService files;

    public OwnerAwareFileController(AuthenticatedFileOwner owners, OwnerAwareFileService files) {
        this.owners = owners;
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OwnedFileMetadataDTO> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(files.upload(owners.requireOwnerId(), file));
    }

    @GetMapping
    public com.javaee.fileservice.dto.OwnedFileListDTO list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid file pagination");
        }
        return files.list(owners.requireOwnerId(), page, size);
    }

    @GetMapping("/{fileId}/metadata")
    public OwnedFileMetadataDTO metadata(@PathVariable String fileId) {
        return files.getMetadata(owners.requireOwnerId(), fileId);
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String fileId) {
        OwnerAwareFileService.OwnedFileStream stream = files.open(owners.requireOwnerId(), fileId);
        return contentResponse(stream);
    }

    static ResponseEntity<InputStreamResource> contentResponse(OwnerAwareFileService.OwnedFileStream stream) {
        OwnedFileMetadataDTO metadata = stream.metadata();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType(metadata.declaredContentType()));
        headers.setContentLength(metadata.sizeBytes());
        headers.setETag('"' + metadata.contentDigest() + '"');
        headers.set("X-Content-SHA256", metadata.contentDigest());
        headers.set("Digest", "sha-256=" + Base64.getEncoder().encodeToString(
                HexFormat.of().parseHex(metadata.contentDigest())));
        headers.set("X-Content-Type-Signature", metadata.contentSignature());
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(stream.input()));
    }

    private static MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (Exception ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
