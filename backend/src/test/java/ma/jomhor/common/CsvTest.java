package ma.jomhor.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void aTextCellThatStartsLikeAFormulaGetsAQuoteAndTheSeparatorIsQuoted() {
        assertThat(Csv.text("=1+1")).isEqualTo("'=1+1");
        assertThat(Csv.text("+212600000000")).isEqualTo("'+212600000000");
        assertThat(Csv.text("-x")).isEqualTo("'-x");
        assertThat(Csv.text("@SUM(1)")).isEqualTo("'@SUM(1)");
        assertThat(Csv.text("\tx")).isEqualTo("'\tx");
        assertThat(Csv.text("\rx")).isEqualTo("\"'\rx\"");
        assertThat(Csv.text("=a;b")).isEqualTo("\"'=a;b\"");
        assertThat(Csv.text("Tribune \"A\"; Nord")).isEqualTo("\"Tribune \"\"A\"\"; Nord\"");
        assertThat(Csv.text("Guichet Nord")).isEqualTo("Guichet Nord");
        assertThat(Csv.text("")).isEqualTo("");
    }
}
