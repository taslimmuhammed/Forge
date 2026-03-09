package javax.tools;

import java.io.*;
import java.net.URI;

/** Stub for Android */
public interface FileObject {
    URI toUri();
    String getName();
    InputStream openInputStream() throws IOException;
    OutputStream openOutputStream() throws IOException;
    Reader openReader(boolean ignoreEncodingErrors) throws IOException;
    CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException;
    Writer openWriter() throws IOException;
    long getLastModified();
    boolean delete();
}
