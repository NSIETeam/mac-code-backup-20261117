package cn.siyue.core;

import cn.siyue.core.AcademicDtos.*;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads retained class membership under the current class scope; contact output stays masked. */
final class ClassStudentRepository {
    /** 班级学员名单的数据库行，姓名电话保持密文并携带报名状态与版本。 */
    private record SavedStudent(String id,String nameCipher,String phoneCipher,String grade,String enrollmentId,String status,long version) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    ClassStudentRepository(JdbcTemplate jdbc,PiiCipher cipher) { this.jdbc=jdbc;this.cipher=cipher; }
    /** 名单分组后的符合查询学员与活跃、历史数量，供分页和筛选统计使用。 */
    private record Membership(List<ClassStudent> matched,long activeCount,long historyCount) { }
    ClassStudentPage students(Actor actor,String classId,ClassStudentQuery query) {
        Membership result=membership(actor,classId,query.membership(),query.search());
        List<ClassStudent> matched=result.matched();
        int page=query.page()==null ? 1 : query.page(),size=query.size()==null ? 20 : query.size();
        long offset=Math.multiplyExact((long)page-1,size);
        List<ClassStudent> items=offset>=matched.size() ? List.of() : List.copyOf(matched.subList((int)offset,(int)Math.min(offset+size,matched.size())));
        return new ClassStudentPage(items,matched.size(),page,size,result.activeCount(),result.historyCount(),classId);
    }
    ClassStudentExport exportStudents(Actor actor,String classId,ClassStudentExportQuery query) {
        Membership result=membership(actor,classId,query.membership(),query.search());
        return new ClassStudentExport(classId,query.membership()==null ? "active" : query.membership(),
            query.search()==null ? "" : query.search(),result.matched(),result.matched().size());
    }
    private Membership membership(Actor actor,String classId,String membership,String searchValue) {
        long internalClass=authorizedClass(actor,classId);
        List<SavedStudent> rows=jdbc.query("""
            SELECT st.public_id,st.name_cipher,st.phone_cipher,st.grade,e.public_id enrollment_public_id,e.status,e.version
            FROM core_effective_enrollment e JOIN core_student st ON st.id=e.student_id
            WHERE e.class_id=? AND e.deleted=0 AND st.deleted=0 ORDER BY e.id DESC
            """,(rs,row)->new SavedStudent(rs.getString("public_id"),rs.getString("name_cipher"),rs.getString("phone_cipher"),
                rs.getString("grade"),rs.getString("enrollment_public_id"),rs.getString("status"),rs.getLong("version")),internalClass);
        var active=new LinkedHashMap<String,ClassStudent>();var history=new LinkedHashMap<String,ClassStudent>();
        String search=searchValue==null ? "" : searchValue.trim().toLowerCase(Locale.ROOT);
        for(SavedStudent row:rows) {
            String name=cipher.decrypt(row.nameCipher());
            if(!matches(row,name,search))continue;
            var target="active".equals(row.status()) ? active : history;
            target.putIfAbsent(row.id(),new ClassStudent(row.id(),name,row.grade(),cipher.maskedPhone(row.phoneCipher()),row.enrollmentId(),row.status(),row.version()));
        }
        List<ClassStudent> matched=List.copyOf(("history".equals(membership) ? history : active).values());
        return new Membership(matched,active.size(),history.size());
    }

    private boolean matches(SavedStudent row,String name,String search) {
        if(search.isEmpty() || row.id().toLowerCase(Locale.ROOT).contains(search) || name.toLowerCase(Locale.ROOT).contains(search))return true;
        if(row.phoneCipher()==null || row.phoneCipher().isBlank())return false;
        return cipher.decrypt(row.phoneCipher()).contains(search);
    }
    private long authorizedClass(Actor actor,String classId) {
        ScopeSql.Clause scope=ScopeSql.classes(actor);
        List<Object> args=new ArrayList<>(scope.args());args.add(classId);
        List<Long> ids=jdbc.query("SELECT c.id FROM core_class c WHERE c.deleted=0 AND c.active=1 AND ("+scope.sql()+") AND c.public_id=?",
            (rs,row)->rs.getLong(1),args.toArray());
        if(ids.size()!=1)throw new BusinessException(ErrorCode.NOT_FOUND,"班级不存在或不在当前授权范围");
        return ids.get(0);
    }
}
