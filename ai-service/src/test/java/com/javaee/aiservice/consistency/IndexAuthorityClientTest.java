package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import java.util.List;
import static com.javaee.aiservice.consistency.IndexContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IndexAuthorityClientTest {
    @Test void cleanupNullGenerationIsSerializedAndAcknowledged() {
        IndexAuthorityClient client=new IndexAuthorityClient(new ObjectMapper(),"http://doc","a".repeat(32));
        MockRestServiceServer server=MockRestServiceServer.createServer((RestTemplate)ReflectionTestUtils.getField(client,"http"));
        server.expect(requestTo("http://doc/internal/index/tasks/cleanup/complete")).andExpect(header("X-OmniDoc-Index-Token","a".repeat(32)))
                .andExpect(content().json("{\"generationId\":\"\",\"leaseToken\":\"lease\",\"segments\":[]}"))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"published\":false,\"accepted\":true}}",MediaType.APPLICATION_JSON));
        Task task=new Task("cleanup","lease","","CLEANUP","doc",2,null,"1","default",null,null,null,null,null,null,List.of("old"));
        assertThat(client.complete(task,List.of())).isFalse(); server.verify();
    }
    @Test void weakOrAbsentServiceSecretNeverMakesHttpCall() {
        IndexAuthorityClient client=new IndexAuthorityClient(new ObjectMapper(),"http://127.0.0.1:1","");
        assertThatThrownBy(()->client.claim("worker")).hasMessage("INDEX_INTERNAL_AUTH_DISABLED");
    }
    @Test void malformedAuthorityBodyCannotGrantVisibility() {
        IndexAuthorityClient client=new IndexAuthorityClient(new ObjectMapper(),"http://doc","a".repeat(32));
        MockRestServiceServer server=MockRestServiceServer.createServer((RestTemplate)ReflectionTestUtils.getField(client,"http"));
        server.expect(requestTo("http://doc/internal/index/visibility")).andRespond(withSuccess("{\"code\":200,\"data\":{}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.visibility("1","default",List.of(new Candidate("doc",2,"gen")))).hasMessage("INDEX_AUTHORITY_UNAVAILABLE"); server.verify();
    }
}
