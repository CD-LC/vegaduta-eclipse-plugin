// Property tester behind the "there is a non-empty text selection" expression
// in plugin.xml. Eclipse has no built-in property for it, and enabling the
// editor actions on an empty selection would put three dead entries in every
// right-click menu.

package ai.vegaduta.ide.eclipse.actions;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.jface.text.ITextSelection;

public class TextSelectionTester extends PropertyTester {

    static final String PROPERTY_NOT_EMPTY = "selectionNotEmpty";

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if (!PROPERTY_NOT_EMPTY.equals(property) || !(receiver instanceof ITextSelection selection)) {
            return false;
        }
        // getLength() alone is not enough: a selection of only whitespace is
        // non-empty but has nothing worth sending.
        String text = selection.getText();
        return text != null && !text.isBlank();
    }
}
