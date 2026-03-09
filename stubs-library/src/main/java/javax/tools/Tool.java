package javax.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import javax.lang.model.SourceVersion;

/** Stub for Android */
public interface Tool {
    int run(InputStream in, OutputStream out, OutputStream err, String... arguments);
    Set<SourceVersion> getSourceVersions();
}
