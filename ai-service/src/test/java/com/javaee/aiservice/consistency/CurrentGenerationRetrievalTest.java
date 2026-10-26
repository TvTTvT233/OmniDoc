package com.javaee.aiservice.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.rag.*;
import com.javaee.aiservice.rag.vector.contract.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.javaee.aiservice.consistency.IndexContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentGenerationRetrievalTest {
    final GenerationStore store=mock(GenerationStore.class);
    final IndexAuthorityClient authority=mock(IndexAuthorityClient.class);
    final DocumentVectorizer embedding=mock(DocumentVectorizer.class);
    final VectorStore vectors=mock(VectorStore.class);
    final Reranker reranker=mock(Reranker.class);
    final CurrentGenerationRetrieval retrieval=new CurrentGenerationRetrieval(store,authority,embedding,vectors,reranker,new ObjectMapper());
    final Task task=OffsetSegmenterTest.task("文档当前版本😀知识", "FIXED_LENGTH");
    final Segment segment=new Segment("reference","generation_seg_0",0,task.canonicalText().length(),hash(task.canonicalText()));
    final Manifest manifest=new Manifest("doc",2,"generation","1","default",task.contentHash(),"cfg",task.config(),List.of(new StoredSegment(segment,task.canonicalText())));
    final Reference ref=new Reference("reference","doc",2,"title","generation_seg_0","generation",task.contentHash(),0,task.canonicalText().length(),segment.segmentHash());
    final VisibleDocument doc=new VisibleDocument("doc",2,"generation","1","default",task.contentHash(),"cfg","title","file",List.of(ref));
    void configured() {
        when(store.candidates("1","default")).thenReturn(List.of(manifest)); when(authority.visibility(eq("1"),eq("default"),anyList())).thenReturn(List.of(doc));
        when(embedding.vectorize(anyString())).thenReturn(new float[16]); when(embedding.modelName()).thenReturn("synthetic-embedding-v1");
        when(vectors.search(any(VectorQuery.class))).thenReturn(List.of(new VectorMatch(segment.segmentId(),"doc",.9f,Map.of())));
    }
    @Test void queryExplicitlyFiltersCurrentGenerationSoOldVectorsCannotCrowdTopK() {
        configured(); var rows=retrieval.search("知识",2,Map.of("userId","1","knowledgeBaseId","default"),false,null);
        assertThat(rows).hasSize(1); assertThat(rows.get(0).get("reference")).isEqualTo(ref);
        verify(vectors).search(argThat(q->q.filter().attributes().get("generationId").equals("generation")&&q.filter().documentId().equals("doc")));
    }
    @Test void savedOrDeletedDocumentNeverReachesEmbeddingOrReranker() {
        configured(); when(authority.visibility(anyString(),anyString(),anyList())).thenReturn(List.of());
        assertThatThrownBy(()->retrieval.search("知识",2,Map.of("userId","1","knowledgeBaseId","default"),true,Reranker.RerankStrategy.HYBRID)).hasMessage("INDEX_NOT_READY");
        verifyNoInteractions(embedding,vectors,reranker);
    }
    @Test void dependencyFailureCannotBecomeSuccessfulEmptyResults() {
        configured(); when(authority.visibility(anyString(),anyString(),anyList())).thenThrow(new IndexConsistencyException("INDEX_AUTHORITY_UNAVAILABLE"));
        assertThatThrownBy(()->retrieval.search("x",2,Map.of("userId","1","knowledgeBaseId","default"),true,null)).hasMessage("INDEX_AUTHORITY_UNAVAILABLE");
    }
    @Test void unregisteredOrForgedVectorHitDoesNotExposeText() {
        configured(); when(vectors.search(any(VectorQuery.class))).thenReturn(List.of(new VectorMatch("foreign","doc",1,Map.of())));
        assertThatThrownBy(()->retrieval.search("x",2,Map.of("userId","1","knowledgeBaseId","default"),false,null)).hasMessage("INDEX_SEGMENT_MISMATCH");
    }
    @Test void changesAfterRetrievalInvalidateReferences() {
        configured(); when(authority.visibility(anyString(),anyString(),anyList())).thenReturn(List.of(doc),List.of());
        assertThatThrownBy(()->retrieval.search("x",2,Map.of("userId","1","knowledgeBaseId","default"),false,null)).hasMessage("INDEX_NOT_READY");
    }
    @Test void fabricatedCitationFieldsAreNotTrusted() {
        configured(); Reference forged=new Reference(ref.referenceId(),ref.documentId(),999,ref.documentTitle(),ref.segmentId(),ref.generationId(),ref.contentHash(),0,1,ref.segmentHash());
        assertThat(retrieval.validateReferences(List.of(forged),"1","default")).isEmpty();
    }
    @Test void unscopedLegacySearchIsRejected() { assertThatThrownBy(()->retrieval.search("x",2,Map.of(),false,null)).hasMessage("INVALID_INDEX_SCOPE"); verifyNoInteractions(store,authority,embedding); }
    @Test void manifestContentTamperingIsDetectedBeforeRanking() {
        configured(); when(store.candidates("1","default")).thenReturn(List.of(new Manifest("doc",2,"generation","1","default",task.contentHash(),"cfg",task.config(),List.of(new StoredSegment(segment,"tampered")))));
        assertThatThrownBy(()->retrieval.search("x",2,Map.of("userId","1","knowledgeBaseId","default"),true,Reranker.RerankStrategy.HYBRID)).hasMessage("INDEX_SEGMENT_MISMATCH"); verifyNoInteractions(reranker);
    }
}
