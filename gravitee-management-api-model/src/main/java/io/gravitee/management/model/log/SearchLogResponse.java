package io.gravitee.management.model.log;

import java.util.List;
import java.util.Map;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SearchLogResponse<T extends LogItem> {

    private final long total;
    private List<T> logs;
    private Map<String, Map<String, String>> metadata;

    public SearchLogResponse(long total) {
        this.total = total;
    }

    public long getTotal() {
        return total;
    }

    public List<T> getLogs() {
        return logs;
    }

    public void setLogs(List<T> logs) {
        this.logs = logs;
    }

    public Map<String, Map<String, String>> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Map<String, String>> metadata) {
        this.metadata = metadata;
    }
}
