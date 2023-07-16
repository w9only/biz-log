package logapi.beans;

import lombok.Builder;
import lombok.Data;

/**
 * @author muzhantong
 * create on 2020/4/29 3:27 下午
 */
@Data
@Builder
public class LogRecordMeta {
    private String successLogTemplate;
    private String failLogTemplate;
    private String operatorName;
    private String bizType;
    private String subBizType;
    private String bizNo;
    private String extra;
    private String condition;
    private String isSuccess;
}
