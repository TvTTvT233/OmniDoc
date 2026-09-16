package com.javaee.aiservice.memory.summary;

import com.javaee.aiservice.agent.ChatService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class BoundedSummaryModelClient implements SummaryModelClient, DisposableBean {

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    private final ChatService chatService;
    private final ExecutorService executor = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16),
            runnable -> {
                Thread thread = new Thread(runnable, "memory-summary-" + THREAD_NUMBER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public BoundedSummaryModelClient(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public String generate(String prompt, String model, Duration timeout) {
        Future<String> future;
        try {
            future = executor.submit(() -> chatService.callChatApiWithModelCode(prompt, model));
        } catch (RejectedExecutionException error) {
            throw new SummaryGenerationException("Structured Summary model queue is full", error);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new SummaryGenerationException("Structured Summary model call timed out", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new SummaryGenerationException("Structured Summary model call was interrupted", error);
        } catch (Exception error) {
            throw new SummaryGenerationException("Structured Summary model call failed", error);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
