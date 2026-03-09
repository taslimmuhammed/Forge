package javax.annotation.processing;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;

/** Stub for Android */
public interface Messager {
    void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence msg);
    void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence msg, Element e);
    void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a);
    void printMessage(javax.tools.Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v);
}
