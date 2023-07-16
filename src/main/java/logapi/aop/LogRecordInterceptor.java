package logapi.aop;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.mzt.logapi.context.LogRecordContext;
import logapi.beans.CodeVariableType;
import logapi.beans.LogRecord;
import logapi.beans.LogRecordMeta;
import logapi.beans.MethodExecuteResult;
import logapi.parse.LogRecordValueParser;
import logapi.service.ILogRecordPerformanceMonitor;
import logapi.service.ILogRecordService;
import logapi.service.IOperatorGetService;
import logapi.starter.LogRecordProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.*;

import static logapi.service.ILogRecordPerformanceMonitor.MONITOR_NAME;
import static logapi.service.ILogRecordPerformanceMonitor.MONITOR_TASK_AFTER_EXECUTE;
import static logapi.service.ILogRecordPerformanceMonitor.MONITOR_TASK_BEFORE_EXECUTE;


/**
 * DATE 5:39 PM
 *
 * @author mzt.
 */
@Slf4j
@Aspect
@Component
public class LogRecordInterceptor {
    @Resource
    private LogRecordOperationAssist logRecordOperationSource;
    @Resource
    private ILogRecordService bizLogService;
    @Resource
    private IOperatorGetService operatorGetService;
    @Resource
    private ILogRecordPerformanceMonitor logRecordPerformanceMonitor;
    @Resource
    private LogRecordValueParser logRecordValueParser;
    @Resource
    private LogRecordProperties logRecordProperties;
    @Resource
    private Environment environment;


    // 配置织入点
    @Pointcut("@annotation(logapi.annotation.LogRecord)")
    public void executionTimePointCut() {

    }

    @Around("executionTimePointCut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        Method method = getMethod(point);
        return execute(point, point.getTarget(), method, point.getArgs());
    }

    /**
     * 从原生类获取method，直接从切点获取可能是被代理的对象
     * @param jp
     * @return
     * @throws NoSuchMethodException
     */
    private Method getMethod(ProceedingJoinPoint jp) throws NoSuchMethodException {
        Signature signature = jp.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        return jp.getTarget().getClass().getMethod(methodSignature.getName(), methodSignature.getParameterTypes());
    }

    private Object execute(ProceedingJoinPoint point, Object target, Method method, Object[] args) throws Throwable {
        //代理不拦截
        if (AopUtils.isAopProxy(target)) {
            return point.proceed();
        }
        StopWatch stopWatch = new StopWatch(MONITOR_NAME);
        stopWatch.start(MONITOR_TASK_BEFORE_EXECUTE);
        Class<?> targetClass = getTargetClass(target);
        Object ret = null;
        MethodExecuteResult methodExecuteResult = new MethodExecuteResult(method, args, targetClass);
        LogRecordContext.putEmptySpan();
        Collection<LogRecordMeta> operations = new ArrayList<>();
        Map<String, String> functionNameAndReturnMap = new HashMap<>();
        try {
            operations = logRecordOperationSource.computeLogRecordOperations(method, targetClass);
            List<String> spElTemplates = getBeforeExecuteFunctionTemplate(operations);
            functionNameAndReturnMap = logRecordValueParser.processBeforeExecuteFunctionTemplate(spElTemplates, targetClass, method, args);
        } catch (Exception e) {
            log.error("log record parse before function exception", e);
        } finally {
            stopWatch.stop();
        }

        try {
            ret = point.proceed();
            methodExecuteResult.setResult(ret);
            methodExecuteResult.setSuccess(true);
        } catch (Exception e) {
            methodExecuteResult.setSuccess(false);
            methodExecuteResult.setThrowable(e);
            methodExecuteResult.setErrorMsg(e.getMessage());
        }
        stopWatch.start(MONITOR_TASK_AFTER_EXECUTE);
        try {
            if (!CollectionUtils.isEmpty(operations)) {
                recordExecute(methodExecuteResult, functionNameAndReturnMap, operations);
            }
        } catch (Exception t) {
            log.error("log record parse exception", t);
            throw t;
        } finally {
            LogRecordContext.clear();
            stopWatch.stop();
            try {
                logRecordPerformanceMonitor.print(stopWatch);
            } catch (Exception e) {
                log.error("execute exception", e);
            }
        }

        if (methodExecuteResult.getThrowable() != null) {
            throw methodExecuteResult.getThrowable();
        }
        return ret;
    }

    private List<String> getBeforeExecuteFunctionTemplate(Collection<LogRecordMeta> operations) {
        List<String> spElTemplates = new ArrayList<>();
        for (LogRecordMeta operation : operations) {
            //执行之前的函数，失败模版不解析
            List<String> templates = getSpElTemplates(operation, operation.getSuccessLogTemplate());
            if (!CollectionUtils.isEmpty(templates)) {
                spElTemplates.addAll(templates);
            }
        }
        return spElTemplates;
    }

    private void recordExecute(MethodExecuteResult methodExecuteResult, Map<String, String> functionNameAndReturnMap,
                               Collection<LogRecordMeta> operations) {
        for (LogRecordMeta operation : operations) {
            try {
                if (StringUtils.isEmpty(operation.getSuccessLogTemplate())
                        && StringUtils.isEmpty(operation.getFailLogTemplate())) {
                    continue;
                }
                if (exitsCondition(methodExecuteResult, functionNameAndReturnMap, operation)) continue;
                if (!methodExecuteResult.isSuccess()) {
                    failRecordExecute(methodExecuteResult, functionNameAndReturnMap, operation);
                } else {
                    successRecordExecute(methodExecuteResult, functionNameAndReturnMap, operation);
                }
            } catch (Exception t) {
                log.error("log record execute exception", t);
                if (logRecordProperties.isJoinTransaction()) throw t;
            }
        }
    }

