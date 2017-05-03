package io.gravitee.management.service;

import io.gravitee.management.model.analytics.query.LogQuery;
import io.gravitee.management.model.log.ApiRequest;
import io.gravitee.management.model.log.ApplicationRequest;
import io.gravitee.management.model.log.SearchLogResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface LogsService {

    SearchLogResponse findByApi(String api, LogQuery query);

    SearchLogResponse findByApplication(String application, LogQuery query);

    ApiRequest findApiLog(String id);

    ApplicationRequest findApplicationLog(String id);
}
