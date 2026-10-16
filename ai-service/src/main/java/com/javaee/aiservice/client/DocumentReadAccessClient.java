package com.javaee.aiservice.client;

import com.javaee.aiservice.rag.SegmentReadException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class DocumentReadAccessClient {
    private final RestTemplate rest;
    private final String baseUrl;

    public DocumentReadAccessClient(@Value("${document.service.url:http://document:8084}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.rest = new RestTemplate(factory);
    }

    public void assertCanRead(String id, String userId, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SegmentReadException(401, "请先登录");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        try {
            var uri = UriComponentsBuilder.fromHttpUrl(baseUrl).pathSegment("api", "documents", id, "read-access")
                    .build().encode().toUri();
            Map<?, ?> body = rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (body == null || !Integer.valueOf(200).equals(body.get("code"))
                    || !(body.get("data") instanceof Map<?, ?> data)
                    || !id.equals(data.get("documentId")) || !userId.equals(data.get("grantedUserId"))) {
                throw new SegmentReadException(503, "文档权限服务暂不可用");
            }
        } catch (HttpStatusCodeException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401) throw new SegmentReadException(401, "请先登录");
            if (status == 403 || status == 404) throw new SegmentReadException(404, "文档不存在或无权访问");
            throw new SegmentReadException(503, "文档权限服务暂不可用");
        } catch (SegmentReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SegmentReadException(503, "文档权限服务暂不可用");
        }
    }
}