    private void successRecordExecute(MethodExecuteResult methodExecuteResult, Map<String, String> functionNameAndReturnMap,
                                      LogRecordMeta operation) {
        // 若存在 isSuccess 条件模版，解析出成功/失败的模版
        String action = "";
        boolean flag = true;
        if (!StringUtils.isEmpty(operation.getIsSuccess())) {
            String condition = logRecordValueParser.singleProcessTemplate(methodExecuteResult, operation.getIsSuccess(), functionNameAndReturnMap);
            if (StringUtils.endsWithIgnoreCase(condition, "true")) {
                action = operation.getSuccessLogTemplate();
            } else {
                action = operation.getFailLogTemplate();
                flag = false;
            }
        } else {
            action = operation.getSuccessLogTemplate();
        }
        if (StringUtils.isEmpty(action)) {
            // 没有日志内容则忽略
            return;
        }
        List<String> spElTemplates = getSpElTemplates(operation, action);
        String operatorIdFromService = getOperatorIdFromServiceAndPutTemplate(operation, spElTemplates);
        Map<String, String> expressionValues = logRecordValueParser.processTemplate(spElTemplates, methodExecuteResult, functionNameAndReturnMap);
        saveLog(methodExecuteResult.getMethod(), !flag, operation, operatorIdFromService, action, expressionValues);
    }

    private void failRecordExecute(MethodExecuteResult methodExecuteResult, Map<String, String> functionNameAndReturnMap,
                                   LogRecordMeta operation) {
        if (StringUtils.isEmpty(operation.getFailLogTemplate())) return;

        String action = operation.getFailLogTemplate();
        List<String> spElTemplates = getSpElTemplates(operation, action);
        String operatorIdFromService = getOperatorIdFromServiceAndPutTemplate(operation, spElTemplates);

        Map<String, String> expressionValues = logRecordValueParser.processTemplate(spElTemplates, methodExecuteResult, functionNameAndReturnMap);
        saveLog(methodExecuteResult.getMethod(), true, operation, operatorIdFromService, action, expressionValues);
    }

    private boolean exitsCondition(MethodExecuteResult methodExecuteResult,
                                   Map<String, String> functionNameAndReturnMap, LogRecordMeta operation) {
        if (!StringUtils.isEmpty(operation.getCondition())) {
            String condition = logRecordValueParser.singleProcessTemplate(methodExecuteResult, operation.getCondition(), functionNameAndReturnMap);
            if (StringUtils.endsWithIgnoreCase(condition, "false")) return true;
        }
        return false;
    }

    private void saveLog(Method method, boolean flag, LogRecordMeta operation, String operatorFromService,
                         String action, Map<String, String> expressionValues) {
        if (StringUtils.isEmpty(expressionValues.get(action)) ||
                (!logRecordValueParser.isDiffLog() && action.contains("#") && Objects.equals(action, expressionValues.get(action)))) {
            return;
        }
        LogRecord logRecord = LogRecord.builder()
                .applicationName(environment.getProperty("spring.application.name"))
                .bizType(expressionValues.get(operation.getBizType()))
                .subBizType(expressionValues.get(operation.getSubBizType()))
                .bizNo(expressionValues.get(operation.getBizNo()))
                .operator(getRealOperator(operation, operatorFromService, expressionValues))
                .extra(expressionValues.get(operation.getExtra()))
                .codeVariable(getCodeVariable(method))
                .action(expressionValues.get(action))
                .fail(flag)
                .createTime(new Date())
                .ip(getIp())
                .build();
        bizLogService.record(logRecord);
    }

    private String getIp() {
        String ip = "";
         ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
         if(sra == null) {
             return ip;
         }
         HttpServletRequest request = sra.getRequest();
         return getIp(request);
    }

    private String getIp(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private Map<CodeVariableType, Object> getCodeVariable(Method method) {
        Map<CodeVariableType, Object> map = new HashMap<>();
        map.put(CodeVariableType.ClassName, method.getDeclaringClass());
        map.put(CodeVariableType.MethodName, method.getName());
        return map;
    }

    private List<String> getSpElTemplates(LogRecordMeta operation, String... actions) {
        List<String> spElTemplates = new ArrayList<>();
        spElTemplates.add(operation.getBizType());
        spElTemplates.add(operation.getBizNo());
        spElTemplates.add(operation.getSubBizType());
        spElTemplates.add(operation.getExtra());
        spElTemplates.addAll(Arrays.asList(actions));
        return spElTemplates;
    }

    private String getRealOperator(LogRecordMeta operation, String operatorFromService, Map<String, String> expressionValues) {
        return !StringUtils.isEmpty(operatorFromService) ? operatorFromService : expressionValues.get(operation.getOperatorName());
    }

    private String getOperatorIdFromServiceAndPutTemplate(LogRecordMeta operation, List<String> spElTemplates) {

        String realOperatorId = "";
        if (StringUtils.isEmpty(operation.getOperatorName())) {
            realOperatorId = operatorGetService.getUser().getOperatorId();
            if (StringUtils.isEmpty(realOperatorId)) {
                throw new IllegalArgumentException("[LogRecord] operator is null");
            }
        } else {
            spElTemplates.add(operation.getOperatorName());
        }
        return realOperatorId;
    }

    private Class<?> getTargetClass(Object target) {
        return AopProxyUtils.ultimateTargetClass(target);
    }

}
