package javax.lang.model.util;

import java.util.List;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Stub for Android */
public interface Elements {
    PackageElement getPackageElement(CharSequence name);
    TypeElement getTypeElement(CharSequence name);
    java.util.Map<? extends ExecutableElement, ? extends AnnotationValue>
        getElementValuesWithDefaults(AnnotationMirror a);
    String getDocComment(Element e);
    boolean isDeprecated(Element e);
    Name getBinaryName(TypeElement type);
    PackageElement getPackageOf(Element type);
    java.util.List<? extends Element> getAllMembers(TypeElement type);
    java.util.List<? extends AnnotationMirror> getAllAnnotationMirrors(Element e);
    boolean hides(Element hider, Element hidden);
    boolean overrides(ExecutableElement overrider, ExecutableElement overridden, TypeElement type);
    String getConstantExpression(Object value);
    void printElements(java.io.Writer w, Element... elements);
    Name getName(CharSequence cs);
    boolean isFunctionalInterface(TypeElement type);
}
