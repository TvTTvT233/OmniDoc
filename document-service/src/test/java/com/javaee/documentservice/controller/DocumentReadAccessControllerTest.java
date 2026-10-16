package com.javaee.documentservice.controller;

import com.javaee.common.utils.JwtUtils;
import com.javaee.documentservice.config.SecurityConfig;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.mapper.DocumentAccessMapper;
import com.javaee.documentservice.service.DocumentAccessService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentReadAccessControllerTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    DocumentMapper mapper;
    DocumentAccessMapper acl;
    Document document;
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class,DocumentReadAccessController.class,DocumentAccessService.class})
    static class Config {
        @Bean DocumentMapper mapper() { return mock(DocumentMapper.class); }
        @Bean DocumentAccessMapper acl() { return mock(DocumentAccessMapper.class); }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        mapper = context.getBean(DocumentMapper.class); acl = context.getBean(DocumentAccessMapper.class);
        document = new Document(); document.setId("d"); document.setUserId(1L); document.setStatus("active");
        document.setContent("must-never-be-returned");
        when(mapper.selectById("d")).thenReturn(document);
        when(acl.selectCount(any())).thenReturn(0L);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    @AfterEach void close() { context.close(); }
    String token(long id) { return "Bearer "+JwtUtils.generateToken(id,"synthetic","user"); }
    @Test void ownerGetsOnlyPermissionProjection() throws Exception {
        mvc.perform(get("/api/documents/d/read-access").header("Authorization",token(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.documentId").value("d"))
                .andExpect(jsonPath("$.data.grantedUserId").value("1"))
                .andExpect(jsonPath("$.data.content").doesNotExist());
    }
    @Test void forgedUserHeaderCannotAuthenticate() throws Exception {
        mvc.perform(get("/api/documents/d/read-access").header("X-User-Id","1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, acl);
    }
    @Test void forgedHeaderAndQueryCannotOverrideOtherJwt() throws Exception {
        mvc.perform(get("/api/documents/d/read-access").header("Authorization",token(2))
                .header("X-User-Id","1").param("userId","1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void malformedTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/documents/d/read-access").header("Authorization","Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper);
    }
    @Test void collaboratorUsesRealAcl() throws Exception {
        when(acl.selectCount(any())).thenReturn(1L);
        mvc.perform(get("/api/documents/d/read-access").header("Authorization",token(2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grantedUserId").value("2"));
    }
    @Test void missingAndDeletedDocumentsAreNotAccessible() throws Exception {
        mvc.perform(get("/api/documents/missing/read-access").header("Authorization",token(1)))
                .andExpect(status().isNotFound());
        document.setStatus("deleted");
        mvc.perform(get("/api/documents/d/read-access").header("Authorization",token(1)))
                .andExpect(status().isNotFound());
    }
    @Test void databaseFaultIsUnavailableAndDoesNotExposeDetails() throws Exception {
        when(mapper.selectById("d")).thenThrow(new IllegalStateException("private jdbc details"));
        mvc.perform(get("/api/documents/d/read-access").header("Authorization",token(1)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("文档权限服务暂不可用"));
    }
}
