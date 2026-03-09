package javax.annotation.processing;

import java.lang.annotation.*;
import javax.lang.model.SourceVersion;

/** Stub for Android */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedSourceVersion {
    SourceVersion value();
}
