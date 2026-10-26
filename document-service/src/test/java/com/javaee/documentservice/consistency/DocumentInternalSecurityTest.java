package com.javaee.documentservice.consistency;

import com.javaee.documentservice.security.InternalIndexAuthenticationFilter;
import com.javaee.documentservice.security.RequestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class DocumentInternalSecurityTest {
    private static final String KEY="test-only-32-byte-token-never-production";
    @AfterEach void clear(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
    @Test void publicIdentityNeverFallsBackToForgedHeader() {
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-User-Id","7");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(new RequestUserContext().getCurrentUserId()).isEmpty();
        assertThatThrownBy(()->new RequestUserContext().getRequiredUserId()).isInstanceOfSatisfying(DocumentStateException.class,e->assertThat(e.getStatus()).isEqualTo(401));
    }
    @Test void authenticatedPrincipalTakesPrecedenceOverUserHeader() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,null,List.of()));
        assertThat(new RequestUserContext().getRequiredUserId()).isEqualTo(7L);
    }
    @Test void missingInternalConfigurationFailsClosedEvenWithJwt() throws Exception {
        assertThat(run("",null).getStatus()).isEqualTo(503);
    }
    @Test void userJwtOrWrongServiceTokenCannotAuthorizeInternalTasks() throws Exception {
        assertThat(run(KEY,null).getStatus()).isEqualTo(401);assertThat(run(KEY,"wrong").getStatus()).isEqualTo(401);
    }
    @Test void independentValidServiceTokenOpensOnlyInternalFilter() throws Exception {
        assertThat(run(KEY,KEY).getStatus()).isEqualTo(204);
    }
    private MockHttpServletResponse run(String configured,String provided) throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/internal/index/tasks/claim");request.setServletPath("/internal/index/tasks/claim");
        request.addHeader("Authorization","Bearer synthetic-user-token");request.addHeader("X-User-Id","7");if(provided!=null)request.addHeader("X-OmniDoc-Index-Token",provided);
        MockHttpServletResponse response=new MockHttpServletResponse();AtomicBoolean ran=new AtomicBoolean();
        new InternalIndexAuthenticationFilter(configured).doFilter(request,response,(req,res)->{ran.set(true);((jakarta.servlet.http.HttpServletResponse)res).setStatus(204);});
        assertThat(ran.get()).isEqualTo(response.getStatus()==204);return response;
    }
}
