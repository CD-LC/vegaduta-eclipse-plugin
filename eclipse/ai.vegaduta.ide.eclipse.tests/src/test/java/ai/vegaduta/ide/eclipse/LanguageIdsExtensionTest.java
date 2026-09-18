// LanguageIds.extensionFor: the scratch-file extension ui.newFile uses so that
// Eclipse opens generated code in the matching editor.

package ai.vegaduta.ide.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ai.vegaduta.ide.eclipse.ui.LanguageIds;

class LanguageIdsExtensionTest {

    @ParameterizedTest
    @CsvSource({ "java,java", "python,py", "typescript,ts", "typescriptreact,tsx", "shellscript,sh",
            "markdown,md", "diff,diff", "JAVA,java", "csharp,cs" })
    @DisplayName("known languageIds map to their usual extension")
    void known(String languageId, String extension) {
        assertEquals(extension, LanguageIds.extensionFor(languageId));
    }

    @Test
    @DisplayName("unknown or absent languageIds fall back to txt, never null")
    void fallback() {
        assertEquals("txt", LanguageIds.extensionFor(null));
        assertEquals("txt", LanguageIds.extensionFor("cobol-2080"));
        assertEquals("txt", LanguageIds.extensionFor(""));
    }

    @Test
    @DisplayName("every extension forFileName knows round-trips back to the same languageId")
    void roundTrip() {
        for (String id : new String[] { "java", "python", "javascript", "typescript", "kotlin", "go", "rust",
                "ruby", "php", "csharp", "c", "cpp", "shellscript", "sql", "xml", "html", "css", "json", "yaml",
                "markdown", "javascriptreact", "typescriptreact" }) {
            assertEquals(id, LanguageIds.forFileName("x." + LanguageIds.extensionFor(id)), id);
        }
    }
}
