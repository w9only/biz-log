package logapi.service;

public interface IFunctionService {

    String apply(String functionName, Object value);

    boolean beforeFunction(String functionName);
}
