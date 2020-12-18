/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.management.rest.resource;

import io.gravitee.common.data.domain.Page;
import io.gravitee.common.http.MediaType;
import io.gravitee.rest.api.management.rest.model.Pageable;
import io.gravitee.rest.api.management.rest.model.Subscription;
import io.gravitee.rest.api.management.rest.model.SubscriptionEntityPage;
import io.gravitee.rest.api.management.rest.resource.param.ListStringParam;
import io.gravitee.rest.api.management.rest.resource.param.ListSubscriptionStatusParam;
import io.gravitee.rest.api.management.rest.security.Permission;
import io.gravitee.rest.api.management.rest.security.Permissions;
import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.model.parameters.Key;
import io.gravitee.rest.api.model.parameters.ParameterReferenceType;
import io.gravitee.rest.api.model.permissions.RolePermission;
import io.gravitee.rest.api.model.permissions.RolePermissionAction;
import io.gravitee.rest.api.model.subscription.SubscriptionQuery;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.validator.CustomApiKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Tag(name = "API Subscriptions")
public class ApiSubscriptionsResource extends AbstractResource {

    @Inject
    private SubscriptionService subscriptionService;

    @Inject
    private ApplicationService applicationService;

    @Inject
    private PlanService planService;

    @Context
    private ResourceContext resourceContext;

    @Inject
    private UserService userService;

    @Inject
    private ApiKeyService apiKeyService;

    @Inject
    private ParameterService parameterService;

