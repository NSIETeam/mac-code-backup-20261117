package cn.siyue.core;

import cn.siyue.core.CustomerSearchDtos.NameIndexRebuild;
import cn.siyue.core.CustomerSearchDtos.NameIndexStatus;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded maintenance decrypts only a locked batch; searches compare versioned keyed hashes. */
@Repository
public class StudentNameIndexRepository {
    private record StudentRow(long id,String publicId,String name,long version,boolean deleted) { }
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    public StudentNameIndexRepository(JdbcTemplate jdbc,PiiCipher cipher) { this.jdbc=jdbc; this.cipher=cipher; }
    /** Must run in the transaction that creates or changes the encrypted student name. */
    public void replace(String studentPublicId,String name) {
        jdbc.update("UPDATE core_student_name_index SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE student_id=(SELECT id FROM core_student WHERE public_id=?)",studentPublicId);
        cipher.blindIndexes("student_name",normalize(name)).forEach((key,hash)->jdbc.update("""
            INSERT INTO core_student_name_index(student_id,key_id,value_hash,source_version)
            SELECT id,?,?,version FROM core_student WHERE public_id=? AND deleted=0
            ON DUPLICATE KEY UPDATE value_hash=VALUES(value_hash),source_version=VALUES(source_version),deleted=0,updated_at=UTC_TIMESTAMP(3)
            """,key,hash,studentPublicId));
    }
    static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
    ScopeSql.Clause missing(String alias) {
        List<Object> args=new ArrayList<>(); List<String> terms=new ArrayList<>();
        for(String key:cipher.blindIndexes("student_name","").keySet()) {
            terms.add("NOT EXISTS (SELECT ni.id FROM core_student_name_index ni WHERE ni.student_id="+alias+".id AND ni.key_id=? AND ni.source_version="+alias+".version AND ni.deleted=0)");
            args.add(key);
        }
        return new ScopeSql.Clause("("+String.join(" OR ",terms)+")",List.copyOf(args));
    }
    public NameIndexStatus status() {
        var missing=missing("s");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM core_student WHERE deleted=0",Long.class);
        long pending=jdbc.queryForObject("SELECT COUNT(*) FROM core_student s WHERE s.deleted=0 AND "+missing.sql(),Long.class,missing.args().toArray());
        return new NameIndexStatus(total,total-pending,pending,cipher.blindIndexes("student_name","").size(),1);
    }
    /** Caller checks current all-scope maintenance permission and owns the transaction. */
    public NameIndexRebuild rebuild(CustomerSearchDtos.Rebuild input) {
        var rows=jdbc.query("SELECT id,public_id,name_cipher,version,deleted FROM core_student WHERE id>? ORDER BY id LIMIT ? FOR UPDATE",
            (rs,n)->new StudentRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getBoolean(5)),input.afterId(),input.limit());
        for(StudentRow row:rows) {
            if(row.deleted())jdbc.update("UPDATE core_student_name_index SET deleted=1,updated_at=UTC_TIMESTAMP(3) WHERE student_id=?",row.id());
            else replace(row.publicId(),cipher.decrypt(row.name()));
        }
        long next=rows.isEmpty()?input.afterId():rows.get(rows.size()-1).id();
        boolean more=jdbc.queryForObject("SELECT EXISTS(SELECT id FROM core_student WHERE id>? LIMIT 1)",Boolean.class,next);
        return new NameIndexRebuild(rows.size(),next,more,status());
    }
}
