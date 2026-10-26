package com.javaee.aiservice.consistency;

import com.javaee.aiservice.rag.*;
import com.javaee.aiservice.rag.vector.contract.VectorRecord;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.javaee.aiservice.consistency.IndexContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndexGenerationWorkerTest {
    final IndexAuthorityClient authority=mock(IndexAuthorityClient.class);
    final GenerationStore store=mock(GenerationStore.class);
    final DocumentVectorizer embedding=mock(DocumentVectorizer.class);
    final VectorStore vectors=mock(VectorStore.class);
    final OffsetSegmenter splitter=new OffsetSegmenter(embedding);
    final Task task=OffsetSegmenterTest.task("真实快照😀内容。".repeat(60),"FIXED_LENGTH");
    final IndexGenerationWorker worker=new IndexGenerationWorker(authority,store,splitter,embedding,vectors,true);
    void configured() { when(embedding.modelName()).thenReturn("synthetic-embedding-v1"); when(embedding.configuredDimension()).thenReturn(16); when(embedding.vectorize(anyString())).thenReturn(new float[16]); when(authority.complete(eq(task),anyList())).thenReturn(true); }
    @Test void completeOnlyAfterEveryVectorAndManifest() {
        configured(); worker.run(task);
        var order=inOrder(store,vectors,authority); order.verify(store).create(any());
        order.verify(vectors,times(splitter.segment(task).size())).upsert(any(VectorRecord.class));
        order.verify(authority).complete(eq(task),argThat(list->list.size()>1)); verify(authority,never()).fail(any(),anyString(),anyBoolean());
    }
    @Test void disabledEmbeddingNeverWritesOrPublishes() {
        doThrow(new IndexConsistencyException("EMBEDDING_DISABLED")).when(embedding).assertAvailable(); worker.run(task);
        verifyNoInteractions(store,vectors); verify(authority).fail(task,"EMBEDDING_DISABLED",true); verify(authority,never()).complete(any(),anyList());
    }
    @Test void partialVectorFailureIsNotPublishedOrDestructivelyRolledBack() {
        configured(); doNothing().doThrow(new RuntimeException("injected")).when(vectors).upsert(any()); worker.run(task);
        verify(authority).fail(task,"INDEX_BUILD_FAILED",true); verify(authority,never()).complete(any(),anyList()); verify(store,never()).cleanup(anyString(),anyString(),anyString(),anyString());
    }
    @Test void lostLeaseAfterEmbeddingPreventsLateVectorWrite() {
        configured(); doNothing().doNothing().doNothing().doThrow(new IndexConsistencyException("INDEX_LEASE_LOST")).when(authority).renew(task);
        worker.run(task); verifyNoInteractions(vectors); verify(authority,never()).complete(any(),anyList());
    }
    @Test void retirementDuringWriteImmediatelyDeletesLateVector() {
        configured(); doNothing().doThrow(new IndexConsistencyException("GENERATION_RETIRED")).when(store).assertWritable(task.generationId());
        worker.run(task); verify(vectors).upsert(any()); verify(vectors).deleteById(anyString(),any()); verify(authority,never()).complete(any(),anyList());
    }
    @Test void cleanupNullGenerationCompletesWithoutEmbedding() {
        Task cleanup=new Task("cleanup","lease","","CLEANUP","doc",2,null,"1","default",null,null,null,null,null,null,List.of("old"));
        worker.run(cleanup); verify(store).cleanup("old","doc","1","default"); verify(authority).complete(cleanup,List.of()); verifyNoInteractions(embedding,vectors);
    }
    @Test void cleanupStorageOutageDoesNotStarveClaims() {
        doThrow(new RuntimeException()).when(store).reclaimRetired(); worker.poll(); verify(authority).claim(anyString());
    }
}
