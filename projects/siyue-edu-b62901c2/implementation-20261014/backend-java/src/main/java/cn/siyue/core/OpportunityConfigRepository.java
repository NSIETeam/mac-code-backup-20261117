package cn.siyue.core;

import static cn.siyue.core.OpportunityNeedSupport.clean;
import static cn.siyue.core.OpportunityNeedSupport.invalid;
import cn.siyue.core.OpportunityNeedDtos.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Configured classifications are stable references, never inferred from historical data. */
@Repository
public class OpportunityConfigRepository {
    private static final Set<String> KINDS=Set.of("department","project","grade","class_type","level","fiscal_year","quarter","judgment","judgment_reason");
    private static final String SELECT="SELECT x.id,x.campus_id,x.parent_id,x.public_id,x.kind,x.code,x.label,x.project_mode,x.effect,x.active,x.version,p.public_id AS parent_public_id,c.public_id AS campus_public_id FROM core_opportunity_option x LEFT JOIN core_opportunity_option p ON p.id=x.parent_id LEFT JOIN core_campus c ON c.id=x.campus_id";
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    /** 连接需求分类、层级选项与版本目录，不从旧业务文字推断分类。
     * @param jdbc 参与调用方事务的参数化访问器
     * @param cipher 校区显示名解密器
     */
    public OpportunityConfigRepository(JdbcTemplate jdbc,PiiCipher cipher) { this.jdbc=jdbc; this.cipher=cipher; }
    /** 需求配置项的内部主键、校区和父项引用及对外投影，供层级与范围校验。 */
    public record Ref(long id,Long campusId,Long parentId,Option option) { }
    /** 经配置规则校验后的部门、可选项目引用及固定需求身份显示快照。 */
    public record IdentityRefs(Ref department,Ref project,IdentityView view) { }
    /** 读取需求目录总版本，可在调用方写事务中锁定单例行。
     * @param lock 是否追加FOR UPDATE并持锁至调用方事务结束
     * @return 当前配置目录版本
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public long version(boolean lock) {
        return jdbc.queryForObject("SELECT version FROM core_opportunity_catalog WHERE id=1"+(lock?" FOR UPDATE":""),Long.class);
    }
    /** 判断是否存在启用部门，供旧需求创建路径决定是否应转用分类流程。
     * @return 至少一个启用部门存在时为true
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public boolean configured() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity_option WHERE kind='department' AND active=1",Long.class)>0;
    }
    /** 读取全局及指定校区启用选项，同时列出有效意向校区。
     * @param campus 调用方已授权的校区内部主键；null仅选全局配置
     * @return 配置总版本、范围校区、启用选项及有效校区列表
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     * @throws IllegalStateException 校区名称密文无法解密时
     */
    public Options options(Long campus) {
        List<Option> items=jdbc.query(SELECT+" WHERE x.active=1 AND (x.campus_id IS NULL OR x.campus_id=?) ORDER BY x.kind,x.id",
            (rs,n)->row(rs).option(),campus);
        String publicCampus=campus==null?null:jdbc.queryForObject("SELECT public_id FROM core_campus WHERE id=?",String.class,campus);
        List<Campus> campuses=jdbc.query("SELECT public_id,name_cipher FROM core_campus WHERE active=1 AND deleted=0 ORDER BY id",
            (rs,n)->new Campus(rs.getString(1),cipher.decrypt(rs.getString(2))));
        return new Options(version(false),publicCampus,items,campuses);
    }
    /** 按配置维护权限和all/campus范围读取选项管理页。
     * @param actor 须具crm.opportunity.config.manage的当前员工
     * @param query 可选类别、状态、校区及有界分页
     * @return 当前范围内选项和目录版本
     * @throws BusinessException 权限、范围、类别、校区或分页无效时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public OptionPage list(Actor actor,OptionQuery query) {
        int page=OpportunityNeedSupport.page(query.page()),size=OpportunityNeedSupport.size(query.size());
        List<Object> args=new ArrayList<>(); String where=managementWhere(actor,args);
        if(clean(query.kind())!=null) { kind(query.kind()); where+=" AND x.kind=?"; args.add(query.kind()); }
        if(clean(query.campusId())!=null) { where+=" AND x.campus_id=?"; args.add(campus(actor,query.campusId())); }
        if(!"all".equals(query.status())) { where+=" AND x.active=?"; args.add(!"inactive".equals(query.status())); }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_opportunity_option x WHERE "+where,Long.class,args.toArray());
        args.add(size); args.add((long)(page-1)*size);
        var items=jdbc.query(SELECT+" WHERE "+where+" ORDER BY x.id LIMIT ? OFFSET ?",(rs,n)->row(rs).option(),args.toArray());
        return new OptionPage(items,total,page,size,version(false));
    }
    private String managementWhere(Actor actor,List<Object> args) {
        actor.require("crm.opportunity.config.manage");
        if("all".equals(actor.scope()))return "1=1";
        if(!"campus".equals(actor.scope()))throw new BusinessException(ErrorCode.FORBIDDEN,"当前范围不能维护需求配置");
        args.add(actor.id());
        return "x.campus_id IN (SELECT campus_id FROM core_principal_campus WHERE principal_id=? AND deleted=0)";
    }
    /** 解析可维护的有效校区；只有all范围可选择全局空校区。
     * @param actor 已由调用方核验操作权限的员工
     * @param publicId 校区公开标识；空白代表全局
     * @return 校区内部主键，全局返回null
     * @throws BusinessException 校区不存在、停用或超出维护范围时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Long campus(Actor actor,String publicId) {
        if(clean(publicId)==null) {
            if(!"all".equals(actor.scope()))throw new BusinessException(ErrorCode.FORBIDDEN,"只有全局配置管理可维护全局选项");
            return null;
        }
        List<Long> ids=jdbc.query("SELECT id FROM core_campus WHERE public_id=? AND deleted=0 AND active=1",(rs,n)->rs.getLong(1),publicId);
        if(ids.isEmpty())OpportunityNeedSupport.missing();
        long id=ids.get(0);
        if(!"all".equals(actor.scope())&&!("campus".equals(actor.scope())&&actor.campusIds().contains(id)))
            throw new BusinessException(ErrorCode.FORBIDDEN,"校区不在配置维护范围");
        return id;
    }
    /** 按公开标识读取配置引用，不在本方法执行员工授权或启用状态检查。
     * @param publicId 配置项公开标识
     * @return 内部主键、层级和校区引用以及配置投影
     * @throws BusinessException 配置项不存在时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Ref find(String publicId) {
        List<Ref> rows=jdbc.query(SELECT+" WHERE x.public_id=?",(rs,n)->row(rs),publicId);
        if(rows.isEmpty())OpportunityNeedSupport.missing(); return rows.get(0);
    }
    /** 核验配置维护权限及该项所属校区范围。
     * @param actor 当前员工
     * @param publicId 配置项公开标识
     * @throws BusinessException 无维护权限、配置项不存在或校区不在当前范围时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public void authorize(Actor actor,String publicId) {
        actor.require("crm.opportunity.config.manage"); Ref ref=find(publicId);
        campus(actor,ref.option().campusId());
    }
    /** 在调用方已授权的事务内锁目录版本，检查层级及模式，插入选项并递增总版本。
     * @param actor 调用服务已核验配置维护权限的员工
     * @param input 配置类别、编码、名称、父项、校区及适用的项目模式/判断效果
     * @return 新选项及递增后的目录版本
     * @throws BusinessException 类别、校区、父项、项目模式或判断效果无效时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public OptionReceipt create(Actor actor,OptionCreate input) {
        version(true); kind(input.kind()); Long campus=campus(actor,input.campusId());
        Ref parent=clean(input.parentId())==null?null:find(input.parentId()); validateShape(input,parent,campus);
        String id="OPT_"+UUID.randomUUID();
        jdbc.update("INSERT INTO core_opportunity_option(public_id,kind,code,label,parent_id,campus_id,project_mode,effect) VALUES(?,?,?,?,?,?,?,?)",
            id,input.kind(),input.code(),input.label().trim(),parent==null?null:parent.id(),campus,clean(input.projectMode()),clean(input.effect()));
        bump(); return new OptionReceipt(find(id).option(),version(false));
    }
    private void validateShape(OptionCreate input,Ref parent,Long campus) {
        validateParent(input,parent,campus);
        validateProjectMode(input,parent);
        validateEffect(input);
    }
    private String expectedParent(String kind) {
        return switch(kind) { case "project" -> "department"; case "judgment_reason" -> "judgment"; case "quarter" -> "fiscal_year"; default -> null; };
    }
    private void validateParent(OptionCreate input,Ref parent,Long campus) {
        String expected=expectedParent(input.kind());
        if(expected==null&&parent!=null || expected!=null&&!"quarter".equals(input.kind())&&parent==null)invalid("选项父级无效");
        if(parent!=null&&(!parent.option().active()||!parent.option().kind().equals(expected)
                ||parent.campusId()!=null&&!Objects.equals(parent.campusId(),campus)))invalid("父选项类型或校区不一致");
    }
    private void validateProjectMode(OptionCreate input,Ref parent) {
        if("department".equals(input.kind())) {
            if(!Set.of("required","not_used").contains(String.valueOf(input.projectMode())))invalid("部门须明确是否使用项目");
        } else if(clean(input.projectMode())!=null)invalid("只有部门可设置项目模式");
        if("project".equals(input.kind())&&parent!=null&&!"required".equals(parent.option().projectMode()))invalid("该部门未启用项目");
    }
    private void validateEffect(OptionCreate input) {
        if("judgment".equals(input.kind())) {
            if(!Set.of("continue","close").contains(String.valueOf(input.effect())))invalid("商机判定只可继续跟进或关闭");
        } else if(clean(input.effect())!=null)invalid("只有判定可设置业务效果");
    }
    /** 锁目录并复核管理权限，按期望版本仅更新名称与启用状态。
     * @param actor 当前配置管理员
     * @param id 配置项公开标识
     * @param input 新名称、启用状态及期望版本
     * @return 更新后的选项及递增目录版本
     * @throws BusinessException 权限、校区范围或期望版本不匹配时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public OptionReceipt update(Actor actor,String id,OptionUpdate input) {
        version(true); authorize(actor,id);
        int changed=jdbc.update("UPDATE core_opportunity_option SET label=?,active=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE public_id=? AND version=?",
            input.label().trim(),input.active(),id,input.expectedVersion());
        if(changed!=1)OpportunityNeedSupport.stale(); bump(); return new OptionReceipt(find(id).option(),version(false));
    }
    private void bump() { jdbc.update("UPDATE core_opportunity_catalog SET version=version+1 WHERE id=1"); }
    private void kind(String value) { if(!KINDS.contains(value))invalid("需求选项类型无效"); }
    /** 按部门项目模式解析需求身份，检查项目确实属于指定部门。
     * @param input 部门及可选项目公开标识
     * @param campus 当前需求校区内部主键
     * @param allowInactive 历史复核时是否允许停用项；仍要求类型、校区及层级一致
     * @return 已核验的部门、可选项目和显示快照
     * @throws BusinessException 身份缺失、配置类型/校区/启用状态或部门项目组合无效时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public IdentityRefs identity(Identity input,Long campus,boolean allowInactive) {
        if(input==null)invalid("请先配置并选择意向部门/项目");
        Ref department=required(input.departmentId(),"department",campus,allowInactive);
        Ref project=clean(input.projectId())==null?null:required(input.projectId(),"project",campus,allowInactive);
        if("required".equals(department.option().projectMode())&&(project==null||!Objects.equals(project.parentId(),department.id())))invalid("请选择该部门下的有效项目");
        if("not_used".equals(department.option().projectMode())&&project!=null)invalid("该部门不使用项目");
        return new IdentityRefs(department,project,new IdentityView(department.option().id(),department.option().label(),
            project==null?null:project.option().id(),project==null?null:project.option().label(),department.option().projectMode()));
    }
    /** 读取必选配置，并核验精确类型、校区和启用要求。
     * @param id 非空配置公开标识
     * @param kind 要求的配置类别
     * @param campus 当前需求校区内部主键
     * @param allowInactive 是否允许当前已停用的历史引用
     * @return 通过资格校验的配置引用
     * @throws BusinessException 未选配置、不存在、类型或校区不符、禁止的停用项时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public Ref required(String id,String kind,Long campus,boolean allowInactive) {
        if(clean(id)==null)invalid("请选择已配置的需求选项"); Ref ref=find(id);
        if(!ref.option().kind().equals(kind)||ref.campusId()!=null&&!Objects.equals(campus,ref.campusId())
                ||!allowInactive&&!ref.option().active())invalid("需求选项未启用或不属于当前校区");
        return ref;
    }
    /** 校验需求档案并冻结显示名；原有相同选项可保留停用引用，新增选择要求有效。
     * @param input 完整档案替换输入；null按全部空字段处理
     * @param campus 当前需求校区内部主键
     * @param previous 之前档案快照，用于允许原停用引用和保留原意向校区名
     * @return 规范配置标识及显示名快照
     * @throws BusinessException 配置类型/校区、季度财年关系或新意向校区无效时
     * @throws org.springframework.dao.DataAccessException 数据库读取、加锁或写入失败时
     */
    public ProfileView profile(Profile input,Long campus,ProfileView previous) {
        if(input==null)input=new Profile(null,null,null,null,null,null);
        Ref grade=optional(input.gradeId(),"grade",campus,previous==null?null:previous.gradeId());
        Ref type=optional(input.classTypeId(),"class_type",campus,previous==null?null:previous.classTypeId());
        Ref level=optional(input.levelId(),"level",campus,previous==null?null:previous.levelId());
        Ref year=optional(input.fiscalYearId(),"fiscal_year",campus,previous==null?null:previous.fiscalYearId());
        Ref quarter=optional(input.quarterId(),"quarter",campus,previous==null?null:previous.quarterId());
        if(quarter!=null&&quarter.parentId()!=null&&(year==null||!quarter.parentId().equals(year.id())))invalid("季度不属于所选财年");
        String preferred=preferredCampus(input.preferredCampusId(),previous);
        return new ProfileView(id(grade),clean(input.preferredCampusId()),id(type),id(level),id(year),id(quarter),
            label(grade),preferred,label(type),label(level),label(year),label(quarter));
    }
    private String preferredCampus(String publicId,ProfileView previous) {
        if(clean(publicId)==null)return null;
        if(previous!=null&&publicId.equals(previous.preferredCampusId()))return previous.preferredCampusName();
        var names=jdbc.queryForList("SELECT name_cipher FROM core_campus WHERE public_id=? AND active=1 AND deleted=0",String.class,publicId);
        if(names.isEmpty())invalid("偏好校区无效"); return cipher.decrypt(names.get(0));
    }
    private Ref optional(String id,String kind,Long campus,String previous) {
        return clean(id)==null?null:required(id,kind,campus,id.equals(previous));
    }
    private String id(Ref ref) { return ref==null?null:ref.option().id(); }
    private String label(Ref ref) { return ref==null?null:ref.option().label(); }
    private Ref row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Ref(rs.getLong("id"),rs.getObject("campus_id",Long.class),rs.getObject("parent_id",Long.class),
            new Option(rs.getString("public_id"),rs.getString("kind"),rs.getString("code"),rs.getString("label"),
                rs.getString("parent_public_id"),rs.getString("campus_public_id"),rs.getString("project_mode"),
                rs.getString("effect"),rs.getBoolean("active"),rs.getLong("version")));
    }
}
