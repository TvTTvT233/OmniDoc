package com.javaee.fileservice.controller;

import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.utils.JwtUtils;
import com.javaee.fileservice.config.SecurityConfig;
import com.javaee.fileservice.dto.OwnedFileMetadataDTO;
import com.javaee.fileservice.exception.OwnedFileNotFoundException;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.security.AuthenticatedFileOwner;
import com.javaee.fileservice.service.OwnerAwareFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OwnerAwareFileController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        AuthenticatedFileOwner.class, OwnerAwareFileExceptionHandler.class})
class OwnerAwareFileSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean OwnerAwareFileService files;
    @MockBean FileMetadataMapper metadataMapper;

    @Test
    void ownedListUsesOnlyJwtOwnerAndValidatesPagination() throws Exception {
        when(files.list("42", 1, 20)).thenReturn(new com.javaee.fileservice.dto.OwnedFileListDTO(
                java.util.List.of(metadata("file-a")), false));
        mvc.perform(get("/api/files/owned").header("X-User-Id", "42"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/files/owned").param("ownerId", "99").param("userId", "99")
                        .header("Authorization", bearer(42L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].fileId").value("file-a"))
                .andExpect(jsonPath("$.hasMore").value(false));
        verify(files).list("42", 1, 20);
        for (String value : java.util.List.of("0", "-1", "10001", "abc", "2147483648")) {
            mvc.perform(get("/api/files/owned").param("page", value).header("Authorization", bearer(42L)))
                    .andExpect(status().isBadRequest());
        }
        for (String value : java.util.List.of("0", "-1", "101", "abc")) {
            mvc.perform(get("/api/files/owned").param("size", value).header("Authorization", bearer(42L)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void forgedUserHeaderWithoutBearerCannotReadOrUpload() throws Exception {
        mvc.perform(get("/api/files/owned/file-a/metadata").header("X-User-Id", "42"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/files/owned")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF".getBytes()))
                        .header("X-User-Id", "42"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedJwtPrincipalIsTheOnlyOwnerPassedToTheService() throws Exception {
        OwnedFileMetadataDTO metadata = metadata("file-a");
        when(files.getMetadata("42", "file-a")).thenReturn(metadata);

        mvc.perform(get("/api/files/owned/file-a/metadata")
                        .header("Authorization", bearer(42L))
                        .header("X-User-Id", "forged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("file-a"));
        verify(files).getMetadata("42", "file-a");
    }

    @Test
    void crossOwnerAndMissingUseTheSame404Shape() throws Exception {
        when(files.getMetadata("42", "hidden")).thenThrow(new OwnedFileNotFoundException());
        mvc.perform(get("/api/files/owned/hidden/metadata")
                        .header("Authorization", bearer(42L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OWNED_FILE_NOT_FOUND"));
    }

    @Test
    void newUploadIsBoundToVerifiedJwtOwner() throws Exception {
        when(files.upload(org.mockito.ArgumentMatchers.eq("42"), any()))
                .thenReturn(metadata("file-new"));
        mvc.perform(multipart("/api/files/owned")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF".getBytes()))
                        .header("Authorization", bearer(42L))
                        .header("X-User-Id", "99"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value("file-new"));
        verify(files).upload(org.mockito.ArgumentMatchers.eq("42"), any());
    }

    private static OwnedFileMetadataDTO metadata(String fileId) {
        return new OwnedFileMetadataDTO(fileId, "a.pdf", "application/pdf", 4,
                "0".repeat(64), "sha256:" + "0".repeat(64), "PDF", true);
    }

    private static String bearer(long ownerId) {
        return "Bearer " + JwtUtils.generateToken(ownerId, "synthetic", "user");
    }
}
