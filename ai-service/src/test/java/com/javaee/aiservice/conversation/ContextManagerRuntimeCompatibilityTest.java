package com.javaee.aiservice.conversation;

import com.javaee.aiservice.conversation.runtime.LegacyRuntimeStateReader;
import com.javaee.aiservice.conversation.runtime.RuntimeStateScope;
import com.javaee.aiservice.conversation.runtime.RuntimeStateService;
import com.javaee.aiservice.conversation.runtime.RuntimeStateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextManagerRuntimeCompatibilityTest {

    private RuntimeStateService runtimeStateService;
    private LegacyRuntimeStateReader legacyReader;
    private ContextManager manager;

    @BeforeEach
    void setUp() {
        runtimeStateService = mock(RuntimeStateService.class);
        legacyReader = mock(LegacyRuntimeStateReader.class);
        manager = new ContextManager(runtimeStateService, legacyReader);
    }

    @Test
    void legacyReadReturnsOnlyReaderControlledFields() {
        when(legacyReader.readOptional("conv-1")).thenReturn(
                LegacyRuntimeStateReader.LegacyReadResult.available(Map.of(
                        "currentDocumentId", "doc-1",
                        "selectedText", "bounded selection"
                ))
        );

        Map<String, Object> result = manager.getContext("conv-1");

        assertThat(result).containsOnly(
                Map.entry("currentDocumentId", "doc-1"),
                Map.entry("selectedText", "bounded selection")
        );
    }

    @Test
    void oldMapWriteOnlyConvertsWhitelistedScalarsWithExplicitTrace() {
        manager.updateContext("conv-1", Map.of(
                "traceId", "trace-1",
                "documentId", "doc-1",
                "selectedText", "selection",
                "pageContext", "/editor/1",
                "approvalToken", "must-not-persist",
                "lastToolResults", Map.of("secret", "full-result"),
                "arbitrary", "must-not-persist"
        ));

        org.mockito.ArgumentCaptor<RuntimeStateUpdate> updateCaptor =
                org.mockito.ArgumentCaptor.forClass(RuntimeStateUpdate.class);
        verify(runtimeStateService).update(
                eq(new RuntimeStateScope("conv-1", "trace-1")),
                updateCaptor.capture()
        );
        RuntimeStateUpdate update = updateCaptor.getValue();
        assertThat(update.currentDocumentId()).isEqualTo("doc-1");
        assertThat(update.selectedText()).isEqualTo("selection");
        assertThat(update.pageContext()).isEqualTo("/editor/1");
        assertThat(update.pendingApproval()).isNull();
        assertThat(update.currentToolState()).isNull();
    }

    @Test
    void traceLessMapWriteIsNotPersisted() {
        manager.updateContext("conv-1", Map.of("selectedText", "selection"));

        verify(runtimeStateService, never()).update(any(), any());
    }

    @Test
    void traceLessSingleFieldWriteIsRejected() {
        assertThatThrownBy(() -> manager.setContextValue("conv-1", "approvalToken", "secret"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Trace-less");
    }

    @Test
    void clearDelegatesToConversationCleanupInterface() {
        manager.clearContext("conv-1");

        verify(runtimeStateService).clearConversation("conv-1");
    }
}
