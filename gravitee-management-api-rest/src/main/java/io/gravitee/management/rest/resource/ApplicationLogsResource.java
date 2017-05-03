package io.gravitee.management.rest.resource;

import io.gravitee.common.http.MediaType;
import io.gravitee.management.model.analytics.query.LogQuery;
import io.gravitee.management.model.log.ApplicationRequest;
import io.gravitee.management.model.log.SearchLogResponse;
import io.gravitee.management.model.permissions.ApplicationPermission;
import io.gravitee.management.rest.resource.param.LogsParam;
import io.gravitee.management.rest.security.ApplicationPermissionsRequired;
import io.gravitee.management.service.LogsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import javax.inject.Inject;
import javax.ws.rs.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@ApplicationPermissionsRequired(ApplicationPermission.ANALYTICS)
@Api(tags = {"Application"})
public class ApplicationLogsResource extends AbstractResource {

    @Inject
    private LogsService logsService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get application logs")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Application logs"),
            @ApiResponse(code = 500, message = "Internal server error")})
    public SearchLogResponse applicationLogs(
            @PathParam("application") String application,
            @BeanParam LogsParam param) {

        param.validate();

        LogQuery logQuery = new LogQuery();
        logQuery.setQuery(param.getQuery());
        logQuery.setPage(param.getPage());
        logQuery.setSize(param.getSize());
        logQuery.setFrom(param.getFrom());
        logQuery.setTo(param.getTo());

        return logsService.findByApplication(application, logQuery);
    }

    @GET
    @Path("/{log}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get a specific log")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Single log"),
            @ApiResponse(code = 500, message = "Internal server error")})
    public ApplicationRequest applicationLog(
            @PathParam("application") String application,
            @PathParam("log") String logId) {
        return logsService.findApplicationLog(logId);
    }
}
