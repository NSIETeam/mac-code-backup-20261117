package cn.siyue.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict patch readers shared by the two existing profile types. */
final class CrmProfileValues {
    private CrmProfileValues() { }
    static void keys(Map<String,Object> value,Set<String> allowed) {
        if(value==null||value.isEmpty()||!allowed.containsAll(value.keySet())) throw invalid("修改字段为空或不受支持");
    }
    static String text(Object value,int max,boolean required) {
        if(value==null&&!required) return null;
        if(!(value instanceof String text)||text.trim().length()>max||required&&text.isBlank()) throw invalid("文字内容无效");
        return ((String)value).trim();
    }
    static String id(Object value) {
        String result=text(value,120,true);
        if(!result.matches("[A-Za-z0-9_.-]{1,120}")) throw invalid("记录编号无效");return result;
    }
    static long integer(Object value,long max) {
        if(!(value instanceof Number number)) throw invalid("数量必须为整数");
        try {long result=new java.math.BigDecimal(number.toString()).longValueExact();if(result<0||result>max) throw invalid("数量超出范围");return result;}
        catch(ArithmeticException error) {throw invalid("数量必须为整数");}
    }
    static List<String> ids(Object value) {
        if(!(value instanceof List<?> list)||list.size()>100) throw invalid("关联列表至多100项");
        List<String> ids=((List<?>)value).stream().map(CrmProfileValues::id).toList();
        if(ids.stream().distinct().count()!=ids.size()) throw invalid("关联列表重复");return ids;
    }
    static void version(long actual,long expected) {
        if(expected<1) throw invalid("须提供当前版本");
        if(actual!=expected) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"资料已变化，请刷新后重试");
    }
    static BusinessException invalid(String text) {return new BusinessException(ErrorCode.INVALID_INPUT,text);}
}
