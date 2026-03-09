package javax.lang.model.type;

import java.util.List;
import javax.lang.model.element.Element;

/** Stub for Android */
public interface DeclaredType extends ReferenceType {
    Element asElement();
    TypeMirror getEnclosingType();
    List<? extends TypeMirror> getTypeArguments();
}
