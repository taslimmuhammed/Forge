package javax.lang.model.element;

import java.util.List;
import javax.lang.model.type.TypeMirror;

/** Stub for Android */
public interface TypeParameterElement extends Element {
    Element getGenericElement();
    List<? extends TypeMirror> getBounds();
}
