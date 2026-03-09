package javax.lang.model.element;

import java.util.List;
import javax.lang.model.type.TypeMirror;

/** Stub for Android */
public interface ExecutableElement extends Element, Parameterizable {
    List<? extends TypeParameterElement> getTypeParameters();
    TypeMirror getReturnType();
    List<? extends VariableElement> getParameters();
    List<? extends TypeMirror> getThrownTypes();
    AnnotationValue getDefaultValue();
    boolean isVarArgs();
}
