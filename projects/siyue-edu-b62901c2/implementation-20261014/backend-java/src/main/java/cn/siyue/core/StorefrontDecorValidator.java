package cn.siyue.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** The public presentation document has an explicit, bounded schema. */
final class StorefrontDecorValidator {
    private final JdbcTemplate jdbc;
    private final boolean current;
    StorefrontDecorValidator(JdbcTemplate jdbc) {this(jdbc,true);}
    StorefrontDecorValidator(JdbcTemplate jdbc,boolean current) {this.jdbc=jdbc;this.current=current;}
    Map<String,Object> clean(Map<String,Object> input) {
        require(input!=null,"请先填写装修内容");keys(input,Set.of("title","subtitle","color","modules","blocks"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("title",text(input.get("title"),100,true));result.put("subtitle",text(input.get("subtitle"),300,false));
        String color=input.get("color")==null?"#3979ed":text(input.get("color"),7,true);require(color.matches("#[a-fA-F0-9]{6}"),"主题颜色无效");result.put("color",color);
        List<String> modules=strings(input.get("modules"),20,100);modules.forEach(this::moduleKey);result.put("modules",modules);
        Map<String,Object> blocks=object(input.get("blocks"));require(blocks.size()<=40,"模块数量过多");Map<String,Object> cleaned=new LinkedHashMap<>();
        blocks.forEach((key,value)->{moduleKey(key);cleaned.put(key,block(object(value),modules.contains(key)));});
        require(cleaned.keySet().containsAll(modules),"显示模块缺少内容");result.put("blocks",cleaned);return result;
    }
    Map<String,Object> published(Map<String,Object> draft) {
        Map<String,Object> result=new LinkedHashMap<>(draft),blocks=object(draft.get("blocks")),published=new LinkedHashMap<>();
        for(String module:strings(draft.get("modules"),20,100)) published.put(module,blocks.get(module));result.put("blocks",published);return result;
    }
    private Map<String,Object> block(Map<String,Object> input,boolean visible) {
        keys(input,Set.of("title","body","image","cta","courseIds"));Map<String,Object> result=new LinkedHashMap<>();
        result.put("title",text(input.get("title"),200,true));result.put("body",text(input.get("body"),5000,false));
        result.put("cta",text(input.get("cta"),100,false));result.put("image",image(input.get("image")));
        if(input.containsKey("courseIds")) {
            List<String> courses=strings(input.get("courseIds"),100,120);for(String course:courses) {
                String sql="SELECT COUNT(co.id) FROM core_course co WHERE co.public_id=? AND co.deleted=0";
                if(current) sql+=" AND co.active=1";
                if(visible&&current) sql+=" AND EXISTS (SELECT p.id FROM core_commerce_product p WHERE p.course_id=co.id AND p.published=1 AND p.deleted=0)";
                require(jdbc.queryForObject(sql,Integer.class,course)==1,"模块课程不存在、已停用或未上架");
            }
            result.put("courseIds",courses);
        }
        return result;
    }
    private String image(Object raw) {
        String value=text(raw,700000,false);if(value.isEmpty()) return value;
        if(value.matches("data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+")) return value;
        try {URI uri=URI.create(value);require("https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null,"图片地址须使用HTTPS且不含账号");}
        catch(IllegalArgumentException error) {throw invalid("图片地址无效");}return value;
    }
    private void moduleKey(String key) {require(!key.isBlank()&&key.length()<=100&&!Set.of("__proto__","constructor","prototype").contains(key),"模块名称无效");}
    private String text(Object value,int max,boolean required) {
        if(value==null&&!required) return "";require(value instanceof String,"装修文字字段无效");String result=((String)value).trim();
        require(result.length()<=max&&(!required||!result.isEmpty()),"装修文字长度无效");return result;
    }
    private List<String> strings(Object value,int max,int length) {
        require(value instanceof List<?>,"装修列表无效");List<?> raw=(List<?>)value;require(raw.size()<=max,"装修列表过长");
        List<String> result=new ArrayList<>();for(Object item:raw) result.add(text(item,length,true));require(result.stream().distinct().count()==result.size(),"装修列表重复");return result;
    }
    private Map<String,Object> object(Object value) {
        require(value instanceof Map<?,?>,"装修对象无效");Map<String,Object> result=new LinkedHashMap<>();
        ((Map<?,?>)value).forEach((key,item)->{require(key instanceof String,"装修字段无效");result.put((String)key,item);});return result;
    }
    private void keys(Map<String,Object> value,Set<String> allowed) {require(allowed.containsAll(value.keySet()),"装修包含不支持的字段");}
    private void require(boolean condition,String text) {if(!condition) throw invalid(text);}
    private BusinessException invalid(String text) {return new BusinessException(ErrorCode.INVALID_INPUT,text);}
}
