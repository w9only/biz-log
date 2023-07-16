package logapi.starter;

import logapi.parse.LogFunctionParser;
import logapi.parse.LogRecordValueParser;
import logapi.service.IFunctionService;
import logapi.service.ILogRecordPerformanceMonitor;
import logapi.service.ILogRecordService;
import logapi.service.IOperatorGetService;
import logapi.service.IParseFunction;
import logapi.service.impl.DefaultDiffItemsToLogContentService;
import logapi.service.IDiffItemsToLogContentService;
import logapi.aop.LogRecordInterceptor;
import logapi.aop.LogRecordOperationAssist;
import logapi.service.impl.DefaultFunctionServiceImpl;
import logapi.service.impl.DefaultLogRecordPerformanceMonitor;
import logapi.service.impl.DefaultLogRecordServiceImpl;
import logapi.service.impl.DefaultOperatorGetServiceImpl;
import logapi.service.impl.DefaultParseFunction;
import logapi.service.impl.DiffParseFunction;
import logapi.service.impl.ParseFunctionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportAware;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * @author muzhantong
 * create on 2020/6/12 10:41 上午
 */
@Configuration
@EnableConfigurationProperties({LogRecordProperties.class})
@Slf4j
@Import(LogRecordInterceptor.class)
@ConditionalOnProperty(prefix = "demo.log",value = "enabled",havingValue = "true")
public class LogRecordProxyAutoConfiguration implements ImportAware {

    private AnnotationAttributes enableLogRecord;

    @Bean
    public LogRecordOperationAssist logRecordOperationSource() {
        return new LogRecordOperationAssist();
    }

    @Bean
    @ConditionalOnMissingBean(IFunctionService.class)
    public IFunctionService functionService(ParseFunctionFactory parseFunctionFactory) {
        return new DefaultFunctionServiceImpl(parseFunctionFactory);
    }

    @Bean
    public ParseFunctionFactory parseFunctionFactory(@Autowired List<IParseFunction> parseFunctions) {
        return new ParseFunctionFactory(parseFunctions);
    }

    @Bean
    @ConditionalOnMissingBean(IParseFunction.class)
    public DefaultParseFunction parseFunction() {
        return new DefaultParseFunction();
    }


    @Bean
    @ConditionalOnMissingBean(ILogRecordPerformanceMonitor.class)
    public ILogRecordPerformanceMonitor logRecordPerformanceMonitor() {
        return new DefaultLogRecordPerformanceMonitor();
    }

    @Bean
    public LogRecordValueParser logRecordValueParser() {
        return new LogRecordValueParser();
    }

    @Bean
    public LogFunctionParser logFunctionParser(IFunctionService functionService) {
        return new LogFunctionParser(functionService);
    }

    @Bean
    public DiffParseFunction diffParseFunction(IDiffItemsToLogContentService diffItemsToLogContentService,
                                               LogRecordProperties logRecordProperties) {
        DiffParseFunction diffParseFunction = new DiffParseFunction();
        diffParseFunction.setDiffItemsToLogContentService(diffItemsToLogContentService);
        // issue#111
        diffParseFunction.addUseEqualsClass(LocalDateTime.class);
        if (!StringUtils.isEmpty(logRecordProperties.getUseEqualsMethod())) {
            diffParseFunction.addUseEqualsClass(Arrays.asList(logRecordProperties.getUseEqualsMethod().split(",")));
        }
        return diffParseFunction;
    }

    @Bean
    @ConditionalOnMissingBean(IDiffItemsToLogContentService.class)
    public IDiffItemsToLogContentService diffItemsToLogContentService(LogRecordProperties logRecordProperties) {
        return new DefaultDiffItemsToLogContentService(logRecordProperties);
    }

    @Bean
    @ConditionalOnMissingBean(IOperatorGetService.class)
    public IOperatorGetService operatorGetService() {
        return new DefaultOperatorGetServiceImpl();
    }

    @Bean
    @ConditionalOnMissingBean(ILogRecordService.class)
    public ILogRecordService recordService() {
        return new DefaultLogRecordServiceImpl();
    }

    @Override
    public void setImportMetadata(AnnotationMetadata importMetadata) {
        this.enableLogRecord = AnnotationAttributes.fromMap(
                importMetadata.getAnnotationAttributes(EnableLogRecord.class.getName(), false));
        if (this.enableLogRecord == null) {
            log.info("EnableLogRecord is not present on importing class");
        }
    }
}
