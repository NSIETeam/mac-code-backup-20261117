package cn.siyue.core;

import java.util.List;
import java.util.Map;

/** Normalized import rows shared by preview and the transactional commit pass. */
record CrmImportPlan(List<String> columns, List<CrmImportLine> rows,
        java.util.Map<String, List<String>> dictionaries) { }

record CrmImportLine(int rowNumber, Map<String, String> data, List<String> errors,
        String category, String customerId, String leadId, String touchpointId) {
    CrmImportLine withOutcome(String nextCategory, String nextCustomerId, String nextLeadId,
            String nextTouchpointId) {
        return new CrmImportLine(rowNumber, data, errors, nextCategory,
            nextCustomerId, nextLeadId, nextTouchpointId);
    }
}
