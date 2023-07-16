package logapi.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface LogField {

    String name();

    String function() default "";

    String dateFormat() default "";
}
