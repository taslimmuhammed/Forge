package javax.annotation.processing;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/** Stub for Android */
public abstract class AbstractProcessor implements Processor {
    protected ProcessingEnvironment processingEnv;

    protected AbstractProcessor() {}

    @Override
    public Set<String> getSupportedOptions() { return Collections.emptySet(); }

    @Override
    public Set<String> getSupportedAnnotationTypes() { return Collections.emptySet(); }

    @Override
    public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_8; }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
    }

    @Override
    public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
                                                          ExecutableElement member, String userText) {
        return Collections.emptyList();
    }
}
