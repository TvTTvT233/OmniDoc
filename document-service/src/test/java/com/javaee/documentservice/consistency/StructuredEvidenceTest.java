package com.javaee.documentservice.consistency;

import com.javaee.documentservice.dto.DocumentCreateDTO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Inherits real transaction/mapper fixture, not a replacement persistence implementation. */
class StructuredEvidenceTest extends DocumentConsistencyTransactionTest {
    static byte[] docx() throws Exception {
        try(var doc=new XWPFDocument();var bytes=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("重复中文😀\r\n正文");
            var table=doc.createTable(1,2);table.getRow(0).getCell(0).setText("姓名");table.getRow(0).getCell(1).setText("合成学生😀");
            doc.createParagraph().createRun().setText("重复中文😀\r\n正文");doc.write(bytes);return bytes.toByteArray();
        }
    }
    @Test void docxRetainsBodyOrderAndCanonicalOffsetsWithoutInventingPages() throws Exception {
        var parsed=StructuredDocument.parse(docx(),"synthetic.docx");
        assertThat(parsed.blocks()).extracting(StructuredDocument.Block::kind).containsExactly("DOCX_PARAGRAPH","DOCX_CELL","DOCX_CELL","DOCX_PARAGRAPH");
        assertThat(parsed.blocks()).allMatch(b->b.page()==null);
        assertThat(parsed.text()).contains("重复中文😀\n正文");
        for(var b:parsed.blocks()) assertThat(CanonicalDocumentText.hash(parsed.text().substring(b.startOffset(),b.endOffset()))).isEqualTo(b.textHash());
        assertThat(parsed.blocks().get(2).column()).isEqualTo(2);
        assertThat(parsed.blocks().get(3).startOffset()).isGreaterThan(parsed.blocks().get(0).endOffset());
    }
    @Test void pdfPagesAreRealAndBlankPdfRequiresManualReview() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            for(String value:List.of("Page one","Page two")) {
                var page=new PDPage();doc.addPage(page);
                try(var stream=new PDPageContentStream(doc,page)) {stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(30,700);stream.showText(value);stream.endText();}
            }
            doc.save(bytes);var parsed=StructuredDocument.parse(bytes.toByteArray(),"test.pdf");
            assertThat(parsed.blocks()).extracting(StructuredDocument.Block::page).containsExactly(1,2);
            assertThat(parsed.text().substring(parsed.blocks().get(1).startOffset())).contains("Page two");
        }
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());doc.save(bytes);
            assertThatThrownBy(()->StructuredDocument.parse(bytes.toByteArray(),"scan.pdf")).isInstanceOf(IOException.class);
        }
    }
    @Test void createReferenceEditRestoreAndOwnerIsolationPreserveVersionBoundEvidence() throws Exception {
        when(files.getFileName("synthetic")).thenReturn(ResponseEntity.ok("test.docx"));
        when(files.downloadFile("synthetic")).thenReturn(ResponseEntity.ok(docx()));
        var dto=new DocumentCreateDTO();dto.setTitle("Synthetic structured");dto.setFileId("synthetic");
        var doc=documents.create(dto,7L);var job=claim();var complete=completion(job);
        index.complete((String)job.get("taskId"),complete);
        String reference=complete.segments().get(0).referenceId();
        assertThat((List<?>)index.resolve(doc.getId(),reference,7L).get("sourceLocations")).hasSize(4);
        assertThat(index.snapshot(doc.getId(),1).getParserVersion()).isEqualTo(StructuredDocument.VERSION);
        save(doc.getId(),1,"edited without original layout");
        assertThat(index.structure(doc.getId(),2,7L).get("status")).isEqualTo("UNAVAILABLE");
        assertThat(index.resolve(doc.getId(),reference,7L).get("status")).isEqualTo("HISTORICAL");
        documents.restoreVersion(doc.getId(),1,2,7L);
        assertThat((List<?>)index.structure(doc.getId(),3,7L).get("blocks")).hasSize(4);
        assertThatThrownBy(()->index.structure(doc.getId(),1,8L)).isInstanceOf(DocumentStateException.class);
        assertThatThrownBy(()->index.resolve(doc.getId(),reference,8L)).isInstanceOf(DocumentStateException.class);
        jdbc.update("UPDATE document_structure SET blocks_json='[]' WHERE document_id=? AND document_version=1",doc.getId());
        assertThatThrownBy(()->index.resolve(doc.getId(),reference,7L)).isInstanceOf(DocumentStateException.class);
        assertThatThrownBy(()->documents.restoreVersion(doc.getId(),1,3,7L)).isInstanceOf(DocumentStateException.class);
        assertThat(mapper.selectById(doc.getId()).getVersion()).isEqualTo(3);
    }
    @Test void mismatchedSourceAndSurrogateBoundaryCannotAttach() {
        var d=create();save(d.getId(),1,"A😀B");var current=mapper.selectById(d.getId());
        assertThatThrownBy(()->index.attachStructure(current,new StructuredDocument("wrong","0".repeat(64),List.of()))).isInstanceOf(DocumentStateException.class);
        var block=new StructuredDocument.Block("PDF_PAGE",1,null,null,null,null,2,4,CanonicalDocumentText.hash("x"));
        assertThatThrownBy(()->index.attachStructure(current,new StructuredDocument("A😀B","0".repeat(64),List.of(block)))).isInstanceOf(DocumentStateException.class);
    }
}
