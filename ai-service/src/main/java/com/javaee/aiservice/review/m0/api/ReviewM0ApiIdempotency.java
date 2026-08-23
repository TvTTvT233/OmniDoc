package com.javaee.aiservice.review.m0.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaim;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaimStatus;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyResponseSnapshot;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.model.IdempotencyState;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Transactional idempotency wrapper for synchronous Review M0 writes. */
public final class ReviewM0ApiIdempotency {
    private final ReviewM0IdempotencyStore store;
    private final TransactionTemplate transactions;
    private final ObjectMapper responseJson;

    private static final Set<String> FORBIDDEN_SNAPSHOT_FIELDS = Set.of(
            "objectkey", "presignedurl", "presigneduri", "credentials", "credential",
            "password", "secret", "token", "accesstoken", "refreshtoken", "authorization",
            "cookie", "setcookie", "apikey", "privatekey", "bucketname", "stacktrace",
            "filebytes", "documentcontent", "ocrtext");

    public ReviewM0ApiIdempotency(
            ReviewM0IdempotencyStore store,
            PlatformTransactionManager transactionManager
    ) {
        this.store = Objects.requireNonNull(store);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.responseJson = new ObjectMapper().findAndRegisterModules();
    }

    public <T> Result<T> execute(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            Class<T> responseClass,
            Supplier<Resource<T>> operation
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            IdempotencyClaim claim = store.claim(scope, requestDigest);
            if (claim.status() == IdempotencyClaimStatus.REPLAY_IN_PROGRESS) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                        "The same write is still in progress");
            }
            if (claim.status() == IdempotencyClaimStatus.REPLAY_COMPLETED) {
                T replay = replay(claim, responseType, responseClass);
                return new Result<>(replay, claim.record().responseStatus(), true);
            }
            Resource<T> created = Objects.requireNonNull(operation.get());
            IdempotencyResponseSnapshot snapshot = snapshot(
                    responseStatus, responseType, created.resourceId(), created.value());
            store.completeWithSnapshot(
                    scope, requestDigest, responseStatus, responseType, created.resourceId(), snapshot);
            return new Result<>(created.value(), responseStatus, false);
        }));
    }

    public <T> Result<T> executeDynamic(
            IdempotencyScope scope,
            String requestDigest,
            String responseType,
            Class<T> responseClass,
            Supplier<DynamicResource<T>> operation
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            IdempotencyClaim claim = store.claim(scope, requestDigest);
            if (claim.status() == IdempotencyClaimStatus.REPLAY_IN_PROGRESS) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                        "The same write is still in progress");
            }
            if (claim.status() == IdempotencyClaimStatus.REPLAY_COMPLETED) {
                return new Result<>(replay(claim, responseType, responseClass),
                        claim.record().responseStatus(), true);
            }
            DynamicResource<T> created = Objects.requireNonNull(operation.get());
            IdempotencyResponseSnapshot snapshot = snapshot(
                    created.responseStatus(), responseType, created.resourceId(), created.value());
            store.completeWithSnapshot(
                    scope, requestDigest, created.responseStatus(), responseType,
                    created.resourceId(), snapshot);
            return new Result<>(created.value(), created.responseStatus(), false);
        }));
    }

    /**
     * Wraps an existing transactional submitter that owns the initial claim/completion.
     * The outer transaction lets the API append the first HTTP body before that business write commits.
     */
    public <T> Result<T> executeDelegatedCompletion(
            IdempotencyScope scope,
            String requestDigest,
            String responseType,
            Class<T> responseClass,
            Supplier<DelegatedResource<T>> operation
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var before = store.find(scope);
            if (before.isPresent()) {
                requireSameRequest(before.get().requestDigest(), requestDigest);
                if (before.get().state() == IdempotencyState.COMPLETED) {
                    return new Result<>(replay(
                            new IdempotencyClaim(IdempotencyClaimStatus.REPLAY_COMPLETED, before.get()),
                            responseType, responseClass), before.get().responseStatus(), true);
                }
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                        "The same write is still in progress");
            }

            DelegatedResource<T> created = Objects.requireNonNull(operation.get());
            if (created.replayed()) {
                var completed = store.find(scope).orElseThrow(() ->
                        IdempotencyResponseSnapshot.unavailable(
                                "Completed idempotency record is unavailable", null));
                requireSameRequest(completed.requestDigest(), requestDigest);
                return new Result<>(replay(
                        new IdempotencyClaim(IdempotencyClaimStatus.REPLAY_COMPLETED, completed),
                        responseType, responseClass), completed.responseStatus(), true);
            }
            IdempotencyResponseSnapshot snapshot = snapshot(
                    created.responseStatus(), responseType, created.resourceId(), created.value());
            store.attachSnapshotToCompletedInCallerTransaction(
                    scope, requestDigest, created.responseStatus(), responseType,
                    created.resourceId(), snapshot);
            return new Result<>(created.value(), created.responseStatus(), false);
        }));
    }

    private <T> T replay(IdempotencyClaim claim, String responseType, Class<T> responseClass) {
        if (!responseType.equals(claim.record().responseType())) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                    "Idempotency key is bound to another resource type");
        }
        IdempotencyResponseSnapshot snapshot = claim.record().responseSnapshot();
        if (snapshot == null) {
            throw IdempotencyResponseSnapshot.unavailable(
                    "Completed idempotency record has no response snapshot", null);
        }
        String body = snapshot.requireValid(
                claim.record().responseStatus(), claim.record().responseType(),
                claim.record().responseId());
        try {
            return responseJson.readValue(body, responseClass);
        } catch (Exception error) {
            throw IdempotencyResponseSnapshot.unavailable(
                    "Idempotency response snapshot cannot be decoded", error);
        }
    }

    private static void requireSameRequest(String existingDigest, String requestDigest) {
        if (!Objects.equals(existingDigest, requestDigest)) {
            throw new ReviewM0Exception(
                    ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                    "Idempotency key was reused with a different canonical payload");
        }
    }

    private IdempotencyResponseSnapshot snapshot(
            int responseStatus,
            String responseType,
            String responseId,
            Object responseBody
    ) {
        try {
            JsonNode tree = responseJson.valueToTree(responseBody);
            requireSafePublicBody(tree);
            return IdempotencyResponseSnapshot.capture(
                    responseStatus, responseType, responseId,
                    responseJson.writeValueAsString(responseBody));
        } catch (ReviewM0Exception error) {
            throw error;
        } catch (Exception error) {
            throw IdempotencyResponseSnapshot.unavailable(
                    "Idempotency response snapshot cannot be serialized", error);
        }
    }

    private static void requireSafePublicBody(JsonNode node) {
        if (node == null) {
            throw IdempotencyResponseSnapshot.unavailable(
                    "Idempotency response snapshot body is unavailable", null);
        }
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (FORBIDDEN_SNAPSHOT_FIELDS.contains(field.toLowerCase(Locale.ROOT))) {
                    throw IdempotencyResponseSnapshot.unavailable(
                            "Idempotency response contains a forbidden snapshot field", null);
                }
                requireSafePublicBody(node.get(field));
            }
        } else if (node.isArray()) {
            node.forEach(ReviewM0ApiIdempotency::requireSafePublicBody);
        }
    }

    public record Resource<T>(String resourceId, T value) {
        public Resource {
            if (resourceId == null || resourceId.isBlank() || resourceId.length() > 64 || value == null) {
                throw new IllegalArgumentException("Idempotent resource reference is invalid");
            }
        }
    }

    public record Result<T>(T value, int status, boolean replayed) {}

    public record DynamicResource<T>(String resourceId, T value, int responseStatus) {
        public DynamicResource {
            if (resourceId == null || resourceId.isBlank() || resourceId.length() > 64
                    || value == null || responseStatus < 200 || responseStatus > 299) {
                throw new IllegalArgumentException("Idempotent dynamic resource reference is invalid");
            }
        }
    }

    public record DelegatedResource<T>(
            String resourceId,
            T value,
            int responseStatus,
            boolean replayed
    ) {
        public DelegatedResource {
            if (resourceId == null || resourceId.isBlank() || resourceId.length() > 64
                    || value == null || responseStatus < 200 || responseStatus > 299) {
                throw new IllegalArgumentException("Delegated idempotent resource is invalid");
            }
        }
    }
}
