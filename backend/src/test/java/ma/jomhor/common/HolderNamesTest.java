package ma.jomhor.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/04-api.md §Holder names. */
class HolderNamesTest {

    @Test
    void labelAndInitials() {
        assertThat(HolderNames.label("Yazid Aqel")).isEqualTo("Yazid A.");
        assertThat(HolderNames.initials("Yazid Aqel")).isEqualTo("Y. A.");
        assertThat(HolderNames.label("Yazid")).isEqualTo("Yazid");
        assertThat(HolderNames.initials("Yazid")).isEqualTo("Y.");
        assertThat(HolderNames.label("Jean-Pierre de la Fontaine")).isEqualTo("Jean-Pierre F.");
        assertThat(HolderNames.initials("élodie d'Arc")).isEqualTo("É. D.");
        assertThat(HolderNames.initials("yazid 'aqel")).isEqualTo("Y. A.");
        assertThat(HolderNames.label("Yazid aqel")).isEqualTo("Yazid A.");
        assertThat(HolderNames.label("yazid aqel")).isEqualTo("yazid A.");
        assertThat(HolderNames.label("élodie d'Arc")).isEqualTo("élodie D.");
        assertThat(HolderNames.label("Yazid .")).isEqualTo("Yazid");
        assertThat(HolderNames.initials("Yazid .")).isEqualTo("Y.");
    }

    @Test
    void normalize() {
        assertThat(HolderNames.normalize(" Fe\u0301lix \t O'Brien ")).contains("Félix O'Brien");
        // No-break spaces (U+00A0, U+202F) are whitespace: collapsed, then stripped (no trailing space kept).
        assertThat(HolderNames.normalize("Yazid Aqel\u00A0")).contains("Yazid Aqel");
        assertThat(HolderNames.normalize("\u00A0Yazid Aqel")).contains("Yazid Aqel");
        assertThat(HolderNames.normalize("Yazid Aqel\u202F")).contains("Yazid Aqel");
        assertThat(HolderNames.normalize("\u202FYazid\u00A0\u202F Aqel")).contains("Yazid Aqel");
        // NFKC: fullwidth to ASCII; typographic apostrophe to '.
        assertThat(HolderNames.normalize("\uFF39azid Aqel")).contains("Yazid Aqel");
        assertThat(HolderNames.normalize("N\u2019Diaye")).contains("N'Diaye");
        // Latin-script symbols that are not letters: Roman numerals (refused before NFKC makes them "III").
        assertThat(HolderNames.normalize("\u2160\u2161")).isEmpty();
        assertThat(HolderNames.normalize("Yazid \u2161")).isEmpty();
        assertThat(HolderNames.normalize("Yazid \u2180")).isEmpty();
        assertThat(HolderNames.normalize("Ab")).contains("Ab");
        assertThat(HolderNames.normalize("A" + "b".repeat(59))).isPresent();
        assertThat(HolderNames.normalize("A" + "b".repeat(60))).isEmpty();
        assertThat(HolderNames.normalize("A")).isEmpty();
        assertThat(HolderNames.normalize(null)).isEmpty();
        assertThat(HolderNames.normalize("يزيد")).isEmpty();
        assertThat(HolderNames.normalize("'Ali")).isEmpty();
        assertThat(HolderNames.normalize("Ali_B")).isEmpty();
    }
}
