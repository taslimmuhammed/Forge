package javax.annotation.processing;

import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/** Stub for Android */
public interface Processor {
    Set<String> getSupportedOptions();
    Set<String> getSupportedAnnotationTypes();
    SourceVersion getSupportedSourceVersion();
    void init(ProcessingEnvironment processingEnv);
    boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv);
    Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
                                                   ExecutableElement member, String userText);
}
