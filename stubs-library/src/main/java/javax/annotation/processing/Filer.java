package javax.annotation.processing;

import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;
import javax.tools.FileObject;

/** Stub for Android */
public interface Filer {
    JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) throws java.io.IOException;
    JavaFileObject createClassFile(CharSequence name, Element... originatingElements) throws java.io.IOException;
    FileObject createResource(javax.tools.JavaFileManager.Location location, CharSequence pkg,
                               CharSequence relativeName, Element... originatingElements) throws java.io.IOException;
    FileObject getResource(javax.tools.JavaFileManager.Location location, CharSequence pkg,
                            CharSequence relativeName) throws java.io.IOException;
}
