package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.javaee.aiservice.consistency.IndexContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationStoreTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values=mock(ValueOperations.class);
    @SuppressWarnings("unchecked") final SetOperations<String,String> sets=mock(SetOperations.class);
    final VectorStore vectors=mock(VectorStore.class);
    final Map<String,String> strings=new HashMap<>(); final Map<String,Set<String>> memberships=new HashMap<>();
    final GenerationStore store=new GenerationStore(redis,new ObjectMapper(),vectors);
    final Manifest manifest=new Manifest("doc",2,"generation","1","default",hash("text"),"cfg",OffsetSegmenterTest.config("FIXED_LENGTH"),List.of(new StoredSegment(new Segment("ref","generation_seg_0",0,4,hash("text")),"text")));
    @BeforeEach void redisSemantics() {
        when(redis.opsForValue()).thenReturn(values); when(redis.opsForSet()).thenReturn(sets);
        when(values.get(anyString())).thenAnswer(i->strings.get(i.getArgument(0)));
        when(values.setIfAbsent(anyString(),anyString())).thenAnswer(i->strings.putIfAbsent(i.getArgument(0),i.getArgument(1))==null);
        doAnswer(i->{ strings.put(i.getArgument(0),i.getArgument(1)); return null; }).when(values).set(anyString(),anyString());
        when(redis.hasKey(anyString())).thenAnswer(i->strings.containsKey(i.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i->strings.remove(i.getArgument(0))!=null);
        when(sets.add(anyString(),anyString())).thenAnswer(i->memberships.computeIfAbsent(i.getArgument(0),k->new LinkedHashSet<>()).add(i.getArgument(1))?1L:0L);
        when(sets.members(anyString())).thenAnswer(i->memberships.getOrDefault(i.getArgument(0),Set.of()));
    }
    @Test void cleanupBeforeManifestBlocksLateCreate() {
        store.cleanup("generation","doc","1","default"); assertThatThrownBy(()->store.create(manifest)).hasMessage("GENERATION_RETIRED"); assertThat(store.read("generation")).isNull();
    }
    @Test void cleanupRacingCreateRetainsCompleteVectorIds() {
        AtomicBoolean once=new AtomicBoolean();
        when(values.setIfAbsent(eq("index:v1:generation:generation"),anyString())).thenAnswer(i->{
            if(once.compareAndSet(false,true)) store.cleanup("generation","doc","1","default");
            return strings.putIfAbsent(i.getArgument(0),i.getArgument(1))==null;
        });
        assertThatThrownBy(()->store.create(manifest)).hasMessage("GENERATION_RETIRED");
        assertThat(strings.get("index:v1:generation:generation:retired")).contains("generation_seg_0").doesNotContain("\"content\"");
        verify(vectors).deleteById(eq("generation_seg_0"),any());
    }
    @Test void emptyRetirementNeverOverwritesCompleteIdsAndRestartReclaimsLateWrites() {
        store.create(manifest); store.cleanup("generation","doc","1","default"); store.cleanup("generation","doc","1","default");
        new GenerationStore(redis,new ObjectMapper(),vectors).reclaimRetired();
        verify(vectors,atLeast(3)).deleteById(eq("generation_seg_0"),any());
        assertThat(strings.get("index:v1:generation:generation:retired")).contains("generation_seg_0");
    }
    @Test void partialDeletionFailureKeepsManifestAndIdsForRetry() {
        store.create(manifest); doThrow(new RuntimeException()).when(vectors).deleteById(anyString(),any());
        assertThatThrownBy(()->store.cleanup("generation","doc","1","default")).hasMessage("INDEX_CLEANUP_UNAVAILABLE");
        assertThat(store.read("generation")).isNotNull(); assertThat(strings.get("index:v1:generation:generation:retired")).contains("generation_seg_0");
    }
    @Test void wrongTenantCannotRetireExistingManifest() {
        store.create(manifest); assertThatThrownBy(()->store.cleanup("generation","doc","2","default")).hasMessage("INVALID_CLEANUP_SCOPE"); verifyNoInteractions(vectors);
    }
    @Test void restartAbsorbsManifestWrittenAfterEmptyRetirementBeforeWriterCrashed() throws Exception {
        store.cleanup("generation","doc","1","default");
        // Simulate a previously authorized creator dying after SET NX, before its second guard.
        strings.put("index:v1:generation:generation",new ObjectMapper().writeValueAsString(manifest));
        new GenerationStore(redis,new ObjectMapper(),vectors).reclaimRetired();
        assertThat(strings.get("index:v1:generation:generation:retired")).contains("generation_seg_0");
        assertThat(store.read("generation")).isNull();
        verify(vectors).deleteById(eq("generation_seg_0"),any());
        verify(sets,atLeastOnce()).remove(anyString(),eq("generation"));
    }
}
