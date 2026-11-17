package cn.siyue.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Parameterized MySQL persistence for encrypted administrator feedback. */
@Repository
public class UiAnnotationRepository {
    private static final String COLUMNS="a.public_id,a.status,a.version,a.payload_cipher,"
        +"p.public_id AS creator,a.created_at,a.updated_at,a.created_by_name_cipher,p.display_name_cipher";
    private static final String FROM=" FROM core_ui_annotation a JOIN core_principal p ON p.id=a.created_by ";
    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final ObjectMapper mapper;
    /** Receives the same encryption keyring as other core business repositories. */
    public UiAnnotationRepository(JdbcTemplate jdbc,PiiCipher cipher,ObjectMapper mapper) {
        this.jdbc=jdbc;this.cipher=cipher;this.mapper=mapper;
    }
    /** Lists the requested status in stable reverse chronological order. */
    public UiAnnotationDtos.Page list(String status,int page,int size) {
        String where=" WHERE a.deleted=0 AND a.status=?";
        Long total=jdbc.queryForObject("SELECT COUNT(a.id)"+FROM+where,Long.class,status);
        List<UiAnnotationDtos.View> rows=jdbc.query("SELECT "+COLUMNS+FROM+where
            +" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",this::map,status,size,(long)(page-1)*size);
        return new UiAnnotationDtos.Page(rows,total==null?0:total,page,size);
    }
    /** Creates an original immutable request; no plaintext feedback goes into audit. */
    public UiAnnotationDtos.View create(Actor actor,UiAnnotationDtos.Create input) throws JsonProcessingException {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO core_ui_annotation(public_id,category,payload_cipher,created_by) VALUES(?,?,?,?)",
            id,input.category(),cipher.encrypt(mapper.writeValueAsString(input)),actor.id());
        return get(id,false);
    }
    /** Locks one annotation for optimistic status transition or reads a single current version. */
    public UiAnnotationDtos.View get(String id,boolean lock) {
        var rows=jdbc.query("SELECT "+COLUMNS+FROM+" WHERE a.public_id=? AND a.deleted=0"
            +(lock?" FOR UPDATE":""),this::map,id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"修改建议不存在");
        return rows.get(0);
    }
    /** Updates status only; original issue and flow definitions remain unchanged. */
    public UiAnnotationDtos.View update(String id,UiAnnotationDtos.Update input) {
        int changed=jdbc.update("UPDATE core_ui_annotation SET status=?,version=version+1 "
            +"WHERE public_id=? AND version=? AND deleted=0",input.status(),id,input.expectedVersion());
        if(changed!=1)throw new BusinessException(ErrorCode.ALREADY_EXISTS,"修改建议版本已变化");
        return get(id,false);
    }
    private String creatorName(ResultSet rs) throws SQLException {
        String encrypted=rs.getString("created_by_name_cipher");
        if(encrypted==null)encrypted=rs.getString("display_name_cipher");
        return encrypted==null?rs.getString("creator"):cipher.decrypt(encrypted);
    }
    private UiAnnotationDtos.View map(ResultSet rs,int index) throws SQLException {
        try {
            return new UiAnnotationDtos.View(rs.getString("public_id"),rs.getString("status"),rs.getLong("version"),
                rs.getString("creator"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
                mapper.readValue(cipher.decrypt(rs.getString("payload_cipher")),UiAnnotationDtos.Create.class),creatorName(rs));
        } catch(JsonProcessingException error) {throw new SQLException("Stored annotation payload is invalid",error);}
    }
}
