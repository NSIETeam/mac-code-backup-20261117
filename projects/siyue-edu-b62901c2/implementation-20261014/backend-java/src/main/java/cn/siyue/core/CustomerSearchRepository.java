package cn.siyue.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Typed exact predicates intersect customer access with the independently authorized source. */
final class CustomerSearchRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final StudentNameIndexRepository names;
    CustomerSearchRepository(JdbcTemplate jdbc,PiiCipher cipher) {
        this.jdbc=jdbc; this.cipher=cipher; this.names=new StudentNameIndexRepository(jdbc,cipher);
    }
    ScopeSql.Clause filter(Actor actor,CustomerSearchDtos.Query input) {
        String value=input.query();
        return switch(input.searchType()) {
            case "name" -> name(actor,value);
            case "phone","email" -> contact(input.searchType(),value);
            case "customer_id" -> new ScopeSql.Clause("c.public_id=?",List.of(value));
            case "student_id" -> student(actor,new ScopeSql.Clause("s.public_id=?",List.of(value)));
            case "student_number" -> studentNumber(actor,value);
            case "opportunity_id" -> resource(actor,value,true);
            case "lead_id" -> resource(actor,value,false);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT,"检索类型无效");
        };
    }
    private ScopeSql.Clause studentNumber(Actor actor,String value) {
        if(!canReadStudents(actor))throw new BusinessException(ErrorCode.FORBIDDEN,"当前账号没有学生身份检索权限");
        return student(actor,new StudentIdentifierRepository(jdbc,cipher).numberMatches(value));
    }
    private ScopeSql.Clause name(Actor actor,String value) {
        var customer=index("ni",cipher.blindIndexes("customer_name",value.trim().replaceAll("\\s+"," ")));
        String sql="EXISTS(SELECT ni.id FROM core_customer_name_index ni WHERE ni.customer_id=c.id AND ni.deleted=0 AND ("+customer.sql()+"))";
        List<Object> args=new ArrayList<>(customer.args());
        if(canReadStudents(actor)) {
            requireReady(actor);
            var hashes=index("ni",cipher.blindIndexes("student_name",StudentNameIndexRepository.normalize(value)));
            var child=student(actor,new ScopeSql.Clause("EXISTS(SELECT ni.id FROM core_student_name_index ni WHERE ni.student_id=s.id AND ni.deleted=0 AND ni.source_version=s.version AND ("+hashes.sql()+"))",hashes.args()));
            sql+=" OR "+child.sql(); args.addAll(child.args());
        }
        return new ScopeSql.Clause("("+sql+")",List.copyOf(args));
    }
    private void requireReady(Actor actor) {
        var customer=ScopeSql.customerReads(actor); var students=student(actor,names.missing("s"));
        var args=new ArrayList<>(customer.args()); args.addAll(students.args());
        boolean pending=jdbc.queryForObject("SELECT EXISTS(SELECT c.id FROM core_customer c WHERE c.deleted=0 AND ("+customer.sql()+") AND ("+students.sql()+") LIMIT 1)",Boolean.class,args.toArray());
        if(pending)throw new BusinessException(ErrorCode.SEARCH_INDEX_NOT_READY,"学生姓名检索索引尚未就绪，请联系管理员完成索引维护后重试");
    }
    private ScopeSql.Clause contact(String channel,String value) {
        String normalized=channel.equals("phone")?value.trim().replaceAll("[\\s()\\-]",""):value.trim().toLowerCase(java.util.Locale.ROOT);
        var hashes=index("ci",cipher.blindIndexes(channel,normalized));
        var args=new ArrayList<Object>(); args.add(channel); args.addAll(hashes.args());
        return new ScopeSql.Clause("EXISTS(SELECT cc.id FROM core_customer_contact cc JOIN core_customer_contact_index ci ON ci.contact_id=cc.id AND ci.deleted=0 WHERE cc.customer_id=c.id AND cc.deleted=0 AND cc.channel=? AND ("+hashes.sql()+"))",List.copyOf(args));
    }
    private ScopeSql.Clause resource(Actor actor,String value,boolean opportunity) {
        actor.require(opportunity?"crm.opportunity.read":"crm.lead.read");
        var scope=opportunity?OpportunityNeedSupport.scope(actor):ScopeSql.leadReads(actor);
        String alias=opportunity?"o":"l",table=opportunity?"core_opportunity":"core_crm_lead";
        var args=new ArrayList<Object>(); args.add(value); args.addAll(scope.args());
        return new ScopeSql.Clause("EXISTS(SELECT "+alias+".id FROM "+table+" "+alias+" WHERE "+alias+".customer_id=c.id AND "+alias+".public_id=? AND "+alias+".deleted=0 AND ("+scope.sql()+"))",List.copyOf(args));
    }
    private ScopeSql.Clause student(Actor actor,ScopeSql.Clause predicate) {
        if(!canReadStudents(actor))throw new BusinessException(ErrorCode.FORBIDDEN,"当前账号没有学生身份检索权限");
        var scope=actor.permissions().contains("crm.opportunity.intake")?new ScopeSql.Clause("1=1",List.of()):ScopeSql.students(actor,null);
        var args=new ArrayList<>(predicate.args()); args.addAll(scope.args());
        return new ScopeSql.Clause("EXISTS(SELECT link.id FROM core_customer_student link JOIN core_student s ON s.id=link.student_id WHERE link.customer_id=c.id AND link.deleted=0 AND s.deleted=0 AND ("+predicate.sql()+") AND ("+scope.sql()+"))",List.copyOf(args));
    }
    private boolean canReadStudents(Actor actor) { return actor.permissions().contains("crm.opportunity.intake")||actor.permissions().contains("student.read"); }
    private ScopeSql.Clause index(String alias,Map<String,byte[]> hashes) {
        List<Object> args=new ArrayList<>(); List<String> terms=new ArrayList<>();
        hashes.forEach((key,hash)->{ terms.add("("+alias+".key_id=? AND "+alias+".value_hash=?)"); args.add(key); args.add(hash); });
        return new ScopeSql.Clause(String.join(" OR ",terms),List.copyOf(args));
    }
}
