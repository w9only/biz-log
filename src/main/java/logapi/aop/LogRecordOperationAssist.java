package logapi.aop;

import logapi.annotation.LogRecord;
import logapi.annotation.LogRecords;
import logapi.beans.LogRecordMeta;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * DATE 6:03 PM
 *
 * @author mzt.
 */
public class LogRecordOperationAssist {
    /**
     * Cache for equivalent methods on an interface implemented by the declaring class.
     */
    private static final Map<Method, Method> INTERFACE_METHOD_CACHE = new ConcurrentReferenceHashMap<>(256);

    public Collection<LogRecordMeta> computeLogRecordOperations(Method method, Class<?> targetClass) {
        // Don't allow no-public methods as required.
        if (!Modifier.isPublic(method.getModifiers())) {
            return Collections.emptyList();
        }

        // The method may be on an interface, but we need attributes from the target class.
        // If the target class is null, the method will be unchanged.
        Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
        // If we are dealing with method with generic parameters, find the original method.
        specificMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);

        // First try is the method in the target class.
        Collection<LogRecordMeta> LogRecordMeta = parseLogRecordAnnotations(specificMethod);
        Collection<LogRecordMeta> logRecordsOps = parseLogRecordsAnnotations(specificMethod);
        Collection<LogRecordMeta> abstractLogRecordMeta = parseLogRecordAnnotations(getInterfaceMethodIfPossible(method));
        Collection<LogRecordMeta> abstractLogRecordsOps = parseLogRecordsAnnotations(getInterfaceMethodIfPossible(method));
        HashSet<LogRecordMeta> result = new HashSet<>();
        result.addAll(LogRecordMeta);
        result.addAll(abstractLogRecordMeta);
        result.addAll(logRecordsOps);
        result.addAll(abstractLogRecordsOps);
        return result;
    }

    /**
     * Determine a corresponding interface method for the given method handle, if possible.
     * <p>This is particularly useful for arriving at a public exported type on Jigsaw
     * which can be reflectively invoked without an illegal access warning.
     *
     * @param method the method to be invoked, potentially from an implementation class
     * @return the corresponding interface method, or the original method if none found
     */
    public static Method getInterfaceMethodIfPossible(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass().isInterface()) {
            return method;
        }
        return INTERFACE_METHOD_CACHE.computeIfAbsent(method, key -> {
            Class<?> current = key.getDeclaringClass();
            while (current != null && current != Object.class) {
                Class<?>[] ifcs = current.getInterfaces();
                for (Class<?> ifc : ifcs) {
                    try {
                        return ifc.getMethod(key.getName(), key.getParameterTypes());
                    } catch (NoSuchMethodException ex) {
                        // ignore
                    }
                }
                current = current.getSuperclass();
            }
            return key;
        });
    }

    private Collection<LogRecordMeta> parseLogRecordsAnnotations(AnnotatedElement ae) {
        Collection<LogRecordMeta> res = new ArrayList<>();
        Collection<LogRecords> logRecordAnnotationAnnotations = AnnotatedElementUtils.findAllMergedAnnotations(ae, LogRecords.class);
        if (!logRecordAnnotationAnnotations.isEmpty()) {
            logRecordAnnotationAnnotations.forEach(logRecords -> {
                LogRecord[] value = logRecords.value();
                for (LogRecord logRecord : value) {
                    res.add(parseLogRecordAnnotation(ae, logRecord));
                }
            });
        }
        return res;
    }

    private Collection<LogRecordMeta> parseLogRecordAnnotations(AnnotatedElement ae) {
        Collection<LogRecord> logRecordAnnotationAnnotations = AnnotatedElementUtils.findAllMergedAnnotations(ae, LogRecord.class);
        Collection<LogRecordMeta> ret = new ArrayList<>();
        if (!logRecordAnnotationAnnotations.isEmpty()) {
            for (LogRecord recordAnnotation : logRecordAnnotationAnnotations) {
                ret.add(parseLogRecordAnnotation(ae, recordAnnotation));
            }
        }
        return ret;
    }

    private LogRecordMeta parseLogRecordAnnotation(AnnotatedElement ae, LogRecord recordAnnotation) {
        LogRecordMeta recordOps = LogRecordMeta.builder()
                .successLogTemplate(recordAnnotation.success())
                .failLogTemplate(recordAnnotation.fail())
                .bizType(recordAnnotation.bizType())
                .bizNo(recordAnnotation.bizNo())
                .operatorName(recordAnnotation.operator())
                .subBizType(recordAnnotation.subBizType())
                .extra(recordAnnotation.extra())
                .condition(recordAnnotation.condition())
                .isSuccess(recordAnnotation.successCondition())
                .build();
        validateLogRecordOperation(ae, recordOps);
        return recordOps;
    }


    private void validateLogRecordOperation(AnnotatedElement ae, LogRecordMeta recordOps) {
        if (!StringUtils.hasText(recordOps.getSuccessLogTemplate()) && !StringUtils.hasText(recordOps.getFailLogTemplate())) {
            throw new IllegalStateException("Invalid logRecord annotation configuration on '" +
                    ae.toString() + "'. 'one of successTemplate and failLogTemplate' attribute must be set.");
        }
    }

}
