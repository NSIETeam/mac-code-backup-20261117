package cn.siyue.core;

import cn.siyue.core.StudentIdentifierDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Encrypted external numbers never substitute for or merge the canonical student identity. */
@Repository
public class StudentIdentifierRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** 连接学员外部编号密文、按密钥版本的盲索引与注册表锁。
     * @param jdbc 参与调用方事务的数据库访问器
     * @param cipher 来源和编号加解密以及多版本HMAC索引器
     */
    public StudentIdentifierRepository(JdbcTemplate jdbc, PiiCipher cipher) { this.jdbc=jdbc; this.cipher=cipher; }

    /** A shared child reveals no other household membership or contact. */
    /** 核验指定客户与孩子的有效关系，不披露孩子其他家庭关联。
     * @param actor 已由服务核验读写权限的员工
     * @param target 客户与学员公开标识组合
     * @param write true使用客户写范围并锁定记录；false按客户读取及适用学员范围查询
     * @return 已授权学员内部引用和当前版本
     * @throws BusinessException 学员、客户关系不存在或范围无效时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Link authorize(Actor actor, Target target, boolean write) {
        ScopeSql.Clause scope=write?ScopeSql.customers(actor):ScopeSql.customerReads(actor);
        ScopeSql.Clause students=write||actor.permissions().contains("crm.opportunity.intake")
            ?new ScopeSql.Clause("1=1",List.of()):ScopeSql.students(actor,null);
        List<Object> args=new ArrayList<>(List.of(target.studentId(),target.customerId()));
        args.addAll(scope.args()); args.addAll(students.args());
        List<Link> rows=jdbc.query("""
            SELECT s.id,s.public_id,s.version FROM core_customer c
            JOIN core_student s ON s.public_id=? AND s.deleted=0
            WHERE c.public_id=? AND c.deleted=0 AND EXISTS (
                SELECT cs.id FROM core_customer_student cs
                WHERE cs.customer_id=c.id AND cs.student_id=s.id AND cs.deleted=0)
            AND (
            """+scope.sql()+") AND ("+students.sql()+")"+(write?" FOR UPDATE":""),
            (rs,n)->new Link(rs.getLong("id"),rs.getString("public_id"),rs.getLong("version")),args.toArray());
        if(rows.isEmpty())missing(); return rows.get(0);
    }
    /** 列出已授权学员的外部编号，按活动状态过滤并解密显示。
     * @param link authorize返回的客户关系授权凭据
     * @param query 由服务验证的active/inactive/all与分页
     * @return 外部编号页及学员当前授权版本
     * @throws BusinessException 历史密文所需密钥不可用时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Page list(Link link, Query query) {
        String filter=switch(query.status()){case "active"->" AND i.active=1";case "inactive"->" AND i.active=0";default->"";};
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_student_identifier i WHERE i.student_id=? AND i.deleted=0"+filter,Long.class,link.studentId());
        List<Identifier> items=jdbc.query(select()+" WHERE i.student_id=? AND i.deleted=0"+filter+" ORDER BY i.id DESC LIMIT ? OFFSET ?",
            this::map,link.studentId(),query.size(),((long)query.page()-1)*query.size());
        return new Page(items,total==null?0:total,query.page(),query.size(),link.studentVersion());
    }
    /** 读取已授权学员的指定外部编号，不允许借编号跨学员读取。
     * @param link 已授权学员引用
     * @param id 外部编号记录的公开标识
     * @return 解密的来源和原编号以及状态版本
     * @throws BusinessException 记录不存在、不属于该学员或密钥不可用时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Identifier get(Link link, String id) {
        List<Identifier> rows=jdbc.query(select()+" WHERE i.public_id=? AND i.student_id=? AND i.deleted=0",
            this::map,id,link.studentId());
        if(rows.isEmpty())missing(); return rows.get(0);
    }
    /** 锁定编号注册表，在索引完整和学员版本一致时插入原文编号及多密钥盲索引。
     * @param actor 已完成写授权并锁定的员工
     * @param link authorize以write=true核验并锁定的学员
     * @param input 已验证的原文来源、原文编号及学员期望版本
     * @return 新外部编号记录；不合并或替换学员主身份
     * @throws BusinessException 索引未就绪、版本变化或来源编号组合已存在时
     * @throws IllegalStateException 插入未返回记录或加密失败时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Identifier create(Actor actor, Link link, Create input) {
        lockRegistry(); ensureSearchable();
        if(link.studentVersion()!=input.expectedStudentVersion())stale();
        Map<String,byte[]> identities=cipher.exactBlindIndexes("student_identity",identity(input.namespace(),input.value()));
        ScopeSql.Clause match=indexMatch("ix.identity_hash",identities);
        Long duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM core_student_identifier_index ix WHERE ix.active=1 AND ("+match.sql()+")",Long.class,match.args().toArray());
        if(duplicate!=null&&duplicate>0)duplicate();
        String publicId="SID_"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_student_identifier(public_id,student_id,namespace_cipher,value_cipher,created_by)
            VALUES(?,?,?,?,?)
            """,publicId,link.studentId(),cipher.encrypt(input.namespace()),cipher.encrypt(input.value()),actor.id());
        Long row=jdbc.queryForObject("SELECT id FROM core_student_identifier WHERE public_id=?",Long.class,publicId);
        if(row==null)throw new IllegalStateException("Identifier insert returned no row");
        putIndexes(row,input.namespace(),input.value(),true); changed(); return get(link,publicId);
    }
    /** 按期望版本停用编号及全部对应索引，保留原文和停用审计字段。
     * @param actor 已完成写授权并锁定的员工
     * @param link 已锁定的学员关系引用
     * @param id 该学员的外部编号公开标识
     * @param input 期望编号版本及停用原因
     * @return 停用后的编号；同版本已停用记录直接返回
     * @throws BusinessException 记录不可访问、期望版本不符或解密密钥不可用时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Identifier deactivate(Actor actor, Link link, String id, Deactivate input) {
        lockRegistry(); Identifier current=get(link,id);
        if(current.version()!=input.expectedVersion())stale();
        if(!current.active())return current;
        int changed=jdbc.update("""
            UPDATE core_student_identifier SET active=0,version=version+1,updated_at=UTC_TIMESTAMP(3),
                deactivated_at=UTC_TIMESTAMP(3),deactivated_by=?,deactivation_reason_cipher=?
            WHERE public_id=? AND student_id=? AND version=? AND active=1 AND deleted=0
            """,actor.id(),cipher.encrypt(input.reason()),id,link.studentId(),input.expectedVersion());
        if(changed!=1)stale();
        jdbc.update("UPDATE core_student_identifier_index ix JOIN core_student_identifier i ON i.id=ix.identifier_id SET ix.active=0 WHERE i.public_id=?",id);
        changed(); return get(link,id);
    }
    /** Matches exact original numbers across namespaces; caller adds authorized customer/student relations. */
    /** 构造跨来源的原编号精确匹配条件；调用方必须另加客户/学员范围。
     * @param raw 不经模糊归一化的原始编号，最多120字符且无首尾空白或控制字符
     * @return 针对学员别名s的EXISTS条件及绑定参数
     * @throws BusinessException 原文格式无效或存在无法查询的活动历史索引时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public ScopeSql.Clause numberMatches(String raw) {
        exact(raw,120); ensureSearchable();
        ScopeSql.Clause match=indexMatch("ix.number_hash",cipher.exactBlindIndexes("student_number",raw));
        return new ScopeSql.Clause("EXISTS (SELECT si.id FROM core_student_identifier si "
            +"JOIN core_student_identifier_index ix ON ix.identifier_id=si.id "
            +"WHERE si.student_id=s.id AND si.deleted=0 AND si.active=1 AND ix.active=1 AND ("+match.sql()+"))",match.args());
    }
    /** Missing key coverage is observable; queries never silently treat unavailable historical indexes as no match. */
    /** 统计当前密钥集合的完整覆盖及活动记录可查询性，不把密钥缺失当成无匹配。
     * @return 总编号数、全当前密钥覆盖数、待补建数和完全不可查询活动数
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public IndexStatus indexStatus() {
        List<String> keys=keyIds();String placeholders=String.join(",",java.util.Collections.nCopies(keys.size(),"?"));
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_student_identifier WHERE deleted=0",Long.class);
        List<Object> args=new ArrayList<>(keys);args.add(keys.size());
        Long indexed=jdbc.queryForObject("SELECT COUNT(*) FROM core_student_identifier i WHERE i.deleted=0 AND "
            +"(SELECT COUNT(*) FROM core_student_identifier_index ix WHERE ix.identifier_id=i.id AND ix.key_id IN ("+placeholders+"))=?",Long.class,args.toArray());
        Long missing=jdbc.queryForObject("SELECT COUNT(*) FROM core_student_identifier i WHERE i.deleted=0 AND i.active=1 "
            +"AND NOT EXISTS (SELECT ix.id FROM core_student_identifier_index ix WHERE ix.identifier_id=i.id AND ix.active=1 AND ix.key_id IN ("+placeholders+"))",Long.class,keys.toArray());
        long count=total==null?0:total,ready=indexed==null?0:indexed;
        return new IndexStatus(count,ready,count-ready,missing==null?0:missing);
    }
    /** Invoked only by the all-scope maintenance transaction; cursor batches retain immutable number meaning. */
    /** 由全局维护事务按游标补建索引并用当前密钥重包原文，保持来源和编号字节意义。
     * @param afterId 上次已处理的内部主键，初次为0
     * @param limit 本次最多处理的源记录数，1至200；实际写行另受当前密钥数影响
     * @return 处理数、末尾游标、是否有后续及更新后的覆盖统计
     * @throws BusinessException 游标/批量无效、历史密钥缺失或编号组合冲突时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public RebuildResult rebuildIndexes(long afterId, int limit) {
        if(afterId<0||limit<1||limit>200)invalid(); lockRegistry();
        List<Stored> rows=jdbc.query("SELECT id,namespace_cipher,value_cipher,active FROM core_student_identifier WHERE deleted=0 AND id>? ORDER BY id LIMIT ?",
            (rs,n)->new Stored(rs.getLong("id"),rs.getString("namespace_cipher"),rs.getString("value_cipher"),rs.getBoolean("active")),afterId,limit+1);
        boolean more=rows.size()>limit;long cursor=afterId;int processed=0;
        for(Stored row:rows.subList(0,Math.min(rows.size(),limit))){
            String namespace=decrypt(row.namespace()),value=decrypt(row.value());
            putIndexes(row.id(),namespace,value,row.active());
            jdbc.update("UPDATE core_student_identifier SET namespace_cipher=?,value_cipher=? WHERE id=?",
                cipher.encrypt(namespace),cipher.encrypt(value),row.id());
            cursor=row.id();processed++;
        }
        if(processed>0)changed(); return new RebuildResult(processed,cursor,more,indexStatus());
    }
    private void ensureSearchable() { if(indexStatus().missingSearchable()>0)notReady(); }
    private List<String> keyIds(){return List.copyOf(cipher.exactBlindIndexes("student_number","").keySet());}
    private void lockRegistry(){jdbc.queryForObject("SELECT revision FROM core_student_identifier_registry WHERE id=1 FOR UPDATE",Long.class);}
    private void changed(){jdbc.update("UPDATE core_student_identifier_registry SET revision=revision+1 WHERE id=1");}
    private void putIndexes(long row, String namespace, String value, boolean active) {
        Map<String,byte[]> numbers=cipher.exactBlindIndexes("student_number",value);
        Map<String,byte[]> identities=cipher.exactBlindIndexes("student_identity",identity(namespace,value));
        try { for(String key:numbers.keySet()) {
            Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM core_student_identifier_index WHERE identifier_id=? AND key_id=?",Long.class,row,key);
            if(exists!=null&&exists>0)jdbc.update("UPDATE core_student_identifier_index SET number_hash=?,identity_hash=?,active=? WHERE identifier_id=? AND key_id=?",
                numbers.get(key),identities.get(key),active,row,key);
            else jdbc.update("INSERT INTO core_student_identifier_index(identifier_id,key_id,number_hash,identity_hash,active) VALUES(?,?,?,?,?)",
                row,key,numbers.get(key),identities.get(key),active);
        }}catch(DuplicateKeyException error){duplicate();}
    }
    private ScopeSql.Clause indexMatch(String column, Map<String,byte[]> indexes) {
        List<String> conditions=new ArrayList<>();List<Object> args=new ArrayList<>();
        indexes.forEach((key,value)->{conditions.add("(ix.key_id=? AND "+column+"=?)");args.add(key);args.add(value);});
        return new ScopeSql.Clause(String.join(" OR ",conditions),List.copyOf(args));
    }
    private String select(){return "SELECT i.public_id,i.namespace_cipher,i.value_cipher,i.active,i.version,i.created_at,i.deactivated_at,s.public_id AS student_public_id FROM core_student_identifier i JOIN core_student s ON s.id=i.student_id AND s.deleted=0";}
    private Identifier map(ResultSet rs,int row)throws SQLException {
        Timestamp ended=rs.getTimestamp("deactivated_at");
        return new Identifier(rs.getString("public_id"),rs.getString("student_public_id"),decrypt(rs.getString("namespace_cipher")),
            decrypt(rs.getString("value_cipher")),rs.getBoolean("active"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),ended==null?null:ended.toInstant());
    }
    private String decrypt(String value){try{return cipher.decrypt(value);}catch(IllegalStateException error){notReady();return null;}}
    static void exact(String value,int max){
        if(value==null||value.isBlank()||value.length()>max||!value.equals(value.strip())||value.codePoints().anyMatch(c->Character.isISOControl(c))
                ||Character.isSpaceChar(value.codePointAt(0))||Character.isSpaceChar(value.codePointBefore(value.length())))invalid();
    }
    private static String identity(String namespace,String value){return namespace.length()+":"+namespace+value.length()+":"+value;}
    private static void missing(){throw new BusinessException(ErrorCode.NOT_FOUND,"学生或学号记录不存在或不在当前授权范围");}
    private static void stale(){throw new BusinessException(ErrorCode.PREVIEW_STALE,"学生或学号版本已变化，请重新核对");}
    private static void duplicate(){throw new BusinessException(ErrorCode.ALREADY_EXISTS,"该来源与学号组合已备案，请核对原记录");}
    private static void invalid(){throw new BusinessException(ErrorCode.INVALID_INPUT,"学号、来源或分页参数无效，请保留原文并去除首尾空白及控制字符");}
    private static void notReady(){throw new BusinessException(ErrorCode.SEARCH_INDEX_NOT_READY,"学号索引或密钥未就绪，请保留旧密钥并完成索引轮换后重试");}
    /** 索引轮换读取的原编号持久化行；来源和值仍为密文，active决定索引可匹配状态。
     * @param id 编号记录内部主键
     * @param namespace 来源密文
     * @param value 编号密文
     * @param active 当前是否启用
     */
    private record Stored(long id,String namespace,String value,boolean active) { }
}
