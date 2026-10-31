package com.javaee.fileservice.controller;

import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.utils.JwtUtils;
import com.javaee.fileservice.config.SecurityConfig;
import com.javaee.fileservice.dto.OwnedFileMetadataDTO;
import com.javaee.fileservice.exception.OwnedFileNotFoundException;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.service.OwnerAwareFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InternalReviewFileController.class,
        properties = "omnidoc.review.internal-token=synthetic-review-internal-token-test-only-123456")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OwnerAwareFileExceptionHandler.class})
class InternalReviewFileSecurityTest {
    private static final String TOKEN = "synthetic-review-internal-token-test-only-123456";
    @Autowired MockMvc mvc;
    @MockBean OwnerAwareFileService files;
    @MockBean FileMetadataMapper metadataMapper;

    @Test
    void userJwtAndForgedOwnerCannotAuthorizeInternalReads() throws Exception {
        for (String suffix : new String[]{"metadata", "content"}) {
            mvc.perform(get("/internal/review-files/file-a/" + suffix)
                            .header("Authorization", "Bearer " + JwtUtils.generateToken(42L, "synthetic", "user"))
                            .header("X-User-Id", "42").header("X-OmniDoc-Review-OwnerId", "42"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/review-files/file-a/" + suffix)
                            .header("X-OmniDoc-Review-Token", "wrong-token")
                            .header("X-OmniDoc-Review-OwnerId", "42"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(files);
    }

    @Test
    void serviceIdentityStillRechecksExactOwnerAndFileAndCannotMutate() throws Exception {
        when(files.getMetadata("42", "file-a")).thenReturn(new OwnedFileMetadataDTO(
                "file-a", "a.pdf", "application/pdf", 4, "0".repeat(64),
                "sha256:" + "0".repeat(64), "PDF", true));
        when(files.getMetadata("43", "file-a")).thenThrow(new OwnedFileNotFoundException());
        mvc.perform(get("/internal/review-files/file-a/metadata")
                        .header("X-OmniDoc-Review-Token", TOKEN).header("X-OmniDoc-Review-OwnerId", "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fileId").value("file-a"));
        mvc.perform(get("/internal/review-files/file-a/metadata")
                        .header("X-OmniDoc-Review-Token", TOKEN).header("X-OmniDoc-Review-OwnerId", "43"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("OWNED_FILE_NOT_FOUND"));
        mvc.perform(delete("/internal/review-files/file-a/metadata")
                        .header("X-OmniDoc-Review-Token", TOKEN).header("X-OmniDoc-Review-OwnerId", "42"))
                .andExpect(status().isMethodNotAllowed());
        verify(files).getMetadata("42", "file-a");
        verify(files).getMetadata("43", "file-a");
        verifyNoMoreInteractions(files);
    }

    @Test
    void missingOrMalformedOwnerIs400WithoutCallingFileService() throws Exception {
        mvc.perform(get("/internal/review-files/file-a/metadata")
                        .header("X-OmniDoc-Review-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("OWNED_FILE_REQUEST_INVALID"));
        mvc.perform(get("/internal/review-files/file-a/content")
                        .header("X-OmniDoc-Review-Token", TOKEN)
                        .header("X-OmniDoc-Review-OwnerId", "../other"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(files);
    }
}
