package javax.lang.model;

/**
 * Stub for Android - this class is normally provided by the JDK's java.compiler module.
 * ECJ references it but doesn't need full functionality for basic compilation.
 */
public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11;

    public static SourceVersion latest() {
        return RELEASE_11;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_8;
    }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        return isIdentifier(name);
    }

    public static boolean isKeyword(CharSequence s) {
        return false;
    }
}
