package cn.siyue.core;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One scoped query exports the complete authorized directory with masked phone numbers. */
@Repository
public class StudentExportRepository {
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public StudentExportRepository(JdbcTemplate jdbc,PiiCipher cipher) { this.jdbc=jdbc;this.cipher=cipher; }
    public List<AcademicDtos.Student> export(Actor actor,AcademicDtos.StudentQuery query) {
        ScopeSql.Clause scope=AcademicReadScope.students(actor,query);
        return jdbc.query("SELECT s.public_id,s.name_cipher,s.grade,s.phone_cipher,s.version FROM core_student s WHERE "
            +scope.sql()+" ORDER BY s.id DESC",(rs,row)->new AcademicDtos.Student(rs.getString("public_id"),
                cipher.decrypt(rs.getString("name_cipher")),rs.getString("grade"),
                cipher.maskedPhone(rs.getString("phone_cipher")),rs.getLong("version")),scope.args().toArray());
    }
}
