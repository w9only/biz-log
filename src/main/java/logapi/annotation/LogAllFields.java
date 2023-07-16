package logapi.annotation;

import java.lang.annotation.*;

/**
 * all unannotated fields.
 *
 * @author wulang
 **/
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface LogAllFields {
}