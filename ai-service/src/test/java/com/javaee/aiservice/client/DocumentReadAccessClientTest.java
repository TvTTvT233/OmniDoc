package com.javaee.aiservice.client;

import com.javaee.aiservice.rag.SegmentReadException;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DocumentReadAccessClientTest {
    DocumentReadAccessClient client; MockRestServiceServer server;
    @BeforeEach void setup() {
        client=new DocumentReadAccessClient("http://document.test");
        server=MockRestServiceServer.createServer((RestTemplate)ReflectionTestUtils.getField(client,"rest"));
    }
    void response(String body) {
        server.expect(requestTo("http://document.test/api/documents/d/read-access"))
                .andExpect(header("Authorization","Bearer synthetic"))
                .andExpect(headerDoesNotExist("X-User-Id"))
                .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    @Test void onlyMatchingPermissionProjectionIsAccepted() {
        response("{\"code\":200,\"data\":{\"documentId\":\"d\",\"grantedUserId\":\"1\"}}");
        client.assertCanRead("d","1","Bearer synthetic"); server.verify();
    }
    @Test void mismatchedIdentityIsUnavailableRatherThanGranted() {
        response("{\"code\":200,\"data\":{\"documentId\":\"d\",\"grantedUserId\":\"2\"}}");
        fails(503); server.verify();
    }
    @Test void missingOrLegacyResponseIsNotTrusted() {
        response("{\"code\":200,\"data\":{\"content\":\"private\"}}"); fails(503);
    }
    @Test void documentMissingAndAccessDeniedStayInaccessible() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND)); fails(404);
    }
    @Test void networkFaultIsUnavailableNotNotIndexed() {
        server.expect(anything()).andRespond(withException(new IOException("private address"))); fails(503);
    }
    @Test void invalidAuthenticationIsUnauthorized() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED)); fails(401);
    }
    @Test void serverFailureDoesNotLeakUpstreamBody() {
        server.expect(anything()).andRespond(withServerError().body("private failure detail")); fails(503);
    }
    void fails(int status) {
        assertThatThrownBy(() -> client.assertCanRead("d","1","Bearer synthetic"))
                .isInstanceOfSatisfying(SegmentReadException.class,e -> assertThat(e.status()).isEqualTo(status));
    }
}
