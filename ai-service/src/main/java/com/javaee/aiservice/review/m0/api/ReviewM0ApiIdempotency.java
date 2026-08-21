package com.javaee.aiservice.review.m0.api;

import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaim;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyClaimStatus;
import com.javaee.aiservice.review.m0.idempotency.IdempotencyScope;
import com.javaee.aiservice.review.m0.idempotency.ReviewM0IdempotencyStore;
import com.javaee.aiservice.review.m0.model.ReviewM0ErrorCode;
import com.javaee.aiservice.review.m0.model.ReviewM0Exception;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Transactional idempotency wrapper for synchronous Review M0 writes. */
public final class ReviewM0ApiIdempotency {
    private final ReviewM0IdempotencyStore store;
    private final TransactionTemplate transactions;

    public ReviewM0ApiIdempotency(
            ReviewM0IdempotencyStore store,
            PlatformTransactionManager transactionManager
    ) {
        this.store = Objects.requireNonNull(store);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public <T> Result<T> execute(
            IdempotencyScope scope,
            String requestDigest,
            int responseStatus,
            String responseType,
            Supplier<Resource<T>> operation,
            Function<String, T> replayLoader
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            IdempotencyClaim claim = store.claim(scope, requestDigest);
            if (claim.status() == IdempotencyClaimStatus.REPLAY_IN_PROGRESS) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                        "The same write is still in progress");
            }
            if (claim.status() == IdempotencyClaimStatus.REPLAY_COMPLETED) {
                if (!responseType.equals(claim.record().responseType())) {
                    throw new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                            "Idempotency key is bound to another resource type");
                }
                T replay = replayLoader.apply(claim.record().responseId());
                return new Result<>(replay, claim.record().responseStatus(), true);
            }
            Resource<T> created = Objects.requireNonNull(operation.get());
            store.complete(scope, requestDigest, responseStatus, responseType, created.resourceId());
            return new Result<>(created.value(), responseStatus, false);
        }));
    }

    public <T> Result<T> executeDynamic(
            IdempotencyScope scope,
            String requestDigest,
            String responseType,
            Supplier<DynamicResource<T>> operation,
            Function<String, T> replayLoader
    ) {
        return Objects.requireNonNull(transactions.execute(status -> {
            IdempotencyClaim claim = store.claim(scope, requestDigest);
            if (claim.status() == IdempotencyClaimStatus.REPLAY_IN_PROGRESS) {
                throw new ReviewM0Exception(
                        ReviewM0ErrorCode.REVIEW_M0_STORAGE_UNAVAILABLE,
                        "The same write is still in progress");
            }
            if (claim.status() == IdempotencyClaimStatus.REPLAY_COMPLETED) {
                if (!responseType.equals(claim.record().responseType())) {
                    throw new ReviewM0Exception(
                            ReviewM0ErrorCode.REVIEW_M0_IDEMPOTENCY_CONFLICT,
                            "Idempotency key is bound to another resource type");
                }
                return new Result<>(replayLoader.apply(claim.record().responseId()),
                        claim.record().responseStatus(), true);
            }
            DynamicResource<T> created = Objects.requireNonNull(operation.get());
            store.complete(scope, requestDigest, created.responseStatus(), responseType, created.resourceId());
            return new Result<>(created.value(), created.responseStatus(), false);
        }));
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
}
