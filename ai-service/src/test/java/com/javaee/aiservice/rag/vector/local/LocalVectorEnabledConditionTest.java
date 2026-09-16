package com.javaee.aiservice.rag.vector.local;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalVectorEnabledConditionTest {

    @Test
    @SuppressWarnings("unchecked")
    void qdrantModeDoesNotConstructTheRedisHnswBackend() {
        new ApplicationContextRunner()
                .withUserConfiguration(LocalRedisHnswVectorBackend.class)
                .withBean(RedisTemplate.class, () -> mock(RedisTemplate.class))
                .withPropertyValues("ai.vector.mode=qdrant")
                .run(context ->
                        assertThat(context).doesNotHaveBean(LocalRedisHnswVectorBackend.class));
    }
}
