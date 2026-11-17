package cn.siyue.core;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Export uses exactly the directory's current-student and teaching scope.
 * @see StudentExportRepository
 */
@Service
public class StudentExportService {
    /** Contains the scoped export rows and the applied view filters.
     * @param items student rows visible to the actor
     * @param total number of rows returned
     * @param view normalized directory view
     * @param classId selected class identifier, or null when not filtered by class
     */
    public record Export(List<AcademicDtos.Student> items,int total,String view,String classId) { }
    private final StudentExportRepository repository;
    /** Injects the student export query repository.
     * @param repository scoped student export persistence
     */
    public StudentExportService(StudentExportRepository repository) { this.repository=repository; }
    /** Exports rows from the current student directory under the actor's existing read scope.
     * @param actor authenticated actor whose student scope is applied
     * @param view requested directory view normalized by the academic read-scope rules
     * @param classId optional public class identifier filter
     * @return export rows and the normalized filters applied
     * @throws BusinessException if student-read permission is missing or the view/class filter is invalid
     */
    @Transactional(readOnly=true, rollbackFor = Exception.class)
    public Export export(Actor actor,String view,String classId) {
        actor.require("student.read");
        String normalized=AcademicReadScope.view(view);
        if(classId!=null && classId.length()>120)throw new BusinessException(ErrorCode.INVALID_INPUT,"班级编号过长");
        String selected=classId==null||classId.isBlank()?null:classId;
        var rows=repository.export(actor,new AcademicDtos.StudentQuery(normalized,selected,1,100));
        return new Export(List.copyOf(rows),rows.size(),normalized,selected);
    }
}
