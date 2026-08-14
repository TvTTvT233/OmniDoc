package com.javaee.aiservice.review.m0.job;

import com.javaee.aiservice.review.m0.model.ReviewJobType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ReviewM0JobHandlerRegistry {
    private final Map<ReviewJobType, ReviewM0JobHandler> handlers;

    public ReviewM0JobHandlerRegistry(List<ReviewM0JobHandler> registeredHandlers) {
        Map<ReviewJobType, ReviewM0JobHandler> mapped = new EnumMap<>(ReviewJobType.class);
        for (ReviewM0JobHandler handler : registeredHandlers) {
            ReviewM0JobHandler duplicate = mapped.putIfAbsent(handler.jobType(), handler);
            if (duplicate != null) {
                throw new IllegalStateException("Multiple Review M0 handlers registered for " + handler.jobType());
            }
        }
        handlers = Map.copyOf(mapped);
    }

    public Optional<ReviewM0JobHandler> find(ReviewJobType jobType) {
        return Optional.ofNullable(handlers.get(jobType));
    }
}
