package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.discovery.representation.RepresentationDiscoveryRunComparison.Category;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RepresentationDiscoveryRunComparisonLocaleTest {

    @Test
    void canonicalEntryKeyUsesAsciiDigitsUnderNonLatinDefaultLocale()
            throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals(
                "0009/workspaceClaimBoundary",
                key(Category.CLAIM_BOUNDARY, "workspaceClaimBoundary")
            );
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static String key(Category category, String field)
            throws Exception {
        Method method = RepresentationDiscoveryRunComparison.class
            .getDeclaredMethod("key", Category.class, String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, category, field);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) {
                throw nested;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        }
    }
}
