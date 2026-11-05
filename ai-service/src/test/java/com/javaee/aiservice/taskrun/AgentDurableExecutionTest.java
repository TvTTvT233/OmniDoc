package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.agent.execution.AgentExecutionService;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.conversation.*;
import com.javaee.aiservice.conversation.model.MessageAppendCommand;
import com.javaee.aiservice.internal.InternalService;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.aiservice.service.*;
import com.javaee.aiservice.vo.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentDurableExecutionTest {
    AgentRunRecoveryTest fixture;
    ConversationManager conversations; ContextManager context; RequestUserContext users;
    ChatService chat; AIService ai; InternalService internal; FileDeleteService files;
    Map<String,String> messages;
    @BeforeEach void setup() {
        fixture=new AgentRunRecoveryTest(); fixture.setup(); messages=new LinkedHashMap<>();
        conversations=mock(ConversationManager.class); context=mock(ContextManager.class); users=mock(RequestUserContext.class);
        chat=mock(ChatService.class); ai=mock(AIService.class); internal=mock(InternalService.class); files=mock(FileDeleteService.class);
        when(users.getRequiredUserId()).thenReturn("u"); when(users.getCurrentRole()).thenReturn("user");
        when(conversations.createConversation("u")).thenReturn("c"); when(context.getContext("c")).thenAnswer(i->new HashMap<>());
        when(chat.callChatApiWithModelCode(any(),any())).thenThrow(new IllegalStateException("model disabled deterministic fixture"));
        when(internal.hasPermission(any(),any())).thenReturn(true);
        when(ai.analyze(any())).thenReturn(new TextAnalyzeVO(4,0,4,0,0,0,1,1,1));
        when(files.deleteFile(any(),any())).thenReturn(new FileDeleteVO("deleted",null,null,null,"deleted fixture"));
        when(conversations.appendMessagesForUser(any(),any(),anyList())).thenAnswer(invocation -> {
            List<MessageAppendCommand> batch=invocation.getArgument(2);
            for(var message:batch) {
                String key=message.clientRequestId()+":"+message.turnOrdinal();
                String previous=messages.putIfAbsent(key,message.content());
                assertThat(previous==null || previous.equals(message.content())).as("stable message content for %s",key).isTrue();
            }
            return List.of();
        });
    }
    AgentExecutionService service() {
        var service=new AgentExecutionService();
        Map<String,Object> fields=Map.ofEntries(Map.entry("conversationManager",conversations),Map.entry("contextManager",context),
                Map.entry("requestUserContext",users),Map.entry("chatService",chat),Map.entry("aiService",ai),Map.entry("internalService",internal),
                Map.entry("fileDeleteService",files),Map.entry("taskRegistry",new AgentTaskRegistry()),Map.entry("toolRegistry",new AgentToolRegistry()),
                Map.entry("durableStore",fixture.store));
        fields.forEach((key,value)->ReflectionTestUtils.setField(service,key,value)); return service;
    }
    AgentExecutionRequest request(String task) {
        var request=new AgentExecutionRequest(); request.setTask(task); request.setConversationId("c");
        request.setClientRequestId("request-1"); request.setReflectionEnabled(false);request.setAutoReplan(false);
        request.setContext(Map.of("content","test")); return request;
    }
    String trace() { return fixture.store.findRequest("u","request-1").orElseThrow().id(); }
    String approval(Map<String,Object> response) { return String.valueOf(((Map<?,?>)response.get("pendingApproval")).get("agentApprovalToken")); }
    AgentExecutionRequest confirmation(String token) { var r=new AgentExecutionRequest();r.setApprovalToken(token);return r; }

    @Test void duplicateSubmissionReusesResultAndDoesNotAppendMessages() {
        var request=request("统计文本"); var first=service().execute(request); int count=messages.size();
        var duplicate=service().execute(request);
        assertThat(first.get("status")).isEqualTo("success"); assertThat(duplicate.get("traceId")).isEqualTo(first.get("traceId"));
        verify(ai,times(1)).analyze(any()); assertThat(messages).hasSize(count);
    }
    @Test void crashAfterToolResultBeforeCheckpointReusesResultAndStableMessageKeys() {
        doThrow(new AssertionError("simulated process exit after result commit")).doNothing().when(internal).logAudit(any(),any(),any());
        assertThatThrownBy(()->service().execute(request("统计文本"))).isInstanceOf(AssertionError.class);
        fixture.expire(trace()); var result=service().executeDurable("u",trace());
        assertThat(result.get("status")).isEqualTo("success"); verify(ai,times(1)).analyze(any());
        assertThat(messages).hasSize(4);
    }
    @Test void writeResponseLostAcrossRestartStopsWithoutSecondDispatch() {
        var request=request("删除文件");request.setContext(Map.of("objectName","synthetic.txt","bucketName","b"));
        var first=service().execute(request); String token=approval(first);
        verify(files,never()).deleteFile(any(),any());
        when(files.deleteFile(any(),any())).thenThrow(new AssertionError("remote applied, worker died"));
        assertThatThrownBy(()->service().confirmApproval(confirmation(token))).isInstanceOf(AssertionError.class);
        fixture.expire(trace()); var result=service().executeDurable("u",trace());
        assertThat(result.get("recoveryReason")).isEqualTo("OUTCOME_UNKNOWN");
        service().confirmApproval(confirmation(token)); verify(files,times(1)).deleteFile(any(),any());
    }
    @Test void approvalSurvivesNewServiceAndRepeatedConfirmDoesNotDeleteTwice() {
        var request=request("删除文件");request.setContext(Map.of("objectName","synthetic.txt","bucketName","b"));
        var first=service().execute(request); String token=approval(first);
        var result=service().confirmApproval(confirmation(token));
        assertThat(result.get("status")).isEqualTo("success");
        service().confirmApproval(confirmation(token)); verify(files,times(1)).deleteFile(any(),any());
    }
    @Test void changedApprovalTargetIsRejectedWithoutDispatch() {
        var request=request("删除文件");request.setContext(Map.of("objectName","synthetic.txt","bucketName","b"));
        String token=approval(service().execute(request)); var confirm=confirmation(token);confirm.setContext(Map.of("bucketName","other"));
        assertThatThrownBy(()->service().confirmApproval(confirm)).hasMessageContaining("APPROVAL_PARAMS_CHANGED");
        verify(files,never()).deleteFile(any(),any());
    }
    @Test void crashDuringFinalAnswerDoesNotPlanOrExecuteToolsAgain() {
        var original=request("统计文本");
        doThrow(new AssertionError("exit before assistant append")).when(conversations).appendMessagesForUser(eq("c"),eq("u"),argThat(batch->batch.get(0).turnOrdinal()==1000));
        assertThatThrownBy(()->service().execute(original)).isInstanceOf(AssertionError.class);
        reset(conversations); fixture.expire(trace());
        assertThat(service().executeDurable("u",trace()).get("status")).isEqualTo("success");
        verify(ai,times(1)).analyze(any());
    }
    @Test void completedFirstStepIsNotRepeatedWhenSecondStepCrashes() {
        doReturn("[{\"id\":\"one\",\"toolName\":\"text-analyze\",\"params\":{\"content\":\"first\"}},{\"id\":\"two\",\"toolName\":\"text-analyze\",\"params\":{\"content\":\"second\"}}]").when(chat).callChatApiWithModelCode(any(),any());
        when(ai.analyze(argThat(dto->"second".equals(dto.getContent()))))
                .thenThrow(new AssertionError("exit during readonly step two")).thenReturn(new TextAnalyzeVO(6,0,6,0,0,0,1,1,1));
        assertThatThrownBy(()->service().execute(request("统计文本"))).isInstanceOf(AssertionError.class);
        fixture.expire(trace()); assertThat(service().executeDurable("u",trace()).get("status")).isEqualTo("success");
        verify(ai,times(1)).analyze(argThat(dto->"first".equals(dto.getContent())));
        verify(ai,times(2)).analyze(argThat(dto->"second".equals(dto.getContent())));
    }
    @Test void successfulWriteWithUnmetBusinessCriteriaIsNeverAutomaticallyRetried() {
        doReturn("[{\"id\":\"delete\",\"toolName\":\"file-delete\",\"params\":{\"objectName\":\"synthetic\"},\"successCriteria\":\"data.nonexistent\",\"retryPolicy\":\"exponential\",\"maxRetries\":5}]").when(chat).callChatApiWithModelCode(any(),any());
        String token=approval(service().execute(request("删除文件")));
        assertThat(service().confirmApproval(confirmation(token)).get("status")).isEqualTo("error");
        service().confirmApproval(confirmation(token));verify(files,times(1)).deleteFile(any(),any());
    }
    @Test void lostRabbitWakeupIsRecoveredByPollerAndDuplicateNotificationDoesNotExecuteTwice() {
        var execution=service();
        var jobs=new com.javaee.aiservice.async.AsyncAIJobService();
        var rabbit=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        doThrow(new org.springframework.amqp.AmqpException("simulated broker outage"))
                .when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class));
        ReflectionTestUtils.setField(jobs,"durableStore",fixture.store);
        ReflectionTestUtils.setField(jobs,"agentExecutionService",execution);
        ReflectionTestUtils.setField(jobs,"rabbitTemplate",rabbit);
        var request=request("统计文本");
        var job=jobs.submit("agent",request,null,"u");
        assertThat(jobs.submit("agent",request,null,"u").getJobId()).isEqualTo(job.getJobId());
        assertThat(fixture.store.recoverable(5)).hasSize(1);
        var worker=new com.javaee.aiservice.taskrun.recovery.AgentRecoveryWorker(fixture.store,execution);
        worker.poll();worker.handleAsync(job.getJobId());worker.handleAsync(job.getJobId());
        assertThat(jobs.findOwned(job.getJobId(),"u").orElseThrow().getStatus()).isEqualTo("success");
        verify(ai,times(1)).analyze(any());
    }
}
