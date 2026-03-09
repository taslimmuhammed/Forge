package javax.lang.model.type;

import javax.lang.model.element.Element;

/** Stub for Android */
public interface TypeVariable extends ReferenceType {
    Element asElement();
    TypeMirror getUpperBound();
    TypeMirror getLowerBound();
}
