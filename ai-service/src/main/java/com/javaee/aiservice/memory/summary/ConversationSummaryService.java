package com.javaee.aiservice.memory.summary;

import com.javaee.aiservice.conversation.config.MemoryPersistenceProperties;
import com.javaee.aiservice.conversation.model.ConversationMessage;
import com.javaee.aiservice.conversation.model.ConversationMessageType;
import com.javaee.aiservice.conversation.service.ConversationService;
import com.javaee.aiservice.memory.context.ConversationSummaryProvider;
import com.javaee.aiservice.memory.context.MemorySummaryView;
import com.javaee.aiservice.memory.context.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Primary
@Service
public final class ConversationSummaryService implements ConversationSummaryProvider, ConversationSummaryUpdater {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);
    private static final int MESSAGE_PAGE_SIZE = 200;

    private final ConversationService conversationService;
    private final ConversationSummaryStore store;
    private final StructuredSummaryCodec codec;
    private final StructuredSummaryValidator validator;
    private final StructuredSummaryRenderer renderer;
    private final SummaryPromptBuilder promptBuilder;
    private final SummaryModelClient modelClient;
    private final SummaryProperties properties;
    private final MemoryPersistenceProperties persistenceProperties;
    private final TokenEstimator tokenEstimator;

    public ConversationSummaryService(
            ConversationService conversationService,
            ConversationSummaryStore store,
            StructuredSummaryCodec codec,
            StructuredSummaryValidator validator,
            StructuredSummaryRenderer renderer,
            SummaryPromptBuilder promptBuilder,
            SummaryModelClient modelClient,
            SummaryProperties properties,
            MemoryPersistenceProperties persistenceProperties,
            TokenEstimator tokenEstimator
    ) {
        this.conversationService = conversationService;
        this.store = store;
        this.codec = codec;
        this.validator = validator;
        this.renderer = renderer;
        this.promptBuilder = promptBuilder;
        this.modelClient = modelClient;
        this.properties = properties;
        this.persistenceProperties = persistenceProperties;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public Optional<MemorySummaryView> findOwned(String conversationId, String userId) {
        if (!persistenceProperties.isEnabled() || !properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            return store.findOwned(conversationId, userId).flatMap(stored -> {
                try {
                    StructuredConversationSummary summary = parseStoredEnvelope(stored);
                    return Optional.of(new MemorySummaryView(summary.coverage().toSeq(), renderer.render(summary)));
                } catch (RuntimeException error) {
                    log.warn("Ignoring an invalid stored Conversation Summary: conversationId={}, version={}, errorType={}",
                            conversationId, stored.summaryVersion(), error.getClass().getSimpleName());
                    return Optional.empty();
                }
            });
        } catch (RuntimeException error) {
            log.warn("Conversation Summary read degraded to raw history: conversationId={}, errorType={}",
                    conversationId, error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public SummaryUpdateResult maybeUpdate(String conversationId, String userId, String requestModel) {
        if (!persistenceProperties.isEnabled() || !properties.isEnabled()) {
            return result(SummaryUpdateStatus.DISABLED, 0, 0, 0, 0, 0, 0);
        }
        StoredConversationSummary stored = null;
        StructuredConversationSummary previous = null;
        long previousVersion = 0;
        long previousCoverage = 0;
        try {
            conversationService.requireOwned(conversationId, userId);
            stored = store.findOwned(conversationId, userId).orElse(null);
            if (stored != null) {
                previous = parseStoredEnvelope(stored);
                previousVersion = previous.summaryVersion();
                previousCoverage = previous.coverage().toSeq();
                List<ConversationMessage> oldCoverage = loadRange(conversationId, userId, 0, previousCoverage);
                String oldDigest = SummarySourceDigest.compute(conversationId, 1, previousCoverage, oldCoverage);
                if (!oldDigest.equals(previous.coverage().sourceDigest())) {
                    throw new SummaryValidationException("Stored Summary sourceDigest no longer matches its source messages");
                }
            }

            List<ConversationMessage> uncompressed = loadAfter(conversationId, userId, previousCoverage);
            int totalTokens = estimate(uncompressed);
            if (totalTokens < properties.getTriggerInputTokens()) {
                return result(SummaryUpdateStatus.BELOW_THRESHOLD, previousVersion, previousVersion,
                        previousCoverage, 0, totalTokens, 0);
            }
            List<ConversationMessage> delta = selectPrefix(uncompressed, totalTokens);
            if (delta.isEmpty()) {
                return result(SummaryUpdateStatus.SOURCE_TOO_LARGE, previousVersion, previousVersion,
                        previousCoverage, 0, totalTokens, 0);
            }

            long toSequence = delta.get(delta.size() - 1).sequence();
            List<ConversationMessage> covered = loadRange(conversationId, userId, 0, toSequence);
            String digest = SummarySourceDigest.compute(conversationId, 1, toSequence, covered);
            long targetVersion = previousVersion + 1;
            // The timestamp is part of the model contract and must survive JSON round-trips exactly.
            Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String prompt = promptBuilder.build(previous, delta, conversationId, targetVersion,
                    previousVersion, toSequence, digest, generatedAt);
            String model = properties.getModel() == null || properties.getModel().isBlank()
                    ? requestModel
                    : properties.getModel();
            for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
                long startedAt = System.currentTimeMillis();
                try {
                    String raw = modelClient.generate(prompt, model, properties.getTimeout());
                    StructuredConversationSummary candidate = codec.parse(raw);
                    validator.validate(candidate, new SummaryValidationContext(
                            conversationId, targetVersion, previousVersion, 1, toSequence,
                            digest, generatedAt, covered, previous));
                    Instant now = Instant.now();
                    StoredConversationSummary next = new StoredConversationSummary(
                            conversationId, "1.0", targetVersion, 1, toSequence, digest,
                            codec.write(candidate), candidate.meta().generatedAt(), now);
                    SummaryWriteResult write = store.saveCas(next, previousVersion);
                    SummaryUpdateStatus status = switch (write) {
                        case STORED -> SummaryUpdateStatus.UPDATED;
                        case IDEMPOTENT -> SummaryUpdateStatus.IDEMPOTENT;
                        case CONFLICT -> SummaryUpdateStatus.CONFLICT;
                    };
                    log.info("Conversation Summary update finished: conversationId={}, status={}, previousVersion={}, targetVersion={}, coverageTo={}, sourceMessages={}, sourceTokens={}, attempt={}, elapsedMs={}",
                            conversationId, status, previousVersion, targetVersion, toSequence,
                            delta.size(), estimate(delta), attempt, System.currentTimeMillis() - startedAt);
                    return result(status, previousVersion,
                            status == SummaryUpdateStatus.CONFLICT ? previousVersion : targetVersion,
                            status == SummaryUpdateStatus.CONFLICT ? previousCoverage : toSequence,
                            delta.size(), estimate(delta), attempt);
                } catch (RuntimeException error) {
                    String validationReason = error instanceof SummaryValidationException
                            ? error.getMessage()
                            : "not-applicable";
                    log.warn("Conversation Summary attempt failed: conversationId={}, previousVersion={}, targetVersion={}, attempt={}, errorType={}, validationReason={}, elapsedMs={}",
                            conversationId, previousVersion, targetVersion, attempt,
                            error.getClass().getSimpleName(), validationReason,
                            System.currentTimeMillis() - startedAt);
                }
            }
            return result(SummaryUpdateStatus.FAILED, previousVersion, previousVersion,
                    previousCoverage, delta.size(), estimate(delta), properties.getMaxAttempts());
        } catch (RuntimeException error) {
            log.warn("Conversation Summary update skipped safely: conversationId={}, previousVersion={}, errorType={}",
                    conversationId, previousVersion, error.getClass().getSimpleName());
            return result(SummaryUpdateStatus.FAILED, previousVersion, previousVersion,
                    previousCoverage, 0, 0, 0);
        }
    }

    private StructuredConversationSummary parseStoredEnvelope(StoredConversationSummary stored) {
        StructuredConversationSummary summary = codec.parse(stored.summaryJson());
        if (!stored.schemaVersion().equals(summary.schemaVersion())
                || !stored.conversationId().equals(summary.conversationId())
                || stored.summaryVersion() != summary.summaryVersion()
                || summary.coverage() == null
                || stored.fromSequence() != summary.coverage().fromSeq()
                || stored.toSequence() != summary.coverage().toSeq()
                || !stored.sourceDigest().equals(summary.coverage().sourceDigest())) {
            throw new SummaryValidationException("Stored Summary envelope does not match its JSON");
        }
        return summary;
    }

    private List<ConversationMessage> loadAfter(String conversationId, String userId, long afterSequence) {
        List<ConversationMessage> result = new ArrayList<>();
        long cursor = afterSequence;
        while (true) {
            List<ConversationMessage> page = conversationService.listMessagesAfter(
                    conversationId, userId, cursor, MESSAGE_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            result.addAll(page);
            cursor = page.get(page.size() - 1).sequence();
            if (page.size() < MESSAGE_PAGE_SIZE) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private List<ConversationMessage> loadRange(
            String conversationId,
            String userId,
            long afterSequence,
            long throughSequence
    ) {
        return loadAfter(conversationId, userId, afterSequence).stream()
                .takeWhile(message -> message.sequence() <= throughSequence)
                .toList();
    }

    private List<ConversationMessage> selectPrefix(List<ConversationMessage> messages, int totalTokens) {
        int target = Math.min(properties.getMaxSourceTokens(),
                Math.max(0, totalTokens - properties.getRetainRecentTokens()));
        if (target <= 0) {
            return List.of();
        }
        List<ConversationMessage> selected = new ArrayList<>();
        int used = 0;
        for (ConversationMessage message : messages) {
            int next = estimate(message);
            if (used + next > target) {
                break;
            }
            selected.add(message);
            used += next;
        }
        if (!selected.isEmpty()
                && selected.get(selected.size() - 1).messageType() == ConversationMessageType.TOOL_CALL
                && selected.size() < messages.size()) {
            ConversationMessage call = selected.get(selected.size() - 1);
            ConversationMessage possibleResult = messages.get(selected.size());
            if (possibleResult.messageType() == ConversationMessageType.TOOL_RESULT
                    && java.util.Objects.equals(call.toolCallId(), possibleResult.toolCallId())) {
                if (used + estimate(possibleResult) <= target) {
                    selected.add(possibleResult);
                } else {
                    selected.remove(selected.size() - 1);
                }
            }
        }
        return List.copyOf(selected);
    }

    private int estimate(List<ConversationMessage> messages) {
        return messages.stream().mapToInt(this::estimate).sum();
    }

    private int estimate(ConversationMessage message) {
        return tokenEstimator.estimate(message.content()) + 8;
    }

    private static SummaryUpdateResult result(
            SummaryUpdateStatus status,
            long previousVersion,
            long resultingVersion,
            long coverage,
            int count,
            int tokens,
            int attempts
    ) {
        return new SummaryUpdateResult(status, previousVersion, resultingVersion, coverage, count, tokens, attempts);
    }
}
