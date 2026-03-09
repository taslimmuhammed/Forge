package javax.lang.model.element;

import java.util.List;
import javax.lang.model.type.TypeMirror;

/** Stub for Android */
public interface TypeElement extends Element, QualifiedNameable, Parameterizable {
    NestingKind getNestingKind();
    Name getQualifiedName();
    TypeMirror getSuperclass();
    List<? extends TypeMirror> getInterfaces();
    List<? extends TypeParameterElement> getTypeParameters();
}
