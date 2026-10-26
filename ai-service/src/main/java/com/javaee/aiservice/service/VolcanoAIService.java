package com.javaee.aiservice.service;

import com.javaee.aiservice.logging.SafeDiagnosticLogger;
import com.javaee.aiservice.agent.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VolcanoAIService {

    private static final Logger log = LoggerFactory.getLogger(VolcanoAIService.class);

    private final ChatService chatService;

    @Autowired
    public VolcanoAIService(ChatService chatService) {
        this.chatService = chatService;
    }

    public String callAI(String prompt) {
        log.info("使用阿里云百炼调用 AI API");
        log.info("AI请求已准备: promptChars={}", prompt == null ? 0 : prompt.length());

        try {
            String content = chatService.callChatApi(prompt);
            log.info("AI请求完成: responseChars={}", content == null ? 0 : content.length());
            return content;
        } catch (Exception e) {
            SafeDiagnosticLogger.error(log, "MODEL_CALL_FAILED", "REMOTE_MODEL_CALL_FAILED",
                    e, true, 0, 1, null, null);
            throw new RuntimeException("调用 AI API 失败: " + e.getMessage(), e);
        }
    }
}
