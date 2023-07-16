package logapi.starter;

import org.springframework.context.annotation.Import;
import java.lang.annotation.*;

/**
 * DATE 6:28 PM
 *
 * @author mzt.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(LogRecordProxyAutoConfiguration.class)
public @interface EnableLogRecord {

}
