package javax.tools;

/** Stub for Android */
public interface DiagnosticListener<S> {
    void report(Diagnostic<? extends S> diagnostic);
}
