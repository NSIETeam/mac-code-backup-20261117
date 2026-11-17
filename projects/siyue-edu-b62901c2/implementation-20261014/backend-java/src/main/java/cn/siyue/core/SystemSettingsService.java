package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Permission-checked, typed system settings with transactional idempotency and audit. */
@Service
public class SystemSettingsService {
    static final Map<String, Map<String, String>> FIELDS = Map.of(
        "organization", Map.of("name", "text", "address", "text", "phone", "text", "contact", "text"),
        "attendance", Map.of("presentConsume", "bool", "absentConsume", "bool", "leaveConsume", "bool", "allowMakeup", "bool"),
        "move", Map.of("sameGrade", "true", "sameSubject", "true", "sameCourse", "bool", "maxDays", "integer", "requireReason", "true"),
        "transfer", Map.of("sameGrade", "true", "sameSubject", "true", "requireReason", "true"),
        "enrollment", Map.of("allowOverCapacity", "false", "requirePhone", "true"),
        "receipts", Map.of("accountName", "text", "bankName", "text", "accountNumber", "text", "receiptNote", "text"));
    /** 一个配置分类的部分字段修改。
     * @param section FIELDS 中已定义的配置分类
     * @param values 非空字段映射，字段名和类型必须符合该分类的规则
     */
    public record Input(String section, Map<String, Object> values) { }
    /** 按分类返回当前已保存的系统配置。
     * @param sections 分类到字段映射；未保存的字段不自动填充默认值
     */
    public record Settings(Map<String, Map<String, Object>> sections) { }
    private final JdbcTemplate jdbc;
    private final AccessRepository access;
    private final MutationRepository mutations;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;

    /** 装配加密配置持久化、权限及幂等依赖。
     * @param jdbc 配置查询、目录锁及配置更新使用的事务 JDBC 接口
     * @param access 当前操作人状态和授权锁读取器
     * @param mutations 幂等结果及审计仓库
     * @param cipher 配置字段值的加解密器
     * @param mapper 请求摘要与重放结果 JSON 编解码器
     */
    public SystemSettingsService(JdbcTemplate jdbc, AccessRepository access, MutationRepository mutations,
            PiiCipher cipher, ObjectMapper mapper) {
        this.jdbc = jdbc; this.access = access; this.mutations = mutations; this.cipher = cipher; this.mapper = mapper;
    }

    /** 读取已保存的配置字段，返回解密后的布尔值、整数或文本。
     * @param actor 具有 settings.read 或 settings.update 权限的当前身份
     * @return 各已定义分类的已保存字段；没有值的分类仍保留空映射
     * @throws BusinessException 当前身份缺少读取或修改设置权限时
     * @throws IllegalStateException 存储数据包含无法识别的分类或字段时
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Settings get(Actor actor) {
        actor.requireAny("settings.read", "settings.update");
        return read();
    }

    /** 更新指定分类的字段，并原子保存加密值和可重放审计结果。
     * 未包含在请求中的字段保持不变；锁定当前身份及配置目录后再持久化。
     * @param actor 具有 settings.update 权限且数据范围为 all 的身份
     * @param key 16 至 128 位幂等键，可包含字母、数字、点、下划线、冒号和短横线
     * @param input 需修改的分类及非空字段映射；文本会裁剪首尾空白，整数范围 0 至 365
     * @return 修改后全部配置，或相同请求保存的原始配置结果
     * @throws BusinessException 权限、数据范围、字段类型、安全规则或幂等检查失败时
     * @throws JsonProcessingException 请求摘要或重放结果 JSON 编解码失败时
     * @throws IllegalStateException 读取结果时发现未知的存储字段或分类时
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Settings update(Actor actor, String key, Input input) throws JsonProcessingException {
        authorize(actor);
        Input clean = validate(input);
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "幂等键格式无效");
        }
        actor = access.lockActive(actor.id()); authorize(actor);
        String hash = mutations.hash(mapper.writeValueAsString(clean));
        String replay = mutations.replay(actor, key, "settings.update", hash);
        if (replay != null) { return mapper.readValue(replay, Settings.class); }
        jdbc.queryForObject("SELECT id FROM core_campus_configuration_lock WHERE lock_name='campus-directory' FOR UPDATE", Long.class);
        for (Map.Entry<String, Object> field : clean.values().entrySet()) {
            String type = field.getValue() instanceof Boolean ? "boolean" : field.getValue() instanceof Number ? "integer" : "text";
            jdbc.update("""
                INSERT INTO core_system_setting(section,field_name,value_type,value_cipher) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE value_type=VALUES(value_type),value_cipher=VALUES(value_cipher),
                  version=version+1,deleted=0,updated_at=UTC_TIMESTAMP(3)
                """, clean.section(), field.getKey(), type, cipher.encrypt(field.getValue().toString()));
        }
        Settings result = read();
        mutations.complete(actor, new MutationRepository.RetryWrite(key, "settings.update", hash,
            mapper.writeValueAsString(result), clean.section()));
        return result;
    }

    static Input validate(Input input) {
        if (input == null || !FIELDS.containsKey(input.section() == null ? "" : input.section())
                || input.values() == null || input.values().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "设置分类或字段无效");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        input.values().forEach((name, value) -> clean.put(name, cleanValue(FIELDS.get(input.section()).get(name), value)));
        return new Input(input.section(), java.util.Collections.unmodifiableMap(clean));
    }

    private static Object cleanValue(String type, Object value) {
        if (type == null || value == null) { throw invalid(); }
        if (type.equals("text")) {
            if (!(value instanceof String text) || text.trim().length() > 500) { throw invalid(); }
            return text.trim();
        }
        if (type.equals("integer")) { return cleanInteger(value); }
        if (!(value instanceof Boolean bool) || !type.equals("bool") && bool != Boolean.parseBoolean(type)) { throw invalid(); }
        return value;
    }

    private static int cleanInteger(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < 0 || number.intValue() > 365) { throw invalid(); }
        return number.intValue();
    }

    private Settings read() {
        Map<String, Map<String, Object>> values = new LinkedHashMap<>();
        FIELDS.keySet().stream().sorted().forEach(section -> values.put(section, new LinkedHashMap<>()));
        jdbc.query("SELECT section,field_name,value_type,value_cipher FROM core_system_setting WHERE deleted=0 ORDER BY section,field_name",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String section = rs.getString(1), name = rs.getString(2), type = rs.getString(3);
                if (!FIELDS.containsKey(section) || !FIELDS.get(section).containsKey(name)) { throw new IllegalStateException("Unknown system setting"); }
                String plain = cipher.decrypt(rs.getString(4));
                Object value = type.equals("boolean") ? Boolean.valueOf(plain) : type.equals("integer") ? Integer.valueOf(plain) : plain;
                values.get(section).put(name, value);
            });
        return new Settings(values);
    }

    private static void authorize(Actor actor) {
        actor.require("settings.update");
        if (!"all".equals(actor.scope())) { throw new BusinessException(ErrorCode.FORBIDDEN, "系统设置修改需要全部数据范围"); }
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_INPUT, "设置字段类型或安全规则无效"); }
}
