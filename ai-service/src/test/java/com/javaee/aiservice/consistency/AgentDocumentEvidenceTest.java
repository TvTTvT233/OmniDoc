package com.javaee.aiservice.consistency;

import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.agent.execution.AgentExecutionService;
import com.javaee.aiservice.agent.execution.model.*;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.conversation.*;
import com.javaee.aiservice.internal.InternalService;
import com.javaee.aiservice.rag.KnowledgeBase;
import com.javaee.aiservice.security.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.javaee.aiservice.consistency.IndexContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentDocumentEvidenceTest {
    final Reference reference=new Reference("ref","doc",1,"title","gen_seg_0","gen",hash("text"),0,4,hash("text"));
    @Test void toolAndUserParametersCannotImpersonateRetrievalOwner() {
        AgentExecutionService service=new AgentExecutionService(); AgentExecutionRequest request=new AgentExecutionRequest(); request.setUserId("trusted"); request.setTask("知识库");
        AgentPlanStep step=new AgentPlanStep("step","search","rag-search",new HashMap<>(Map.of("userId","forged")));
        ReflectionTestUtils.invokeMethod(service,"fillDefaultParams",step,request,new HashMap<>(Map.of("userId","also-forged")));
        assertThat(step.getParams().get("userId")).isEqualTo("trusted");
    }
    @Test void unrelatedToolCannotInventDocumentReferences() {
        AgentExecutionService service=new AgentExecutionService(); ReflectionTestUtils.setField(service,"currentGenerations",mock(CurrentGenerationRetrieval.class));
        Object refs=ReflectionTestUtils.invokeMethod(service,"collectDocumentReferences",List.of(AgentToolResult.success("mcp.search","fake",Map.of("references",List.of(reference)))));
        assertThat((List<?>)refs).isEmpty();
    }
    @Test void multiToolReferencesDeduplicateAndOverLimitFailsExplicitly() {
        AgentExecutionService service = new AgentExecutionService(); ReflectionTestUtils.setField(service,"currentGenerations",mock(CurrentGenerationRetrieval.class));
        var one = AgentToolResult.success("rag-search","ok",Map.of("references",List.of(reference)));
        var two = AgentToolResult.success("rag-answer","ok",Map.of("references",List.of(reference)));
        List<?> combined = ReflectionTestUtils.invokeMethod(service,"collectDocumentReferences",List.of(one,two));
        assertThat(combined).hasSize(1);
        List<Reference> refs = new ArrayList<>();
        for (int i=0;i<100;i++) refs.add(new Reference("ref"+i,"doc",1,"title","gen_seg_"+i,"gen",hash("text"),0,4,hash("text")));
        var many = AgentToolResult.success("rag-search","ok",Map.of("references",refs));
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"collectDocumentReferences",List.of(many,one))).hasMessage("INDEX_REFERENCE_LIMIT");
    }
    @Test void emptyEvidenceNeverCallsAnswerModel() {
        AgentExecutionService service=new AgentExecutionService(); ChatService chat=mock(ChatService.class); KnowledgeBase knowledge=mock(KnowledgeBase.class);
        ReflectionTestUtils.setField(service,"chatService",chat); ReflectionTestUtils.setField(service,"knowledgeBase",knowledge);
        AgentExecutionRequest request=new AgentExecutionRequest(); request.setTask("question");
        AgentToolResult result=ReflectionTestUtils.invokeMethod(service,"executeRagAnswer",Map.of("question","q","userId","1"),request,Map.of());
        assertThat(result.getStatus()).isEqualTo("error"); verifyNoInteractions(chat);
    }
    @Test @SuppressWarnings("unchecked") void saveDuringFinalGenerationRemovesStaleAnswerReferencesAndToolText() {
        AgentExecutionService service=new AgentExecutionService(); ChatService chat=mock(ChatService.class);
        ConversationManager conversations=mock(ConversationManager.class); ContextManager context=mock(ContextManager.class);
        InternalService internal=mock(InternalService.class); RequestUserContext users=mock(RequestUserContext.class);
        KnowledgeBase knowledge=mock(KnowledgeBase.class); CurrentGenerationRetrieval current=mock(CurrentGenerationRetrieval.class);
        ReflectionTestUtils.setField(service,"chatService",chat); ReflectionTestUtils.setField(service,"conversationManager",conversations);
        ReflectionTestUtils.setField(service,"contextManager",context); ReflectionTestUtils.setField(service,"internalService",internal);
        ReflectionTestUtils.setField(service,"requestUserContext",users); ReflectionTestUtils.setField(service,"knowledgeBase",knowledge);
        ReflectionTestUtils.setField(service,"currentGenerations",current); ReflectionTestUtils.setField(service,"toolRegistry",new AgentToolRegistry());
        ReflectionTestUtils.setField(service,"taskRegistry",new AgentTaskRegistry());
        when(users.getRequiredUserId()).thenReturn("1"); when(users.getCurrentRole()).thenReturn("user");
        when(conversations.createConversation("1")).thenReturn("conversation"); when(context.getContext(anyString())).thenReturn(new HashMap<>());
        when(internal.hasPermission(anyString(),any())).thenReturn(true);
        when(chat.callChatApiWithModelCode(anyString(),any())).thenReturn("[{\"id\":\"retrieve\",\"description\":\"search\",\"toolName\":\"rag-search\",\"params\":{},\"reasoning\":\"test\"}]","OLD MODEL ANSWER");
        when(knowledge.hybridSearchWithRerank(anyString(),anyInt(),any(),anyString(),anyString())).thenReturn(List.of(Map.of("id","gen_seg_0","content","OLD DOCUMENT TEXT","reference",reference)));
        when(current.references(anyList())).thenReturn(List.of(reference));
        when(current.validateReferences(anyList(),eq("1"),eq("default"))).thenReturn(List.of(reference),List.of());
        AgentExecutionRequest request=new AgentExecutionRequest(); request.setTask("查询知识库"); request.setMaxIterations(1); request.setAutoReplan(false); request.setReflectionEnabled(false);
        Map<String,Object> response=service.execute(request);
        assertThat(response.get("answer").toString()).doesNotContain("OLD MODEL ANSWER");
        assertThat(response.get("references")).isEqualTo(List.of()); assertThat(response.get("status")).isEqualTo("error");
        assertThat(response.get("stoppedReason")).isEqualTo("document_evidence_invalidated");
        List<AgentToolResult> tools=(List<AgentToolResult>)response.get("toolResults");
        assertThat(tools).hasSize(1); assertThat(tools.get(0).getData()).doesNotContainKeys("results","retrieved","content");
    }
}
