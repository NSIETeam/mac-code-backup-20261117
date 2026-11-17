package cn.siyue.core;

import java.time.Instant;

final class TaskRoutingRows {
    private TaskRoutingRows() { }
    record BatchRow(long id,String publicId,long campusId,String campusPublicId,String mode,
        Long supervisorId,String supervisorPublicId,Instant startsAt,Instant dueAt,String status,long managementVersion) { }
    record TaskRow(long id,String publicId,long batchId,long customerId,Long studentId,long campusId,
        String ownerPublicId,String status,Instant dueAt,long version) { }
    record Target(long customerId,Long studentId,long campusId,CrmDtos.TaskTargetInput input) { }
}
