package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.conversation.api.ConversationPublicMessageMapper;
import com.javaee.aiservice.conversation.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.javaee.aiservice.consistency.IndexContracts.*;
import static org.assertj.core.api.Assertions.*;

class PublicDocumentReferencesTest {
    final Reference ref=new Reference("reference","doc",2,"title","generation_seg_0","generation",hash("text"),0,4,hash("text"));
    ConversationMessage message(ConversationRole role,Map<String,Object> metadata) { return new ConversationMessage("m","c",1,"t","r",1,role,ConversationMessageType.TEXT,"answer","trace",null,metadata,hash("answer"),Instant.now()); }
    @Test void persistedJsonReferencesSurvivePublicAssistantProjection() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); Map<String,Object> metadata=mapper.readValue(mapper.writeValueAsString(Map.of("references",List.of(ref),"secret","hidden")),Map.class);
        var response=new ConversationPublicMessageMapper().toPublic(message(ConversationRole.ASSISTANT,metadata)).orElseThrow();
        assertThat(response.references()).containsExactly(ref); assertThat(response.metadata()).isEmpty();
    }
    @Test void userSuppliedReferenceMetadataNeverBecomesAssistantCitation() {
        assertThat(new ConversationPublicMessageMapper().toPublic(message(ConversationRole.USER,Map.of("references",List.of(ref)))).orElseThrow().references()).isEmpty();
    }
    @Test void legacyMessagesAndArbitraryToolDataDoNotGenerateReferences() {
        var mapper=new ConversationPublicMessageMapper();
        assertThat(mapper.toPublic(message(ConversationRole.ASSISTANT,Map.of("toolResults",List.of(ref)))).orElseThrow().references()).isEmpty();
        assertThat(PublicDocumentReferences.from(List.of(Map.of("referenceId","forged")))).isEmpty();
    }
    @Test void jdbcRoundTripAndIdempotentReplayPreserveReferencesAcrossServiceRestart() {
        var fixture = new com.javaee.aiservice.conversation.ConversationPersistenceTestFixture();
        var first = fixture.newService(); String conversation = first.createConversation("owner").conversationId();
        MessageAppendCommand append = new MessageAppendCommand(null,"turn","request",1,ConversationRole.ASSISTANT,
                ConversationMessageType.TEXT,"answer",null,null,Map.of("source","agent","references",List.of(ref)));
        first.appendMessages(conversation,"owner",List.of(append));
        var restarted = fixture.newService(); restarted.appendMessages(conversation,"owner",List.of(append));
        var messages = restarted.listMessages(conversation,"owner",null,10).items();
        assertThat(messages).hasSize(1);
        assertThat(new ConversationPublicMessageMapper().toPublic(messages.get(0)).orElseThrow().references()).containsExactly(ref);
        Reference changed = new Reference("different",ref.documentId(),ref.documentVersion(),ref.documentTitle(),ref.segmentId(),ref.generationId(),ref.contentHash(),ref.startOffset(),ref.endOffset(),ref.segmentHash());
        MessageAppendCommand conflict = new MessageAppendCommand(null,"turn","request",1,ConversationRole.ASSISTANT,
                ConversationMessageType.TEXT,"answer",null,null,Map.of("source","agent","references",List.of(changed)));
        assertThatThrownBy(()->restarted.appendMessages(conversation,"owner",List.of(conflict))).isInstanceOf(com.javaee.aiservice.conversation.service.ConversationMemoryException.class);
    }
    @Test void jdbcRejectsForgedRoleMalformedReferencesAndUnrelatedMetadata() {
        var fixture = new com.javaee.aiservice.conversation.ConversationPersistenceTestFixture(); var service = fixture.newService();
        String conversation = service.createConversation("owner").conversationId();
        for (MessageAppendCommand command : List.of(
                new MessageAppendCommand(null,"t1","r1",1,ConversationRole.USER,ConversationMessageType.TEXT,"x",null,null,Map.of("source","agent","references",List.of(ref))),
                new MessageAppendCommand(null,"t2","r2",1,ConversationRole.ASSISTANT,ConversationMessageType.TEXT,"x",null,null,Map.of("source","agent","references",List.of(Map.of("referenceId","fake")))),
                new MessageAppendCommand(null,"t3","r3",1,ConversationRole.ASSISTANT,ConversationMessageType.TEXT,"x",null,null,Map.of("source","agent","credentials","secret"))))
            assertThatThrownBy(()->service.appendMessages(conversation,"owner",List.of(command))).isInstanceOf(com.javaee.aiservice.conversation.service.ConversationMemoryException.class);
        assertThat(service.listMessages(conversation,"owner",null,10).items()).isEmpty();
    }
}
