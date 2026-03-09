package javax.tools;

/** Stub for Android */
public interface JavaFileObject extends FileObject {
    enum Kind {
        SOURCE(".java"), CLASS(".class"), HTML(".html"), OTHER("");

        public final String extension;
        Kind(String extension) { this.extension = extension; }
    }

    Kind getKind();
    boolean isNameCompatible(String simpleName, Kind kind);
    javax.lang.model.element.NestingKind getNestingKind();
    javax.lang.model.element.Modifier getAccessLevel();
}
