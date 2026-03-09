package javax.annotation.processing;

import java.lang.annotation.*;

/** Stub for Android */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedAnnotationTypes {
    String[] value();
}
