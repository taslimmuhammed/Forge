package javax.tools;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** Stub for Android */
public class ForwardingJavaFileManager<M extends JavaFileManager> implements JavaFileManager {
    protected final M fileManager;

    protected ForwardingJavaFileManager(M fileManager) { this.fileManager = fileManager; }

    @Override public ClassLoader getClassLoader(Location location) { return fileManager.getClassLoader(location); }
    @Override public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException { return fileManager.list(location, packageName, kinds, recurse); }
    @Override public String inferBinaryName(Location location, JavaFileObject file) { return fileManager.inferBinaryName(location, file); }
    @Override public boolean isSameFile(FileObject a, FileObject b) { return fileManager.isSameFile(a, b); }
    @Override public boolean handleOption(String current, Iterator<String> remaining) { return fileManager.handleOption(current, remaining); }
    @Override public boolean hasLocation(Location location) { return fileManager.hasLocation(location); }
    @Override public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException { return fileManager.getJavaFileForInput(location, className, kind); }
    @Override public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException { return fileManager.getJavaFileForOutput(location, className, kind, sibling); }
    @Override public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException { return fileManager.getFileForInput(location, packageName, relativeName); }
    @Override public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException { return fileManager.getFileForOutput(location, packageName, relativeName, sibling); }
    @Override public void flush() throws IOException { fileManager.flush(); }
    @Override public void close() throws IOException { fileManager.close(); }
    @Override public int isSupportedOption(String option) { return fileManager.isSupportedOption(option); }
}
