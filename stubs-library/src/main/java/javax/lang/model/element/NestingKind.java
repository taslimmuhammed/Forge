package javax.lang.model.element;

/** Stub for Android */
public enum NestingKind {
    TOP_LEVEL, MEMBER, LOCAL, ANONYMOUS;

    public boolean isNested() {
        return this != TOP_LEVEL;
    }
}
