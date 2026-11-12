package com.javaee.documentservice.consistency;

import com.javaee.documentservice.util.DocumentParserUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import java.io.*;
import java.util.*;

/** Coordinates address the exact canonical text, never guessed PDF geometry or Word pages. */
public record StructuredDocument(String text, String sourceHash, List<Block> blocks) {
    public static final String VERSION = "structured-parser-v1";
    public record Block(String kind, Integer page, Integer paragraph, Integer table, Integer row,
                        Integer column, int startOffset, int endOffset, String textHash) {}
    public static StructuredDocument parse(byte[] bytes, String name) throws IOException {
        if(bytes==null) throw new IOException("SOURCE_UNAVAILABLE");
        String ext=name==null?"":name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        Builder b=new Builder();
        if("pdf".equals(ext)) {
            try(var doc=Loader.loadPDF(bytes)) {
                PDFTextStripper strip=new PDFTextStripper();
                for(int page=1;page<=doc.getNumberOfPages();page++) {
                    strip.setStartPage(page);strip.setEndPage(page);
                    b.add(strip.getText(doc),"PDF_PAGE",page,null,null,null,null);
                }
            }
            if(b.text.toString().isBlank()) throw new IOException("PDF_TEXT_UNAVAILABLE_REQUIRES_MANUAL_REVIEW");
        } else if("docx".equals(ext)) {
            try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes))) {
                int paragraph=0,table=0;
                for(IBodyElement element:doc.getBodyElements()) {
                    if(element instanceof XWPFParagraph p) b.add(p.getText(),"DOCX_PARAGRAPH",null,++paragraph,null,null,null);
                    else if(element instanceof XWPFTable t) {
                        table++;int row=0;
                        for(var r:t.getRows()) {row++;int column=0;
                            for(var cell:r.getTableCells()) b.add(cell.getText(),"DOCX_CELL",null,null,table,row,++column);
                        }
                    }
                }
            }
        } else {
            return new StructuredDocument(DocumentParserUtil.parseDocumentStrict(bytes,name),sha(bytes),List.of());
        }
        return new StructuredDocument(b.text.toString(),sha(bytes),List.copyOf(b.blocks));
    }
    private static String sha(byte[] value) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static class Builder {
        StringBuilder text=new StringBuilder();List<Block> blocks=new ArrayList<>();
        void add(String raw,String kind,Integer page,Integer paragraph,Integer table,Integer row,Integer column) {
            String value=new CanonicalDocumentText(2097152).normalize(raw,"PLAIN_TEXT");
            if(value.isEmpty())return;
            if(!text.isEmpty())text.append('\n');
            int start=text.length();text.append(value);
            if(text.length()>2097152 || blocks.size()>=20000) throw new IllegalArgumentException("STRUCTURE_TOO_LARGE");
            blocks.add(new Block(kind,page,paragraph,table,row,column,start,text.length(),CanonicalDocumentText.hash(value)));
        }
    }
}
