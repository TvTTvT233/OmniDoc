package com.javaee.aiservice.consistency;

import com.javaee.aiservice.rag.DocumentVectorizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.javaee.aiservice.consistency.IndexContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OffsetSegmenterTest {
    static Config config(String strategy) { return new Config("canonical-v1", "offset-v1", strategy, 180, 30, "synthetic-embedding-v1", 16, 1); }
    static Task task(String text, String strategy) { return new Task("task", "lease", "", "BUILD", "doc", 2, "generation", "1", "default", hash(text), "cfg", text, "file", "title", config(strategy), List.of()); }
    @ParameterizedTest @ValueSource(strings={"FIXED_LENGTH","CHAPTER","SEMANTIC","HYBRID","AUTO"})
    void everyStrategyKeepsExactRepeatedUnicodeParagraphRanges(String strategy) {
        DocumentVectorizer vectorizer = mock(DocumentVectorizer.class); when(vectorizer.vectorize(anyString())).thenReturn(new float[]{1,0});
        String paragraph = "重复句子😀保留空白。\n\n  第二段中文与重复句子😀。  \n";
        String text = "# 第一章\n" + paragraph.repeat(12) + "# 第二章\n" + paragraph.repeat(12);
        List<StoredSegment> segments = new OffsetSegmenter(vectorizer).segment(task(text, strategy));
        assertThat(segments).isNotEmpty();
        for (StoredSegment segment : segments) {
            assertThat(text.substring(segment.source().startOffset(), segment.source().endOffset())).isEqualTo(segment.content());
            assertThat(hash(segment.content())).isEqualTo(segment.source().segmentHash());
            assertThat(Character.isLowSurrogate(text.charAt(segment.source().startOffset()))).isFalse();
            if (segment.source().endOffset()<text.length()) assertThat(Character.isLowSurrogate(text.charAt(segment.source().endOffset()))).isFalse();
        }
        assertThat(segments.stream().map(s -> s.source().referenceId())).doesNotHaveDuplicates();
    }
    @Test void rejectsUnverifiedSnapshotBeforeEmbedding() {
        DocumentVectorizer vectorizer=mock(DocumentVectorizer.class); Task t=task("hello","FIXED_LENGTH");
        Task corrupt=new Task(t.taskId(),t.leaseToken(),"",t.operation(),t.documentId(),2,t.generationId(),"1","default","bad","cfg",t.canonicalText(),"file","title",t.config(),List.of());
        assertThatThrownBy(()->new OffsetSegmenter(vectorizer).segment(corrupt)).hasMessage("SNAPSHOT_HASH_MISMATCH"); verifyNoInteractions(vectorizer);
    }
}
