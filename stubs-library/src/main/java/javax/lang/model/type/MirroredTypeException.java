package javax.lang.model.type;

/** Stub for Android */
public class MirroredTypeException extends RuntimeException {
    private transient TypeMirror type;

    public MirroredTypeException(TypeMirror type) {
        super("Attempt to access Class object for TypeMirror");
        this.type = type;
    }

    public TypeMirror getTypeMirror() { return type; }
}
