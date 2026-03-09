package javax.tools;

import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import javax.annotation.processing.Processor;

/** Stub for Android */
public interface JavaCompiler extends Tool {
    interface CompilationTask {
        void setProcessors(Iterable<? extends Processor> processors);
        void setLocale(Locale locale);
        Boolean call();
    }

    CompilationTask getTask(Writer out, JavaFileManager fileManager,
                             DiagnosticListener<? super JavaFileObject> diagnosticListener,
                             Iterable<String> options, Iterable<String> classes,
                             Iterable<? extends JavaFileObject> compilationUnits);

    StandardJavaFileManager getStandardFileManager(
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Locale locale, Charset charset);
}
