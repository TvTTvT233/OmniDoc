package com.javaee.documentservice.consistency;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** The stored UTF-16 string is the coordinate space of every paragraph/segment citation. */
@Component
public class CanonicalDocumentText {
    public static final String VERSION = "canonical-v1";
    private static final Set<String> BLOCKS = Set.of("p","div","section","article","h1","h2","h3","h4","h5","h6","li","tr","pre","blockquote");
    private final int maxBytes;
    public CanonicalDocumentText(@Value("${omnidoc.index.max-content-bytes:2097152}") int maxBytes) {
        if (maxBytes < 1 || maxBytes > 16777216) throw new IllegalArgumentException("Invalid content limit");
        this.maxBytes = maxBytes;
    }
    public String normalize(String raw, String format) {
        if (raw == null) throw new DocumentStateException(400, "CONTENT_REQUIRED", "必须提供正文");
        checkSize(raw);
        validateUnicode(raw);
        String result;
        if ("PLAIN_TEXT".equals(format)) result = raw;
        else if ("HTML".equals(format)) {
            Element body = Jsoup.parseBodyFragment(raw).body();
            body.select("script,style,noscript,template").remove();
            StringBuilder text = new StringBuilder();
            for (Node child : body.childNodes()) append(child, text);
            result = text.toString();
        } else throw new DocumentStateException(400, "CONTENT_FORMAT_REQUIRED", "请明确正文格式（HTML或PLAIN_TEXT）");
        result = result.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a0', ' ');
        checkSize(result);
        return result;
    }
    private void append(Node node, StringBuilder text) {
        if (node instanceof TextNode leaf) { text.append(leaf.getWholeText()); return; }
        if (!(node instanceof Element element)) return;
        String tag = element.normalName();
        if ("br".equals(tag)) { text.append('\n'); return; }
        boolean block = BLOCKS.contains(tag);
        if (block && !text.isEmpty() && text.charAt(text.length()-1) != '\n') text.append('\n');
        for (Node child : element.childNodes()) append(child, text);
        if (block && !text.isEmpty() && text.charAt(text.length()-1) != '\n') text.append('\n');
        if ("td".equals(tag) || "th".equals(tag)) text.append('\t');
    }
    public void checkSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new DocumentStateException(413, "DOCUMENT_TOO_LARGE", "正文超过大小限制");
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static boolean boundary(String text, int offset) {
        return offset >= 0 && offset <= text.length() && !(offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset-1)) && Character.isLowSurrogate(text.charAt(offset)));
    }
    private static void validateUnicode(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) invalidUnicode();
            } else if (Character.isLowSurrogate(c)) invalidUnicode();
        }
    }
    private static void invalidUnicode() { throw new DocumentStateException(400,"INVALID_UNICODE","正文包含无效字符"); }
}
