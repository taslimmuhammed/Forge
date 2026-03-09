package javax.tools;

import java.io.*;
import java.net.URI;

/** Stub for Android */
public class SimpleJavaFileObject implements JavaFileObject {
    protected final URI uri;
    protected final Kind kind;

    protected SimpleJavaFileObject(URI uri, Kind kind) {
        this.uri = uri;
        this.kind = kind;
    }

    @Override public URI toUri() { return uri; }
    @Override public String getName() { return uri.getPath(); }
    @Override public InputStream openInputStream() throws IOException { throw new UnsupportedOperationException(); }
    @Override public OutputStream openOutputStream() throws IOException { throw new UnsupportedOperationException(); }
    @Override public Reader openReader(boolean ignoreEncodingErrors) throws IOException { throw new UnsupportedOperationException(); }
    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException { throw new UnsupportedOperationException(); }
    @Override public Writer openWriter() throws IOException { throw new UnsupportedOperationException(); }
    @Override public long getLastModified() { return 0; }
    @Override public boolean delete() { return false; }
    @Override public Kind getKind() { return kind; }
    @Override public boolean isNameCompatible(String simpleName, Kind kind) { return true; }
    @Override public javax.lang.model.element.NestingKind getNestingKind() { return null; }
    @Override public javax.lang.model.element.Modifier getAccessLevel() { return null; }
}