    @SuppressWarnings("UnresolvedRestParam")
    @PathParam("api")
    @Parameter(name = "api", hidden = true)
    private String api;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List subscriptions for the API", description = "User must have the READ_SUBSCRIPTION permission to use this service")
    @ApiResponse(responseCode = "200", description = "Paged result of API's subscriptions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SubscriptionEntityPage.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error")
    @Permissions({
            @Permission(value = RolePermission.API_SUBSCRIPTION, acls = RolePermissionAction.READ)
    })
    public SubscriptionEntityPage getApiSubscriptions(
            @BeanParam SubscriptionParam subscriptionParam,
            @Valid @BeanParam Pageable pageable,
            @Parameter(description = "Expansion of data to return in subscriptions",
                    array = @ArraySchema( schema = @Schema(allowableValues = "keys")))
            @QueryParam("expand") List<String> expand) {
        // Transform query parameters to a subscription query
        SubscriptionQuery subscriptionQuery = subscriptionParam.toQuery();
        subscriptionQuery.setApi(api);

        Page<SubscriptionEntity> subscriptions = subscriptionService
                .search(subscriptionQuery, pageable.toPageable());

        if (expand != null && !expand.isEmpty()) {
            for (String e : expand) {
                switch (e) {
                    case "keys":
                        subscriptions.getContent().forEach(subscriptionEntity -> {
                            final List<String> keys = apiKeyService.findBySubscription(subscriptionEntity.getId())
                                    .stream()
                                    .filter(apiKeyEntity -> !apiKeyEntity.isExpired() && !apiKeyEntity.isRevoked())
                                    .map(ApiKeyEntity::getKey)
                                    .collect(Collectors.toList());
                            subscriptionEntity.setKeys(keys);
                        });
                        break;
                    default: break;
                }
            }
        }

        SubscriptionEntityPage result = new SubscriptionEntityPage(subscriptions, pageable.getSize());
        result.setMetadata(subscriptionService.getMetadata(subscriptions.getContent()).getMetadata());
        return result;
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Subscribe to a plan", description = "User must have the MANAGE_SUBSCRIPTIONS permission to use this service")
    @ApiResponse(responseCode = "201", description = "Subscription successfully created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Subscription.class)))
    @ApiResponse(responseCode = "400", description = "Bad custom API Key format or custom API Key definition disabled")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    @Permissions({
            @Permission(value = RolePermission.API_SUBSCRIPTION, acls = RolePermissionAction.CREATE)
    })
    public Response createSubscriptionToApi(
            @Parameter(name = "application", required = true)
            @NotNull @QueryParam("application") String application,
            @Parameter(name = "plan", required = true)
            @NotNull @QueryParam("plan") String plan,
            @Parameter(name = "customApiKey")
            @CustomApiKey @QueryParam("customApiKey") String customApiKey) {

        if (StringUtils.isNotEmpty(customApiKey)  &&
                !parameterService.findAsBoolean(Key.PLAN_SECURITY_APIKEY_CUSTOM_ALLOWED, ParameterReferenceType.ENVIRONMENT)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("You are not allowed to provide a custom API Key")
                    .build();
        }

        NewSubscriptionEntity newSubscriptionEntity = new NewSubscriptionEntity(plan, application);
        // Create subscription
        SubscriptionEntity subscription = subscriptionService.create(newSubscriptionEntity, customApiKey);

        if (subscription.getStatus() == SubscriptionStatus.PENDING) {
            ProcessSubscriptionEntity process = new ProcessSubscriptionEntity();
            process.setId(subscription.getId());
            process.setAccepted(true);
            process.setStartingAt(new Date());
            process.setCustomApiKey(customApiKey);
            subscription = subscriptionService.process(process, getAuthenticatedUser());
        }

        return Response
                .created(this.getRequestUriBuilder().path(subscription.getId()).replaceQueryParam("application", null).replaceQueryParam("plan", null).build())
                .entity(convert(subscription))
                .build();
    }

    @GET
    @Path("export")
    @Produces("text/csv")
    @Operation(summary = "Export API logs as CSV")
    @ApiResponse(responseCode = "200", description = "API logs as CSV", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "500", description = "Internal server error")
    @Permissions({@Permission(value = RolePermission.API_LOG, acls = RolePermissionAction.READ)})
    public Response exportApiSubscriptionsLogsAsCSV(
            @BeanParam SubscriptionParam subscriptionParam,
            @Valid @BeanParam Pageable pageable) {
        final SubscriptionEntityPage subscriptions = getApiSubscriptions(subscriptionParam, pageable, null);
        return Response
                .ok(subscriptionService.exportAsCsv(subscriptions.getData(), subscriptions.getMetadata()))
                .header(HttpHeaders.CONTENT_DISPOSITION, format("attachment;filename=subscriptions-%s-%s.csv", api, System.currentTimeMillis()))
                .build();
    }

    @Path("{subscription}")
    public ApiSubscriptionResource getApiSubscriptionResource() {
        return resourceContext.getResource(ApiSubscriptionResource.class);
    }

    private Subscription convert(SubscriptionEntity subscriptionEntity) {
        Subscription subscription = new Subscription();

        subscription.setId(subscriptionEntity.getId());
        subscription.setCreatedAt(subscriptionEntity.getCreatedAt());
        subscription.setUpdatedAt(subscriptionEntity.getUpdatedAt());
        subscription.setStartingAt(subscriptionEntity.getStartingAt());
        subscription.setEndingAt(subscriptionEntity.getEndingAt());
        subscription.setProcessedAt(subscriptionEntity.getProcessedAt());
        subscription.setProcessedBy(subscriptionEntity.getProcessedBy());
        subscription.setReason(subscriptionEntity.getReason());
        subscription.setStatus(subscriptionEntity.getStatus());
        subscription.setSubscribedBy(
                new Subscription.User(subscriptionEntity.getSubscribedBy(),
                        userService.findById(subscriptionEntity.getSubscribedBy()).getDisplayName()
                ));

        PlanEntity plan = planService.findById(subscriptionEntity.getPlan());
        subscription.setPlan(new Subscription.Plan(plan.getId(), plan.getName()));

        ApplicationEntity application = applicationService.findById(subscriptionEntity.getApplication());
        subscription.setApplication(
                new Subscription.Application(
                        application.getId(),
                        application.getName(),
                        application.getType(),
                        application.getDescription(),
                        new Subscription.User(
                                application.getPrimaryOwner().getId(),
                                application.getPrimaryOwner().getDisplayName()
                        )
                ));

        subscription.setClosedAt(subscriptionEntity.getClosedAt());

        return subscription;
    }

    private static class SubscriptionParam {

        @QueryParam("plan")
        @Parameter(description = "plan", explode = Explode.FALSE, schema = @Schema(type = "array"))
        private ListStringParam plans;

        @QueryParam("application")
        @Parameter(description = "application", explode = Explode.FALSE, schema = @Schema(type = "array"))
        private ListStringParam applications;

        @QueryParam("status")
        @DefaultValue("ACCEPTED,PENDING,PAUSED")
        @Parameter(description = "Subscription status", explode = Explode.FALSE, schema = @Schema(type = "array"))
        private ListSubscriptionStatusParam status;

        @QueryParam("api_key")
        private String apiKey;

        public ListStringParam getPlans() {
            return plans;
        }

        public void setPlans(ListStringParam plans) {
            this.plans = plans;
        }

        public ListStringParam getApplications() {
            return applications;
        }

        public void setApplications(ListStringParam applications) {
            this.applications = applications;
        }

        public ListSubscriptionStatusParam getStatus() {
            return status;
        }

        public void setStatus(ListSubscriptionStatusParam status) {
            this.status = status;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        private SubscriptionQuery toQuery() {
            SubscriptionQuery query = new SubscriptionQuery();
            query.setPlans(plans);
            query.setStatuses(status);
            query.setApplications(applications);
            query.setApiKey(this.apiKey);
            return query;
        }
    }
}
