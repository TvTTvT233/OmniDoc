package com.javaee.aiservice.controller;

import com.javaee.aiservice.client.DocumentReadAccessClient;
import com.javaee.aiservice.config.SecurityConfig;
import com.javaee.aiservice.rag.DocumentSegmentReadService;
import com.javaee.aiservice.rag.SegmentReadException;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.common.utils.JwtUtils;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentSegmentsControllerTest {
    AnnotationConfigWebApplicationContext context; MockMvc mvc;
    DocumentReadAccessClient access; DocumentSegmentReadService segments;
    String token;
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class,JwtAuthenticationFilter.class,DocumentSegmentsController.class,RequestUserContext.class})
    static class Config {
        @Bean DocumentReadAccessClient access() { return mock(DocumentReadAccessClient.class); }
        @Bean DocumentSegmentReadService segments() { return mock(DocumentSegmentReadService.class); }
        @Bean com.javaee.aiservice.consistency.CurrentGenerationRetrieval generations() { return mock(com.javaee.aiservice.consistency.CurrentGenerationRetrieval.class); }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        access=context.getBean(DocumentReadAccessClient.class); segments=context.getBean(DocumentSegmentReadService.class);
        token="Bearer "+JwtUtils.generateToken(1L,"synthetic","user");
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    @AfterEach void close() { context.close(); }
    @Test void authenticatedNotIndexedHasExplicitState() throws Exception {
        when(segments.read("d","1")).thenReturn(new DocumentSegmentReadService.View("NOT_INDEXED",List.of()));
        mvc.perform(get("/api/ai/rag/document/d/segments").header("Authorization",token).header("X-User-Id","9").param("userId","9"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.indexState").value("NOT_INDEXED"))
                .andExpect(jsonPath("$.data.segments").isEmpty());
        verify(access).assertCanRead("d","1",token);
    }
    @Test void availableSegmentsKeepContentAndState() throws Exception {
        when(segments.read("d","1")).thenReturn(new DocumentSegmentReadService.View("SEGMENTS_AVAILABLE",
                List.of(Map.of("segmentId","d_seg_0","content","synthetic"))));
        mvc.perform(get("/api/ai/rag/document/d/segments").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.segments[0].content").value("synthetic"));
    }
    @Test void missingJwtCannotUseForgedHeader() throws Exception {
        mvc.perform(get("/api/ai/rag/document/d/segments").header("X-User-Id","1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(access,segments);
    }
    @Test void permissionDenialStopsBeforeRedis() throws Exception {
        doThrow(new SegmentReadException(404,"文档不存在或无权访问")).when(access).assertCanRead("d","1",token);
        mvc.perform(get("/api/ai/rag/document/d/segments").header("Authorization",token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(segments);
    }
    @Test void storageAndOwnerFailuresKeepActualHttpStatus() throws Exception {
        for(int code:List.of(403,409,503)) {
            doThrow(new SegmentReadException(code,"safe message")).when(segments).read("d","1");
            mvc.perform(get("/api/ai/rag/document/d/segments").header("Authorization",token))
                    .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.data").isEmpty());
        }
    }
}
