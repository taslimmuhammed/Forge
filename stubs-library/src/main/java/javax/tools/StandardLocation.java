package javax.tools;

/** Stub for Android */
public enum StandardLocation implements JavaFileManager.Location {
    CLASS_OUTPUT, SOURCE_OUTPUT, CLASS_PATH, SOURCE_PATH,
    ANNOTATION_PROCESSOR_PATH, PLATFORM_CLASS_PATH, NATIVE_HEADER_OUTPUT;

    @Override
    public String getName() { return name(); }

    @Override
    public boolean isOutputLocation() {
        switch (this) {
            case CLASS_OUTPUT: case SOURCE_OUTPUT: case NATIVE_HEADER_OUTPUT:
                return true;
            default:
                return false;
        }
    }

    public static StandardLocation locationFor(String name) {
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return null; }
    }
}
