package com.javaee.fileservice.controller;

import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.utils.JwtUtils;
import com.javaee.fileservice.config.SecurityConfig;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.security.AuthenticatedFileOwner;
import com.javaee.fileservice.service.OwnerAwareFileService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OwnerAwareFileController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        AuthenticatedFileOwner.class, OwnerAwareFileExceptionHandler.class})
@ImportAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
class OwnerAwareJwtCompatibilityR1Test {
    @Autowired MockMvc mvc;
    @MockBean OwnerAwareFileService files;
    @MockBean FileMetadataMapper metadataMapper;

    @Test
    void missingBearerTokenRemainsUnauthorized() throws Exception {
        mvc.perform(get("/api/files/owned/synthetic/metadata"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBearerTokenRemainsUnauthorized() throws Exception {
        mvc.perform(get("/api/files/owned/synthetic/metadata")
                        .header("Authorization", "Bearer synthetically-invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredBearerTokenRemainsUnauthorized() throws Exception {
        mvc.perform(get("/api/files/owned/synthetic/metadata")
                        .header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized());
    }

    private static String expiredToken() throws ReflectiveOperationException {
        Field keyField = JwtUtils.class.getDeclaredField("KEY");
        keyField.setAccessible(true);
        SecretKey key = (SecretKey) keyField.get(null);
        Instant now = Instant.now();
        return Jwts.builder()
                .setClaims(Map.of("userId", 42L, "username", "synthetic", "role", "user"))
                .setIssuedAt(Date.from(now.minusSeconds(120)))
                .setExpiration(Date.from(now.minusSeconds(60)))
                .signWith(key)
                .compact();
    }
}
