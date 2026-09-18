// The workbench theme sent in init, so the panel matches a dark Eclipse on a
// light OS (the SWT Browser only knows the OS preference).

package ai.vegaduta.ide.eclipse.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdeThemeTest {

    @Test
    @DisplayName("e4 dark theme ids map to dark")
    void darkIds() {
        assertEquals("dark", WebviewBridge.themeFromId("org.eclipse.e4.ui.css.theme.e4_dark"));
        assertEquals("dark", WebviewBridge.themeFromId("com.github.eclipsecolortheme.themes.Dark"));
    }

    @Test
    @DisplayName("other theme ids map to light")
    void lightIds() {
        assertEquals("light", WebviewBridge.themeFromId("org.eclipse.e4.ui.css.theme.e4_default"));
        assertEquals("light", WebviewBridge.themeFromId("org.eclipse.e4.ui.css.theme.e4_classic"));
    }

    @Test
    @DisplayName("no theme id means follow the OS (omitted from init)")
    void unknown() {
        assertNull(WebviewBridge.themeFromId(""));
        assertNull(WebviewBridge.themeFromId(null));
    }
}
