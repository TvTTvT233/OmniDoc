package com.javaee.documentservice.consistency;

import com.javaee.documentservice.util.DocumentParserUtil;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class CanonicalDocumentTextTest {
    private final CanonicalDocumentText text=new CanonicalDocumentText(2097152);
    @Test void htmlKeepsParagraphsDecodesEntitiesAndDoesNotFlattenWhitespace() {
        assertThat(text.normalize("<p>第一段 &amp; 😀</p><div>第二段<br>末行</div><script>secret()</script><style>bad</style>","HTML"))
                .isEqualTo("第一段 & 😀\n第二段\n末行\n");
    }
    @Test void plainTextDoesNotTreatAngleBracketsAsMarkupAndNormalizesLineEndingsOnly() {
        assertThat(text.normalize(" a  <b> \r\n\r\n尾行\r", "PLAIN_TEXT")).isEqualTo(" a  <b> \n\n尾行\n");
    }
    @Test void explicitEmptyContentIsValidButUnknownFormatIsNot() {
        assertThat(text.normalize("", "HTML")).isEmpty();
        assertThatThrownBy(()->text.normalize("hello",null)).isInstanceOf(DocumentStateException.class);
    }
    @Test void utf16CoordinatesCannotSplitEmoji() {
        String s="中😀文";
        assertThat(CanonicalDocumentText.boundary(s,1)).isTrue();
        assertThat(CanonicalDocumentText.boundary(s,2)).isFalse();
        assertThat(CanonicalDocumentText.boundary(s,3)).isTrue();
        assertThat(CanonicalDocumentText.boundary(s,-1)).isFalse();
        assertThat(text.normalize(s,"PLAIN_TEXT")).isEqualTo(s);
    }
    @Test void utf8ByteLimitAndUnpairedSurrogatesAreRejected() {
        assertThatThrownBy(()->new CanonicalDocumentText(3).normalize("中文","PLAIN_TEXT"))
                .isInstanceOfSatisfying(DocumentStateException.class,e->assertThat(e.getStatus()).isEqualTo(413));
        assertThatThrownBy(()->text.normalize("\ud800","PLAIN_TEXT")).isInstanceOf(DocumentStateException.class);
    }
    @Test void parserDoesNotTurnBrokenPdfOrInvalidUtf8IntoSuccessfulText() {
        assertThatThrownBy(()->DocumentParserUtil.parseDocumentStrict("not a PDF".getBytes(StandardCharsets.UTF_8),"broken.pdf")).isInstanceOf(Exception.class);
        assertThatThrownBy(()->DocumentParserUtil.parseDocumentStrict(new byte[]{(byte)0xff},"broken.txt")).isInstanceOf(Exception.class);
        assertThatCode(()->DocumentParserUtil.parseDocumentStrict(new byte[0],"empty.txt")).doesNotThrowAnyException();
    }
}
