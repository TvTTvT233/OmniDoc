package com.javaee.documentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Dedicated service identity. User JWTs and gateway user headers never authorize this channel. */
public class InternalIndexAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] token;
    public InternalIndexAuthenticationFilter(String token) { this.token=token.getBytes(StandardCharsets.UTF_8); }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().startsWith("/internal/index/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String provided=request.getHeader("X-OmniDoc-Index-Token");
        int status=token.length<32?503:(provided!=null&&provided.length()<=1024&&MessageDigest.isEqual(token,provided.getBytes(StandardCharsets.UTF_8))?200:401);
        if(status!=200) {
            response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(status==503?"{\"errorCode\":\"INTERNAL_CHANNEL_DISABLED\",\"message\":\"内部索引通道未配置\"}":"{\"errorCode\":\"INTERNAL_UNAUTHORIZED\",\"message\":\"内部身份验证失败\"}");return;
        }
        if(request.getContentLengthLong()>16L*1024*1024) {response.setStatus(413);response.setContentType("application/json");response.getWriter().write("{\"errorCode\":\"REQUEST_TOO_LARGE\",\"message\":\"Request too large\"}");return;}
        chain.doFilter(request,response);
    }
}
