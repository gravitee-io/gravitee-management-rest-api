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
package io.gravitee.rest.api.service.impl;

import static io.gravitee.repository.management.model.Api.AuditEvent.*;
import static io.gravitee.repository.management.model.Visibility.PUBLIC;
import static io.gravitee.repository.management.model.Workflow.AuditEvent.*;
import static io.gravitee.rest.api.model.EventType.PUBLISH_API;
import static io.gravitee.rest.api.model.ImportSwaggerDescriptorEntity.Type.INLINE;
import static io.gravitee.rest.api.model.PageType.SWAGGER;
import static io.gravitee.rest.api.model.WorkflowType.REVIEW;
import static java.util.Collections.*;
import static java.util.Comparator.comparing;
import static java.util.Optional.of;
import static java.util.stream.Collectors.*;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import io.gravitee.common.data.domain.Page;
import io.gravitee.definition.model.*;
import io.gravitee.definition.model.Properties;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.definition.model.services.healthcheck.HealthCheckService;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.ApiQualityRuleRepository;
import io.gravitee.repository.management.api.search.ApiCriteria;
import io.gravitee.repository.management.api.search.ApiFieldExclusionFilter;
import io.gravitee.repository.management.model.*;
import io.gravitee.repository.management.model.Api;
import io.gravitee.repository.management.model.ApiLifecycleState;
import io.gravitee.repository.management.model.Visibility;
import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.model.EventType;
import io.gravitee.rest.api.model.MembershipMemberType;
import io.gravitee.rest.api.model.MembershipReferenceType;
import io.gravitee.rest.api.model.alert.AlertReferenceType;
import io.gravitee.rest.api.model.alert.AlertTriggerEntity;
import io.gravitee.rest.api.model.api.*;
import io.gravitee.rest.api.model.api.header.ApiHeaderEntity;
import io.gravitee.rest.api.model.application.ApplicationListItem;
import io.gravitee.rest.api.model.common.Pageable;
import io.gravitee.rest.api.model.common.PageableImpl;
import io.gravitee.rest.api.model.common.Sortable;
import io.gravitee.rest.api.model.documentation.PageQuery;
import io.gravitee.rest.api.model.permissions.ApiPermission;
import io.gravitee.rest.api.model.permissions.RolePermissionAction;
import io.gravitee.rest.api.model.permissions.RoleScope;
import io.gravitee.rest.api.model.subscription.SubscriptionQuery;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.service.builder.EmailNotificationBuilder;
import io.gravitee.rest.api.service.common.GraviteeContext;
import io.gravitee.rest.api.service.exceptions.*;
import io.gravitee.rest.api.service.impl.search.SearchResult;
import io.gravitee.rest.api.service.migration.APIV1toAPIV2Converter;
import io.gravitee.rest.api.service.notification.ApiHook;
import io.gravitee.rest.api.service.notification.NotificationParamsBuilder;
import io.gravitee.rest.api.service.processor.ApiSynchronizationProcessor;
import io.gravitee.rest.api.service.search.query.Query;
import io.gravitee.rest.api.service.search.query.QueryBuilder;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.bind.DatatypeConverter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author Azize ELAMRANI (azize at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class ApiServiceImpl extends AbstractApiService implements ApiService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiServiceImpl.class);

    @Autowired
    private ApiQualityRuleRepository apiQualityRuleRepository;

    @Autowired
    private EventService eventService;

    @Autowired
    private ApiSynchronizationProcessor apiSynchronizationProcessor;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private TopApiService topApiService;

    @Autowired
    private PortalNotificationConfigService portalNotificationConfigService;

    @Autowired
    private SwaggerService swaggerService;

    @Autowired
    private ApiHeaderService apiHeaderService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private APIV1toAPIV2Converter apiv1toAPIV2Converter;

    @Value("${configuration.default-api-icon:}")
    private String defaultApiIcon;

    private static final String ENDPOINTS_DELIMITER = "\n";

    @Override
    public ApiEntity createFromSwagger(
            final SwaggerApiEntity swaggerApiEntity,
            final String userId,
            final ImportSwaggerDescriptorEntity swaggerDescriptor, String environment
    ) throws ApiAlreadyExistsException {
        final ApiEntity createdApi = createFromUpdateApiEntity(swaggerApiEntity, userId, swaggerDescriptor, environment);

        createMetadata(swaggerApiEntity.getMetadata(), createdApi.getId());

        return createdApi;
    }

    private void createMetadata(List<ApiMetadataEntity> apiMetadata, String apiId) {
        if (apiMetadata != null && !apiMetadata.isEmpty()) {
            apiMetadata
                .stream()
                .map(
                    data -> {
                        NewApiMetadataEntity newMD = new NewApiMetadataEntity();
                        newMD.setFormat(data.getFormat());
                        newMD.setName(data.getName());
                        newMD.setValue(data.getValue());
                        newMD.setApiId(apiId);
                        return newMD;
                    }
                )
                .forEach(this.apiMetadataService::create);
        }
    }

    @Override
    public ApiEntity create(final NewApiEntity newApiEntity, final String userId, String environment) throws ApiAlreadyExistsException {
        UpdateApiEntity apiEntity = new UpdateApiEntity();

        apiEntity.setName(newApiEntity.getName());
        apiEntity.setDescription(newApiEntity.getDescription());
        apiEntity.setVersion(newApiEntity.getVersion());

        Set<String> groups = newApiEntity.getGroups();
        if (groups != null && !groups.isEmpty()) {
            checkGroupExistence(groups);
            groups = removePOGroups(groups, null);
            newApiEntity.setGroups(groups);
        }
        apiEntity.setGroups(groups);

        Proxy proxy = new Proxy();
        proxy.setVirtualHosts(singletonList(new VirtualHost(newApiEntity.getContextPath())));
        EndpointGroup group = new EndpointGroup();
        group.setName("default-group");

        String[] endpoints = null;
        if (newApiEntity.getEndpoint() != null) {
            endpoints = newApiEntity.getEndpoint().split(ENDPOINTS_DELIMITER);
        }

        if (endpoints == null) {
            group.setEndpoints(singleton(new HttpEndpoint("default", null)));
        } else if (endpoints.length == 1) {
            group.setEndpoints(singleton(new HttpEndpoint("default", endpoints[0])));
        } else {
            group.setEndpoints(new HashSet<>());
            for (int i = 0; i < endpoints.length; i++) {
                group.getEndpoints().add(new HttpEndpoint("server" + (i + 1), endpoints[i]));
            }
        }
        proxy.setGroups(singleton(group));
        apiEntity.setProxy(proxy);

        final List<String> declaredPaths = newApiEntity.getPaths() != null ? newApiEntity.getPaths() : new ArrayList<>();
        if (!declaredPaths.contains("/")) {
            declaredPaths.add(0, "/");
        }

        apiEntity.setPathMappings(new HashSet<>(declaredPaths));

        return createFromUpdateApiEntity(apiEntity, userId, null, environment);
    }

    private ApiEntity createFromUpdateApiEntity(
            final UpdateApiEntity apiEntity,
            final String userId,
            final ImportSwaggerDescriptorEntity swaggerDescriptor, String environment
    ) {
        final ApiEntity createdApi = this.create0(apiEntity, userId, true, null, environment, null);
        createOrUpdateDocumentation(swaggerDescriptor, createdApi, true);
        return createdApi;
    }

    private void createOrUpdateDocumentation(
        final ImportSwaggerDescriptorEntity swaggerDescriptor,
        final ApiEntity api,
        boolean isForCreation
    ) {
        if (swaggerDescriptor != null && swaggerDescriptor.isWithDocumentation()) {
            List<PageEntity> apiDocs = pageService.search(new PageQuery.Builder().api(api.getId()).type(PageType.SWAGGER).build());

            if (isForCreation || (apiDocs == null || apiDocs.isEmpty())) {
                final NewPageEntity page = new NewPageEntity();
                page.setName("Swagger");
                page.setType(SWAGGER);
                page.setOrder(1);
                if (INLINE.equals(swaggerDescriptor.getType())) {
                    page.setContent(swaggerDescriptor.getPayload());
                } else {
                    final PageSourceEntity source = new PageSourceEntity();
                    page.setSource(source);
                    source.setType("http-fetcher");
                    source.setConfiguration(objectMapper.convertValue(singletonMap("url", swaggerDescriptor.getPayload()), JsonNode.class));
                }
                pageService.createPage(api.getId(), page, GraviteeContext.getCurrentEnvironment());
            } else if (apiDocs.size() == 1) {
                PageEntity pageToUpdate = apiDocs.get(0);
                final UpdatePageEntity page = new UpdatePageEntity();
                page.setName(pageToUpdate.getName());
                page.setOrder(pageToUpdate.getOrder());
                page.setHomepage(pageToUpdate.isHomepage());
                page.setPublished(pageToUpdate.isPublished());
                page.setParentId(pageToUpdate.getParentId());
                page.setConfiguration(pageToUpdate.getConfiguration());
                if (INLINE.equals(swaggerDescriptor.getType())) {
                    page.setContent(swaggerDescriptor.getPayload());
                } else {
                    final PageSourceEntity source = new PageSourceEntity();
                    page.setSource(source);
                    source.setType("http-fetcher");
                    source.setConfiguration(objectMapper.convertValue(singletonMap("url", swaggerDescriptor.getPayload()), JsonNode.class));
                }
                pageService.update(pageToUpdate.getId(), page);
            }
        }
    }

    @Override
    public ApiEntity findById(String apiId) {
        return super.findById(apiId);
    }

    @Override
    public PrimaryOwnerEntity getPrimaryOwner(String apiId) throws TechnicalManagementException {
        return super.getPrimaryOwner(apiId);
    }

    @Override
    public Set<ApiEntity> findByVisibility(io.gravitee.rest.api.model.Visibility visibility) {
        try {
            LOGGER.debug("Find APIs by visibility {}", visibility);
            return new HashSet<>(
                convert(
                    apiRepository.search(
                        new ApiCriteria.Builder()
                            .environmentId(GraviteeContext.getCurrentEnvironment())
                            .visibility(Visibility.valueOf(visibility.name()))
                            .build()
                    )
                )
            );
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find all APIs", ex);
            throw new TechnicalManagementException("An error occurs while trying to find all APIs", ex);
        }
    }

    @Override
    public Set<ApiEntity> findAll() {
        try {
            LOGGER.debug("Find all APIs for current environment {}", GraviteeContext.getCurrentEnvironment());
            return new HashSet<>(
                convert(apiRepository.search(new ApiCriteria.Builder().environmentId(GraviteeContext.getCurrentEnvironment()).build()))
            );
        } catch (TechnicalException ex) {
            LOGGER.error(
                "An error occurs while trying to find all APIs for current environment {}",
                GraviteeContext.getCurrentEnvironment(),
                ex
            );
            throw new TechnicalManagementException("An error occurs while trying to find all APIs for current environment", ex);
        }
    }

    @Override
    public Set<ApiEntity> findAllLight() {
        try {
            LOGGER.debug("Find all APIs without some fields (definition, picture...)");
            return new HashSet<>(
                convert(
                    apiRepository.search(
                        new ApiCriteria.Builder().environmentId(GraviteeContext.getCurrentEnvironment()).build(),
                        new ApiFieldExclusionFilter.Builder().excludeDefinition().excludePicture().build()
                    )
                )
            );
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find all APIs light", ex);
            throw new TechnicalManagementException("An error occurs while trying to find all APIs light", ex);
        }
    }

    @Override
    public Set<ApiEntity> findByUser(String userId, ApiQuery apiQuery, boolean portal) {
        return new HashSet<>(findByUser(userId, apiQuery, null, null, portal).getContent());
    }

    @Override
    public Page<ApiEntity> findByUser(String userId, ApiQuery apiQuery, Sortable sortable, Pageable pageable, boolean portal) {
        try {
            LOGGER.debug("Find APIs page by user {}", userId);

            List<Api> allApis = findApisByUser(userId, apiQuery, portal);

            final Page<Api> apiPage = sortAndPaginate(allApis, sortable, pageable);

            // merge all apis
            final List<ApiEntity> apis = convert(apiPage.getContent());

            return new Page<>(
                filterApiByQuery(apis.stream(), apiQuery).collect(toList()),
                apiPage.getPageNumber(),
                (int) apiPage.getPageElements(),
                apiPage.getTotalElements()
            );
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find APIs for user {}", userId, ex);
            throw new TechnicalManagementException("An error occurs while trying to find APIs for user " + userId, ex);
        }
    }

    @Override
    public List<String> findIdsByUser(String userId, ApiQuery apiQuery, boolean portal) {
        try {
            LOGGER.debug("Search API ids by user {} and {}", userId, apiQuery);
            return findApisByUser(userId, apiQuery, portal).stream().map(Api::getId).collect(toList());
        } catch (Exception ex) {
            final String errorMessage = "An error occurs while trying to search for API ids for user " + userId + ": " + apiQuery;
            LOGGER.error(errorMessage, ex);
            throw new TechnicalManagementException(errorMessage, ex);
        }
    }

    private List<Api> findApisByUser(String userId, ApiQuery apiQuery, boolean portal) {
        //get all public apis
        List<Api> publicApis;
        if (portal) {
            publicApis = apiRepository.search(queryToCriteria(apiQuery).visibility(PUBLIC).build());
        } else {
            publicApis = emptyList();
        }

        List<Api> userApis = emptyList();
        List<Api> groupApis = emptyList();
        List<Api> subscribedApis = emptyList();

        // for others API, user must be authenticated
        if (userId != null) {
            // get user apis
            final String[] userApiIds = membershipService
                .getMembershipsByMemberAndReference(MembershipMemberType.USER, userId, MembershipReferenceType.API)
                .stream()
                .map(MembershipEntity::getReferenceId)
                .filter(
                    apiId -> {
                        if (apiQuery != null && !CollectionUtils.isEmpty(apiQuery.getIds())) {
                            // We already have api ids to focus on.
                            return apiQuery.getIds().contains(apiId);
                        } else {
                            return true;
                        }
                    }
                )
                .toArray(String[]::new);

            if (userApiIds.length > 0) {
                userApis = apiRepository.search(queryToCriteria(apiQuery).ids(userApiIds).build());
            }

            // get user groups apis
            final String[] groupIds = membershipService
                .getMembershipsByMemberAndReference(MembershipMemberType.USER, userId, MembershipReferenceType.GROUP)
                .stream()
                .filter(
                    m -> {
                        final RoleEntity roleInGroup = roleService.findById(m.getRoleId());
                        if (!portal) {
                            return (
                                m.getRoleId() != null &&
                                roleInGroup.getScope().equals(RoleScope.API) &&
                                canManageApi(roleInGroup.getPermissions())
                            );
                        }
                        return m.getRoleId() != null && roleInGroup.getScope().equals(RoleScope.API);
                    }
                )
                .map(MembershipEntity::getReferenceId)
                .toArray(String[]::new);
            if (groupIds.length > 0 && groupIds[0] != null) {
                groupApis = apiRepository.search(queryToCriteria(apiQuery).groups(groupIds).build());
            }

            // get user subscribed apis, useful when an API becomes private and an app owner is not anymore in members.
            if (portal) {
                final Set<String> applications = applicationService
                    .findByUser(userId)
                    .stream()
                    .map(ApplicationListItem::getId)
                    .collect(toSet());
                if (!applications.isEmpty()) {
                    final SubscriptionQuery query = new SubscriptionQuery();
                    query.setApplications(applications);
                    final Collection<SubscriptionEntity> subscriptions = subscriptionService.search(query);
                    if (subscriptions != null && !subscriptions.isEmpty()) {
                        subscribedApis =
                            apiRepository.search(
                                queryToCriteria(apiQuery)
                                    .ids(subscriptions.stream().map(SubscriptionEntity::getApi).distinct().toArray(String[]::new))
                                    .build()
                            );
                    }
                }
            }
        }

        List<Api> allApis = new ArrayList<>();
        allApis.addAll(publicApis);
        allApis.addAll(userApis);
        allApis.addAll(groupApis);
        allApis.addAll(subscribedApis);

        return allApis.stream().distinct().collect(toList());
    }

    private boolean canManageApi(Map<String, char[]> permissions) {
        return permissions
            .entrySet()
            .stream()
            .filter(
                entry -> !entry.getKey().equals(ApiPermission.RATING.name()) && !entry.getKey().equals(ApiPermission.RATING_ANSWER.name())
            )
            .anyMatch(
                entry -> {
                    String stringPerm = new String(entry.getValue());
                    return stringPerm.contains("C") || stringPerm.contains("U") || stringPerm.contains("D");
                }
            );
    }

    @Override
    public Set<ApiEntity> findPublishedByUser(String userId, ApiQuery apiQuery) {
        if (apiQuery == null) {
            apiQuery = new ApiQuery();
        }
        apiQuery.setLifecycleStates(Arrays.asList(io.gravitee.rest.api.model.api.ApiLifecycleState.PUBLISHED));
        return findByUser(userId, apiQuery, true);
    }

    @Override
    public Page<ApiEntity> findPublishedByUser(String userId, ApiQuery apiQuery, Sortable sortable, Pageable pageable) {
        if (apiQuery == null) {
            apiQuery = new ApiQuery();
        }
        apiQuery.setLifecycleStates(Arrays.asList(io.gravitee.rest.api.model.api.ApiLifecycleState.PUBLISHED));
        return findByUser(userId, apiQuery, sortable, pageable, true);
    }

    @Override
    public Set<ApiEntity> findPublishedByUser(String userId) {
        return findPublishedByUser(userId, null);
    }

    private Stream<ApiEntity> filterApiByQuery(Stream<ApiEntity> apiEntityStream, ApiQuery query) {
        if (query == null) {
            return apiEntityStream;
        }
        return apiEntityStream
            .filter(api -> query.getTag() == null || (api.getTags() != null && api.getTags().contains(query.getTag())))
            .filter(
                api ->
                    query.getContextPath() == null ||
                    api.getProxy().getVirtualHosts().stream().anyMatch(virtualHost -> query.getContextPath().equals(virtualHost.getPath()))
            );
    }

    private Set merge(List originSet, Collection setToAdd) {
        if (originSet == null) {
            return merge(Collections.emptySet(), setToAdd);
        }
        return merge(new HashSet<>(originSet), setToAdd);
    }

    private Set merge(Set originSet, Collection setToAdd) {
        if (setToAdd != null && !setToAdd.isEmpty()) {
            if (originSet == null) {
                originSet = new HashSet();
            }
            originSet.addAll(setToAdd);
        }
        return originSet;
    }

    @Override
    public ApiEntity updateFromSwagger(String apiId, SwaggerApiEntity swaggerApiEntity, ImportSwaggerDescriptorEntity swaggerDescriptor) {
        final ApiEntity apiEntityToUpdate = this.findById(apiId);
        final UpdateApiEntity updateApiEntity = convert(apiEntityToUpdate);

        // Overwrite from swagger
        updateApiEntity.setVersion(swaggerApiEntity.getVersion());
        updateApiEntity.setName(swaggerApiEntity.getName());
        updateApiEntity.setDescription(swaggerApiEntity.getDescription());

        updateApiEntity.setCategories(merge(updateApiEntity.getCategories(), swaggerApiEntity.getCategories()));

        if (swaggerApiEntity.getProxy() != null) {
            Proxy proxy = updateApiEntity.getProxy();
            if (proxy == null) {
                proxy = new Proxy();
            }

            proxy.setGroups(merge(proxy.getGroups(), swaggerApiEntity.getProxy().getGroups()));

            List<VirtualHost> virtualHostsToAdd = swaggerApiEntity.getProxy().getVirtualHosts();
            if (virtualHostsToAdd != null && !virtualHostsToAdd.isEmpty()) {
                // Sanitize both current vHost and vHost to add to avoid duplicates
                virtualHostsToAdd = virtualHostsToAdd.stream().map(this.virtualHostService::sanitize).collect(toList());
                proxy.setVirtualHosts(
                    new ArrayList<>(
                        merge(proxy.getVirtualHosts().stream().map(this.virtualHostService::sanitize).collect(toSet()), virtualHostsToAdd)
                    )
                );
            }
            updateApiEntity.setProxy(proxy);
        }

        updateApiEntity.setGroups(merge(updateApiEntity.getGroups(), swaggerApiEntity.getGroups()));
        updateApiEntity.setLabels(new ArrayList<>(merge(updateApiEntity.getLabels(), swaggerApiEntity.getLabels())));
        if (swaggerApiEntity.getPicture() != null) {
            updateApiEntity.setPicture(swaggerApiEntity.getPicture());
        }
        updateApiEntity.setTags(merge(updateApiEntity.getTags(), swaggerApiEntity.getTags()));
        if (swaggerApiEntity.getVisibility() != null) {
            updateApiEntity.setVisibility(swaggerApiEntity.getVisibility());
        }

        if (swaggerApiEntity.getProperties() != null) {
            Properties properties = updateApiEntity.getProperties();
            if (properties == null) {
                properties = new Properties();
            }
            properties.setProperties(new ArrayList<>(merge(properties.getProperties(), swaggerApiEntity.getProperties().getProperties())));
            updateApiEntity.setProperties(properties);
        }

        // Overwrite from swagger, if asked
        if (swaggerDescriptor != null) {
            if (swaggerDescriptor.isWithPathMapping()) {
                updateApiEntity.setPathMappings(swaggerApiEntity.getPathMappings());
            }

            if (swaggerDescriptor.isWithPolicyPaths()) {
                if (DefinitionVersion.V2.equals(updateApiEntity.getGraviteeDefinitionVersion())) {
                    updateApiEntity.setFlows(swaggerApiEntity.getFlows());
                } else {
                    updateApiEntity.setPaths(swaggerApiEntity.getPaths());
                }
            }
        }

        createOrUpdateDocumentation(swaggerDescriptor, apiEntityToUpdate, false);

        final ApiEntity updatedApi = update(apiId, updateApiEntity);

        if (swaggerApiEntity.getMetadata() != null && !swaggerApiEntity.getMetadata().isEmpty()) {
            swaggerApiEntity
                .getMetadata()
                .forEach(
                    data -> {
                        try {
                            final ApiMetadataEntity apiMetadataEntity = this.apiMetadataService.findByIdAndApi(data.getKey(), apiId);
                            UpdateApiMetadataEntity updateApiMetadataEntity = new UpdateApiMetadataEntity();
                            updateApiMetadataEntity.setApiId(apiId);
                            updateApiMetadataEntity.setFormat(data.getFormat());
                            updateApiMetadataEntity.setKey(apiMetadataEntity.getKey());
                            updateApiMetadataEntity.setName(data.getName());
                            updateApiMetadataEntity.setValue(data.getValue());
                            ApiMetadataEntity metadata = this.apiMetadataService.update(updateApiMetadataEntity);
                            updatedApi.getMetadata().put(metadata.getKey(), metadata.getValue());
                        } catch (ApiMetadataNotFoundException amnfe) {
                            NewApiMetadataEntity newMD = new NewApiMetadataEntity();
                            newMD.setApiId(apiId);
                            newMD.setFormat(data.getFormat());
                            newMD.setName(data.getName());
                            newMD.setValue(data.getValue());
                            ApiMetadataEntity metadata = this.apiMetadataService.create(newMD);
                            updatedApi.getMetadata().put(metadata.getKey(), metadata.getValue());
                        }
                    }
                );
        }

        searchEngineService.index(updatedApi, false);

        return updatedApi;
    }

    @Override
    public ApiEntity update(String apiId, UpdateApiEntity updateApiEntity) {
        return update(apiId, updateApiEntity, false);
    }

    @Override
    public ApiEntity update(String apiId, UpdateApiEntity updateApiEntity, boolean checkPlans) {
        return super.update(apiId, updateApiEntity, checkPlans);
    }

    @Override
    public void delete(String apiId) {
        try {
            LOGGER.debug("Delete API {}", apiId);

            Optional<Api> optApi = apiRepository.findById(apiId);
            if (!optApi.isPresent()) {
                throw new ApiNotFoundException(apiId);
            }

            if (optApi.get().getLifecycleState() == LifecycleState.STARTED) {
                throw new ApiRunningStateException(apiId);
            } else {
                // Delete plans
                Set<PlanEntity> plans = planService.findByApi(apiId);
                Set<String> plansNotClosed = plans
                    .stream()
                    .filter(plan -> plan.getStatus() == PlanStatus.PUBLISHED)
                    .map(PlanEntity::getName)
                    .collect(toSet());

                if (!plansNotClosed.isEmpty()) {
                    throw new ApiNotDeletableException(plansNotClosed);
                }

                Collection<SubscriptionEntity> subscriptions = subscriptionService.findByApi(apiId);
                subscriptions.forEach(sub -> subscriptionService.delete(sub.getId()));

                for (PlanEntity plan : plans) {
                    planService.delete(plan.getId());
                }

                // Delete events
                final EventQuery query = new EventQuery();
                query.setApi(apiId);
                eventService.search(query).forEach(event -> eventService.delete(event.getId()));

                // https://github.com/gravitee-io/issues/issues/4130
                // Ensure we are sending a last UNPUBLISH_API event because the gateway couldn't be aware that the API (and
                // all its relative events) have been deleted.
                Map<String, String> properties = new HashMap<>(2);
                properties.put(Event.EventProperties.API_ID.getValue(), apiId);
                if (getAuthenticatedUser() != null) {
                    properties.put(Event.EventProperties.USER.getValue(), getAuthenticatedUser().getUsername());
                }
                eventService.create(EventType.UNPUBLISH_API, null, properties);

                // Delete pages
                pageService.deleteAllByApi(apiId);

                // Delete top API
                topApiService.delete(apiId);
                // Delete API
                apiRepository.delete(apiId);
                // Delete memberships
                membershipService.deleteReference(MembershipReferenceType.API, apiId);
                // Delete notifications
                genericNotificationConfigService.deleteReference(NotificationReferenceType.API, apiId);
                portalNotificationConfigService.deleteReference(NotificationReferenceType.API, apiId);
                // Delete alerts
                final List<AlertTriggerEntity> alerts = alertService.findByReferenceWithEventCounts(AlertReferenceType.API, apiId);
                alerts.forEach(alert -> alertService.delete(alert.getId(), alert.getReferenceId()));
                // delete all reference on api quality rule
                apiQualityRuleRepository.deleteByApi(apiId);
                // Audit
                auditService.createApiAuditLog(apiId, Collections.emptyMap(), API_DELETED, new Date(), optApi.get(), null);
                // remove from search engine
                searchEngineService.delete(convert(optApi.get()), false);

                mediaService.deleteAllByApi(apiId);

                apiMetadataService.deleteAllByApi(apiId);
            }
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to delete API {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to delete API " + apiId, ex);
        }
    }

    @Override
    public ApiEntity start(String apiId, String userId) {
        try {
            LOGGER.debug("Start API {}", apiId);
            ApiEntity apiEntity = updateLifecycle(apiId, LifecycleState.STARTED, userId);
            notifierService.trigger(
                ApiHook.API_STARTED,
                apiId,
                new NotificationParamsBuilder().api(apiEntity).user(userService.findById(userId)).build()
            );
            return apiEntity;
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to start API {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to start API " + apiId, ex);
        }
    }

    @Override
    public ApiEntity stop(String apiId, String userId) {
        try {
            LOGGER.debug("Stop API {}", apiId);
            ApiEntity apiEntity = updateLifecycle(apiId, LifecycleState.STOPPED, userId);
            notifierService.trigger(
                ApiHook.API_STOPPED,
                apiId,
                new NotificationParamsBuilder().api(apiEntity).user(userService.findById(userId)).build()
            );
            return apiEntity;
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to stop API {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to stop API " + apiId, ex);
        }
    }

    @Override
    public boolean isSynchronized(String apiId) {
        try {
            // 1_ First, check the API state
            ApiEntity api = findById(apiId);

            Map<String, Object> properties = new HashMap<>();
            properties.put(Event.EventProperties.API_ID.getValue(), apiId);

            io.gravitee.common.data.domain.Page<EventEntity> events = eventService.search(
                Arrays.asList(PUBLISH_API, EventType.UNPUBLISH_API),
                properties,
                0,
                0,
                0,
                1
            );

            if (!events.getContent().isEmpty()) {
                // According to page size, we know that we have only one element in the list
                EventEntity lastEvent = events.getContent().get(0);

                //TODO: Done only for backward compatibility with 0.x. Must be removed later (1.1.x ?)
                boolean enabled = objectMapper.getDeserializationConfig().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                Api payloadEntity = objectMapper.readValue(lastEvent.getPayload(), Api.class);
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, enabled);

                final ApiEntity deployedApi = convert(payloadEntity);
                // Remove policy description from sync check
                removeDescriptionFromPolicies(api);
                removeDescriptionFromPolicies(deployedApi);

                boolean sync = apiSynchronizationProcessor.processCheckSynchronization(deployedApi, api);

                // 2_ If API definition is synchronized, check if there is any modification for API's plans
                // but only for published or closed plan
                if (sync) {
                    Set<PlanEntity> plans = planService.findByApi(api.getId());
                    sync =
                        plans
                            .stream()
                            .filter(plan -> (plan.getStatus() != PlanStatus.STAGING))
                            .filter(plan -> plan.getNeedRedeployAt().after(api.getDeployedAt()))
                            .count() ==
                        0;
                }

                return sync;
            }
        } catch (Exception e) {
            LOGGER.error("An error occurs while trying to check API synchronization state {}", apiId, e);
        }

        return false;
    }

    private void removeDescriptionFromPolicies(final ApiEntity api) {
        if (api.getPaths() != null) {
            api
                .getPaths()
                .forEach(
                    (s, rules) -> {
                        if (rules != null) {
                            rules.forEach(rule -> rule.setDescription(""));
                        }
                    }
                );
        }
    }

    @Override
    public ApiEntity deploy(String apiId, String userId, EventType eventType, ApiDeploymentEntity apiDeploymentEntity) {
        try {
            LOGGER.debug("Deploy API : {}", apiId);

            return deployCurrentAPI(apiId, userId, eventType, apiDeploymentEntity);
        } catch (Exception ex) {
            LOGGER.error("An error occurs while trying to deploy API: {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to deploy API: " + apiId, ex);
        }
    }

    @Override
    public ApiEntity rollback(String apiId, UpdateApiEntity api) {
        LOGGER.debug("Rollback API : {}", apiId);
        try {
            // Audit
            auditService.createApiAuditLog(apiId, Collections.emptyMap(), API_ROLLBACKED, new Date(), null, null);

            return update(apiId, api);
        } catch (Exception ex) {
            LOGGER.error("An error occurs while trying to rollback API: {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to rollback API: " + apiId, ex);
        }
    }

    private ApiEntity deployCurrentAPI(String apiId, String userId, EventType eventType, ApiDeploymentEntity apiDeploymentEntity)
        throws Exception {
        Optional<Api> api = apiRepository.findById(apiId);

        if (api.isPresent()) {
            // add deployment date
            Api apiValue = api.get();
            apiValue.setUpdatedAt(new Date());
            apiValue.setDeployedAt(apiValue.getUpdatedAt());
            apiValue = apiRepository.update(apiValue);

            Map<String, String> properties = new HashMap<>();
            properties.put(Event.EventProperties.API_ID.getValue(), apiValue.getId());
            properties.put(Event.EventProperties.USER.getValue(), userId);

            // Clear useless field for history
            apiValue.setPicture(null);

            addDeploymentLabelToProperties(apiId, eventType, properties, apiDeploymentEntity);

            // And create event
            eventService.create(eventType, objectMapper.writeValueAsString(apiValue), properties);

            return convert(singletonList(apiValue)).iterator().next();
        } else {
            throw new ApiNotFoundException(apiId);
        }
    }

    private void addDeploymentLabelToProperties(
        String apiId,
        EventType eventType,
        Map<String, String> properties,
        ApiDeploymentEntity apiDeploymentEntity
    ) {
        if (PUBLISH_API.equals(eventType)) {
            final EventQuery query = new EventQuery();
            query.setApi(apiId);
            query.setTypes(singleton(PUBLISH_API));

            final Optional<EventEntity> optEvent = eventService.search(query).stream().max(comparing(EventEntity::getCreatedAt));

            String lastDeployNumber = optEvent.isPresent()
                ? optEvent.get().getProperties().getOrDefault(Event.EventProperties.DEPLOYMENT_NUMBER.getValue(), "0")
                : "0";
            String newDeployNumber = Long.toString(Long.parseLong(lastDeployNumber) + 1);
            properties.put(Event.EventProperties.DEPLOYMENT_NUMBER.getValue(), newDeployNumber);

            if (apiDeploymentEntity != null && StringUtils.isNotEmpty(apiDeploymentEntity.getDeploymentLabel())) {
                properties.put(Event.EventProperties.DEPLOYMENT_LABEL.getValue(), apiDeploymentEntity.getDeploymentLabel());
            }
        }
    }

    /**
     * Allows to deploy the last published API
     * @param apiId the API id
     * @param userId the user id
     * @param eventType the event type
     * @return The persisted API or null
     * @throws TechnicalException if an exception occurs while saving the API
     */
    private ApiEntity deployLastPublishedAPI(String apiId, String userId, EventType eventType) throws TechnicalException {
        final EventQuery query = new EventQuery();
        query.setApi(apiId);
        query.setTypes(singleton(PUBLISH_API));

        final Optional<EventEntity> optEvent = eventService.search(query).stream().max(comparing(EventEntity::getCreatedAt));
        try {
            if (optEvent.isPresent()) {
                EventEntity event = optEvent.get();
                JsonNode node = objectMapper.readTree(event.getPayload());
                Api lastPublishedAPI = objectMapper.convertValue(node, Api.class);
                lastPublishedAPI.setLifecycleState(convert(eventType));
                lastPublishedAPI.setUpdatedAt(new Date());
                lastPublishedAPI.setDeployedAt(new Date());
                Map<String, String> properties = new HashMap<>();
                properties.put(Event.EventProperties.API_ID.getValue(), lastPublishedAPI.getId());
                properties.put(Event.EventProperties.USER.getValue(), userId);

                // Clear useless field for history
                lastPublishedAPI.setPicture(null);

                // And create event
                eventService.create(eventType, objectMapper.writeValueAsString(lastPublishedAPI), properties);
                return null;
            } else {
                // this is the first time we start the api without previously deployed id.
                // let's do it.
                return this.deploy(apiId, userId, PUBLISH_API, new ApiDeploymentEntity());
            }
        } catch (Exception e) {
            LOGGER.error("An error occurs while trying to deploy last published API {}", apiId, e);
            throw new TechnicalException("An error occurs while trying to deploy last published API " + apiId, e);
        }
    }

    @Override
    public InlinePictureEntity getPicture(String apiId) {
        Api api = this.findApiById(apiId, GraviteeContext.getCurrentEnvironment());
        InlinePictureEntity imageEntity = new InlinePictureEntity();
        if (api.getPicture() != null) {
            String[] parts = api.getPicture().split(";", 2);
            imageEntity.setType(parts[0].split(":")[1]);
            String base64Content = api.getPicture().split(",", 2)[1];
            imageEntity.setContent(DatatypeConverter.parseBase64Binary(base64Content));
        } else {
            getDefaultPicture()
                .ifPresent(
                    content -> {
                        imageEntity.setType("image/png");
                        imageEntity.setContent(content);
                    }
                );
        }

        return imageEntity;
    }

    private Optional<byte[]> getDefaultPicture() {
        Optional<byte[]> content = Optional.empty();
        if (!Strings.isNullOrEmpty(defaultApiIcon)) {
            try {
                content = of(IOUtils.toByteArray(new FileInputStream(defaultApiIcon)));
            } catch (IOException ioe) {
                LOGGER.error("Default icon for API does not exist", ioe);
            }
        }
        return content;
    }

    @Override
    public InlinePictureEntity getBackground(String apiId) {
        Api api = this.findApiById(apiId, GraviteeContext.getCurrentEnvironment());
        InlinePictureEntity imageEntity = new InlinePictureEntity();
        if (api.getBackground() != null) {
            String[] parts = api.getBackground().split(";", 2);
            imageEntity.setType(parts[0].split(":")[1]);
            String base64Content = api.getBackground().split(",", 2)[1];
            imageEntity.setContent(DatatypeConverter.parseBase64Binary(base64Content));
        }

        return imageEntity;
    }

    @Override
    public ApiEntity migrate(String apiId) {
        final ApiEntity apiEntity = findById(apiId);
        final Set<PolicyEntity> policies = policyService.findAll();
        Set<PlanEntity> plans = planService.findByApi(apiId);

        ApiEntity migratedApi = apiv1toAPIV2Converter.migrateToV2(apiEntity, policies, plans);

        return this.update(apiId, ApiService.convert(migratedApi));
    }

    @Override
    public void deleteCategoryFromAPIs(final String categoryId) {
        findAll()
            .forEach(
                api -> {
                    if (api.getCategories() != null && api.getCategories().contains(categoryId)) {
                        removeCategory(api.getId(), categoryId);
                    }
                }
            );
    }

    private void removeCategory(String apiId, String categoryId) throws TechnicalManagementException {
        try {
            Optional<Api> optApi = apiRepository.findById(apiId);
            if (optApi.isPresent()) {
                Api api = optApi.get();
                Api previousApi = new Api(api);
                api.getCategories().remove(categoryId);
                api.setUpdatedAt(new Date());
                apiRepository.update(api);
                // Audit
                auditService.createApiAuditLog(apiId, Collections.emptyMap(), API_UPDATED, api.getUpdatedAt(), previousApi, api);
            } else {
                throw new ApiNotFoundException(apiId);
            }
        } catch (Exception ex) {
            LOGGER.error("An error occurs while removing category from API: {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while removing category from API: " + apiId, ex);
        }
    }

    @Override
    public void deleteTagFromAPIs(final String tagId) {
        findAll()
            .forEach(
                api -> {
                    if (api.getTags() != null && api.getTags().contains(tagId)) {
                        removeTag(api.getId(), tagId);
                    }
                }
            );
    }

    @Override
    public ApiModelEntity findByIdForTemplates(String apiId, boolean decodeTemplate) {
        final ApiEntity apiEntity = findById(apiId);

        final ApiModelEntity apiModelEntity = new ApiModelEntity();

        apiModelEntity.setId(apiEntity.getId());
        apiModelEntity.setName(apiEntity.getName());
        apiModelEntity.setDescription(apiEntity.getDescription());
        apiModelEntity.setCreatedAt(apiEntity.getCreatedAt());
        apiModelEntity.setDeployedAt(apiEntity.getDeployedAt());
        apiModelEntity.setUpdatedAt(apiEntity.getUpdatedAt());
        apiModelEntity.setGroups(apiEntity.getGroups());
        apiModelEntity.setVisibility(apiEntity.getVisibility());
        apiModelEntity.setCategories(apiEntity.getCategories());
        apiModelEntity.setVersion(apiEntity.getVersion());
        apiModelEntity.setState(apiEntity.getState());
        apiModelEntity.setTags(apiEntity.getTags());
        apiModelEntity.setServices(apiEntity.getServices());
        apiModelEntity.setPaths(apiEntity.getPaths());
        apiModelEntity.setPicture(apiEntity.getPicture());
        apiModelEntity.setPrimaryOwner(apiEntity.getPrimaryOwner());
        apiModelEntity.setProperties(apiEntity.getProperties());
        apiModelEntity.setProxy(convert(apiEntity.getProxy()));
        apiModelEntity.setLifecycleState(apiEntity.getLifecycleState());
        apiModelEntity.setDisableMembershipNotifications(apiEntity.isDisableMembershipNotifications());

        final List<ApiMetadataEntity> metadataList = apiMetadataService.findAllByApi(apiId);

        if (metadataList != null) {
            final Map<String, String> mapMetadata = new HashMap<>(metadataList.size());
            metadataList.forEach(
                metadata ->
                    mapMetadata.put(metadata.getKey(), metadata.getValue() == null ? metadata.getDefaultValue() : metadata.getValue())
            );
            apiModelEntity.setMetadata(mapMetadata);
            if (decodeTemplate) {
                try {
                    String decodedValue =
                        this.notificationTemplateService.resolveInlineTemplateWithParam(
                                apiModelEntity.getId(),
                                new StringReader(mapMetadata.toString()),
                                Collections.singletonMap("api", apiModelEntity)
                            );
                    Map<String, String> metadataDecoded = Arrays
                        .stream(decodedValue.substring(1, decodedValue.length() - 1).split(", "))
                        .map(entry -> entry.split("="))
                        .collect(Collectors.toMap(entry -> entry[0], entry -> entry.length > 1 ? entry[1] : ""));
                    apiModelEntity.setMetadata(metadataDecoded);
                } catch (Exception ex) {
                    throw new TechnicalManagementException("An error occurs while evaluating API metadata", ex);
                }
            }
        }
        return apiModelEntity;
    }

    @Override
    public boolean exists(final String apiId) {
        try {
            return apiRepository.findById(apiId).isPresent();
        } catch (final TechnicalException te) {
            final String msg = "An error occurs while checking if the API exists: " + apiId;
            LOGGER.error(msg, te);
            throw new TechnicalManagementException(msg, te);
        }
    }

    @Override
    public ApiEntity importPathMappingsFromPage(final ApiEntity apiEntity, final String page) {
        final PageEntity pageEntity = pageService.findById(page);
        if (SWAGGER.name().equals(pageEntity.getType())) {
            final ImportSwaggerDescriptorEntity importSwaggerDescriptorEntity = new ImportSwaggerDescriptorEntity();
            importSwaggerDescriptorEntity.setPayload(pageEntity.getContent());
            final SwaggerApiEntity swaggerApiEntity = swaggerService.createAPI(importSwaggerDescriptorEntity);
            apiEntity.getPathMappings().addAll(swaggerApiEntity.getPathMappings());
        }

        return update(apiEntity.getId(), ApiService.convert(apiEntity));
    }

    @Override
    public Page<ApiEntity> search(final ApiQuery query, Sortable sortable, Pageable pageable) {
        try {
            LOGGER.debug("Search paginated APIs by {}", query);

            // We need to sort on fields which cannot be sort using db engine (ex: api's definition fields). Retrieve all the apis, then sort and paginate in memory.
            Page<Api> apiPage = sortAndPaginate(apiRepository.search(queryToCriteria(query).build()), sortable, pageable);

            // Unfortunately, for now, filterApiByQuery can't be invoked because it could break pagination and sort.
            // Pagination MUST be applied before calls to convert as it involved a lot of data fetching and can be very slow.
            return new Page<>(
                this.convert(apiPage.getContent()),
                apiPage.getPageNumber(),
                (int) apiPage.getPageElements(),
                apiPage.getTotalElements()
            );
        } catch (TechnicalException ex) {
            final String errorMessage = "An error occurs while trying to search for paginated APIs: " + query;
            LOGGER.error(errorMessage, ex);
            throw new TechnicalManagementException(errorMessage, ex);
        }
    }

    @Override
    public Collection<ApiEntity> search(final ApiQuery query) {
        try {
            LOGGER.debug("Search APIs by {}", query);
            return filterApiByQuery(this.convert(apiRepository.search(queryToCriteria(query).build())).stream(), query).collect(toList());
        } catch (TechnicalException ex) {
            final String errorMessage = "An error occurs while trying to search for APIs: " + query;
            LOGGER.error(errorMessage, ex);
            throw new TechnicalManagementException(errorMessage, ex);
        }
    }

    @Override
    public Collection<String> searchIds(ApiQuery query) {
        try {
            LOGGER.debug("Search API ids by {}", query);
            return apiRepository.search(queryToCriteria(query).build()).stream().map(Api::getId).collect(toList());
        } catch (Exception ex) {
            final String errorMessage = "An error occurs while trying to search for API ids: " + query;
            LOGGER.error(errorMessage, ex);
            throw new TechnicalManagementException(errorMessage, ex);
        }
    }

    @Override
    public Page<ApiEntity> search(String query, Map<String, Object> filters, Sortable sortable, Pageable pageable) {
        try {
            LOGGER.debug("Search paged APIs by {}", query);

            Query<ApiEntity> apiQuery = QueryBuilder.create(ApiEntity.class).setQuery(query).setFilters(filters).build();

            SearchResult matchApis = searchEngineService.search(apiQuery);

            if (matchApis.getDocuments().isEmpty()) {
                return new Page<>(emptyList(), 0, 0, 0);
            }

            final ApiCriteria apiCriteria = new ApiCriteria.Builder().ids(matchApis.getDocuments().toArray(new String[0])).build();
            final Page<Api> apiPage = sortAndPaginate(apiRepository.search(apiCriteria), sortable, pageable);

            // merge all apis
            final List<ApiEntity> apis = convert(apiPage.getContent());

            return new Page<>(apis, apiPage.getPageNumber(), (int) apiPage.getPageElements(), apiPage.getTotalElements());
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to search paged apis", ex);
            throw new TechnicalManagementException("An error occurs while trying to search paged apis", ex);
        }
    }

    @Override
    public Collection<ApiEntity> search(String query, Map<String, Object> filters) {
        Query<ApiEntity> apiQuery = QueryBuilder.create(ApiEntity.class).setQuery(query).setFilters(filters).build();

        SearchResult matchApis = searchEngineService.search(apiQuery);
        return matchApis.getDocuments().stream().map(this::findById).collect(toList());
    }

    @Override
    public List<ApiHeaderEntity> getPortalHeaders(String apiId) {
        List<ApiHeaderEntity> entities = apiHeaderService.findAll();
        ApiModelEntity apiEntity = this.findByIdForTemplates(apiId);
        Map<String, Object> model = new HashMap<>();
        model.put("api", apiEntity);
        entities.forEach(
            entity -> {
                if (entity.getValue().contains("${")) {
                    String entityValue =
                        this.notificationTemplateService.resolveInlineTemplateWithParam(
                                entity.getId() + entity.getUpdatedAt().toString(),
                                entity.getValue(),
                                model
                            );
                    entity.setValue(entityValue);
                }
            }
        );
        return entities
            .stream()
            .filter(apiHeaderEntity -> apiHeaderEntity.getValue() != null && !apiHeaderEntity.getValue().isEmpty())
            .collect(Collectors.toList());
    }

    @Override
    public ApiEntity askForReview(final String apiId, final String userId, final ReviewEntity reviewEntity) {
        LOGGER.debug("Ask for review API {}", apiId);
        return updateWorkflowReview(apiId, userId, ApiHook.ASK_FOR_REVIEW, WorkflowState.IN_REVIEW, reviewEntity.getMessage());
    }

    @Override
    public ApiEntity acceptReview(final String apiId, final String userId, final ReviewEntity reviewEntity) {
        LOGGER.debug("Accept review API {}", apiId);
        return updateWorkflowReview(apiId, userId, ApiHook.REVIEW_OK, WorkflowState.REVIEW_OK, reviewEntity.getMessage());
    }

    @Override
    public ApiEntity rejectReview(final String apiId, final String userId, final ReviewEntity reviewEntity) {
        LOGGER.debug("Reject review API {}", apiId);
        return updateWorkflowReview(
            apiId,
            userId,
            ApiHook.REQUEST_FOR_CHANGES,
            WorkflowState.REQUEST_FOR_CHANGES,
            reviewEntity.getMessage()
        );
    }

    @Override
    public boolean hasHealthCheckEnabled(ApiEntity api, boolean mustBeEnabledOnAllEndpoints) {
        boolean globalHC =
            api.getServices() != null &&
            api.getServices().getAll() != null &&
            api.getServices().getAll().stream().anyMatch(service -> service.isEnabled() && service instanceof HealthCheckService);
        if (globalHC) {
            return true;
        } else {
            final Predicate<Endpoint> endpointHealthCheckEnabledPredicate = endpoint -> {
                if (endpoint instanceof HttpEndpoint) {
                    return ((HttpEndpoint) endpoint).getHealthCheck() != null && ((HttpEndpoint) endpoint).getHealthCheck().isEnabled();
                } else {
                    return false;
                }
            };
            if (mustBeEnabledOnAllEndpoints) {
                return api
                    .getProxy()
                    .getGroups()
                    .stream()
                    .allMatch(group -> group.getEndpoints().stream().allMatch(endpointHealthCheckEnabledPredicate));
            } else {
                return (
                    api.getProxy() != null &&
                    api.getProxy().getGroups() != null &&
                    api
                        .getProxy()
                        .getGroups()
                        .stream()
                        .anyMatch(
                            group ->
                                group.getEndpoints() != null && group.getEndpoints().stream().anyMatch(endpointHealthCheckEnabledPredicate)
                        )
                );
            }
        }
    }

    @Override
    public ApiEntity fetchMetadataForApi(ApiEntity apiEntity) {
        return super.fetchMetadataForApi(apiEntity);
    }

    @Override
    public void addGroup(String apiId, String group) {
        try {
            LOGGER.debug("Add group {} to API {}", group, apiId);

            Optional<Api> optApi = apiRepository.findById(apiId);

            if (GraviteeContext.getCurrentEnvironment() != null) {
                optApi = optApi.filter(result -> result.getEnvironmentId().equals(GraviteeContext.getCurrentEnvironment()));
            }

            Api api = optApi.orElseThrow(() -> new ApiNotFoundException(apiId));

            Set<String> groups = api.getGroups();
            if (groups == null) {
                groups = new HashSet<>();
                api.setGroups(groups);
            }
            groups.add(group);

            apiRepository.update(api);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to add group {} to API {}: {}", group, apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to add group " + group + " to API " + apiId, ex);
        }
    }

    @Override
    public void removeGroup(String apiId, String group) {
        try {
            LOGGER.debug("Remove group {} to API {}", group, apiId);

            Optional<Api> optApi = apiRepository.findById(apiId);

            if (GraviteeContext.getCurrentEnvironment() != null) {
                optApi = optApi.filter(result -> result.getEnvironmentId().equals(GraviteeContext.getCurrentEnvironment()));
            }

            Api api = optApi.orElseThrow(() -> new ApiNotFoundException(apiId));
            if (api.getGroups() != null && api.getGroups().remove(group)) {
                apiRepository.update(api);
            }
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to remove group {} from API {}: {}", group, apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to remove group " + group + " from API " + apiId, ex);
        }
    }

    private ApiEntity updateWorkflowReview(
        final String apiId,
        final String userId,
        final ApiHook hook,
        final WorkflowState workflowState,
        final String workflowMessage
    ) {
        Workflow workflow = workflowService.create(WorkflowReferenceType.API, apiId, REVIEW, userId, workflowState, workflowMessage);
        final ApiEntity apiEntity = findById(apiId);
        apiEntity.setWorkflowState(workflowState);

        final UserEntity user = userService.findById(userId);
        notifierService.trigger(hook, apiId, new NotificationParamsBuilder().api(apiEntity).user(user).build());

        // Find all reviewers of the API and send them a notification email
        if (hook.equals(ApiHook.ASK_FOR_REVIEW)) {
            List<String> reviewersEmail = findAllReviewersEmail(apiId);
            this.emailService.sendAsyncEmailNotification(
                    new EmailNotificationBuilder()
                        .params(new NotificationParamsBuilder().api(apiEntity).user(user).build())
                        .to(reviewersEmail.toArray(new String[reviewersEmail.size()]))
                        .template(EmailNotificationBuilder.EmailTemplate.API_ASK_FOR_REVIEW)
                        .build(),
                    GraviteeContext.getCurrentContext()
                );
        }

        Map<Audit.AuditProperties, String> properties = new HashMap<>();
        properties.put(Audit.AuditProperties.USER, userId);
        properties.put(Audit.AuditProperties.API, apiId);

        Workflow.AuditEvent evtType = null;
        switch (workflowState) {
            case REQUEST_FOR_CHANGES:
                evtType = API_REVIEW_REJECTED;
                break;
            case REVIEW_OK:
                evtType = API_REVIEW_ACCEPTED;
                break;
            default:
                evtType = API_REVIEW_ASKED;
                break;
        }

        auditService.createApiAuditLog(apiId, properties, evtType, new Date(), null, workflow);
        return apiEntity;
    }

    private List<String> findAllReviewersEmail(String apiId) {
        final RolePermissionAction[] acls = { RolePermissionAction.UPDATE };

        // find direct members of the API
        Set<String> reviewerEmails = roleService
            .findByScope(RoleScope.API)
            .stream()
            .filter(role -> this.roleService.hasPermission(role.getPermissions(), ApiPermission.REVIEWS, acls))
            .flatMap(
                role -> this.membershipService.getMembershipsByReferenceAndRole(MembershipReferenceType.API, apiId, role.getId()).stream()
            )
            .filter(m -> m.getMemberType().equals(MembershipMemberType.USER))
            .map(MembershipEntity::getMemberId)
            .distinct()
            .map(this.userService::findById)
            .map(UserEntity::getEmail)
            .filter(Objects::nonNull)
            .collect(toSet());

        // find reviewers in group attached to the API
        final Set<String> groups = this.findById(apiId).getGroups();
        if (groups != null && !groups.isEmpty()) {
            groups.forEach(
                group -> {
                    reviewerEmails.addAll(
                        roleService
                            .findByScope(RoleScope.API)
                            .stream()
                            .filter(role -> this.roleService.hasPermission(role.getPermissions(), ApiPermission.REVIEWS, acls))
                            .flatMap(
                                role ->
                                    this.membershipService.getMembershipsByReferenceAndRole(
                                            MembershipReferenceType.GROUP,
                                            group,
                                            role.getId()
                                        )
                                        .stream()
                            )
                            .filter(m -> m.getMemberType().equals(MembershipMemberType.USER))
                            .map(MembershipEntity::getMemberId)
                            .distinct()
                            .map(this.userService::findById)
                            .map(UserEntity::getEmail)
                            .filter(Objects::nonNull)
                            .collect(toSet())
                    );
                }
            );
        }

        return new ArrayList<>(reviewerEmails);
    }

    private ApiCriteria.Builder queryToCriteria(ApiQuery query) {
        final ApiCriteria.Builder builder = new ApiCriteria.Builder().environmentId(GraviteeContext.getCurrentEnvironment());
        if (query == null) {
            return builder;
        }
        builder.label(query.getLabel()).name(query.getName()).version(query.getVersion());

        if (!isBlank(query.getCategory())) {
            builder.category(categoryService.findById(query.getCategory()).getId());
        }
        if (query.getGroups() != null && !query.getGroups().isEmpty()) {
            builder.groups(query.getGroups().toArray(new String[0]));
        }
        if (!isBlank(query.getState())) {
            builder.state(LifecycleState.valueOf(query.getState()));
        }
        if (query.getVisibility() != null) {
            builder.visibility(Visibility.valueOf(query.getVisibility().name()));
        }
        if (query.getLifecycleStates() != null) {
            builder.lifecycleStates(
                query
                    .getLifecycleStates()
                    .stream()
                    .map(apiLifecycleState -> ApiLifecycleState.valueOf(apiLifecycleState.name()))
                    .collect(toList())
            );
        }
        if (query.getIds() != null && !query.getIds().isEmpty()) {
            builder.ids(query.getIds().toArray(new String[0]));
        }

        if (!isBlank(query.getContextPath())) {
            builder.contextPath(query.getContextPath());
        }
        return builder;
    }

    private void removeTag(String apiId, String tagId) throws TechnicalManagementException {
        try {
            Api api = this.findApiById(apiId, GraviteeContext.getCurrentEnvironment());
            Api previousApi = new Api(api);
            final io.gravitee.definition.model.Api apiDefinition = objectMapper.readValue(
                api.getDefinition(),
                io.gravitee.definition.model.Api.class
            );
            if (apiDefinition.getTags().remove(tagId)) {
                api.setDefinition(objectMapper.writeValueAsString(apiDefinition));
                Api updated = apiRepository.update(api);

                auditService.createApiAuditLog(api.getId(), Collections.emptyMap(), API_UPDATED, api.getUpdatedAt(), previousApi, updated);
            }
        } catch (Exception ex) {
            LOGGER.error("An error occurs while removing tag from API: {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while removing tag from API: " + apiId, ex);
        }
    }

    private ApiEntity updateLifecycle(String apiId, LifecycleState lifecycleState, String username) throws TechnicalException {
        Optional<Api> optApi = apiRepository.findById(apiId);
        if (optApi.isPresent()) {
            Api api = optApi.get();
            Api previousApi = new Api(api);
            api.setUpdatedAt(new Date());
            api.setLifecycleState(lifecycleState);
            ApiEntity apiEntity = convert(apiRepository.update(api), getPrimaryOwner(api.getId()), null);
            // Audit
            auditService.createApiAuditLog(apiId, Collections.emptyMap(), API_UPDATED, api.getUpdatedAt(), previousApi, api);

            EventType eventType = null;
            switch (lifecycleState) {
                case STARTED:
                    eventType = EventType.START_API;
                    break;
                case STOPPED:
                    eventType = EventType.STOP_API;
                    break;
                default:
                    break;
            }
            final ApiEntity deployedApi = deployLastPublishedAPI(apiId, username, eventType);
            if (deployedApi != null) {
                return deployedApi;
            }
            return apiEntity;
        } else {
            throw new ApiNotFoundException(apiId);
        }
    }

    private List<ApiEntity> convert(final List<Api> apis) throws TechnicalException {
        if (apis == null || apis.isEmpty()) {
            return Collections.emptyList();
        }
        RoleEntity primaryOwnerRole = roleService.findPrimaryOwnerRoleByOrganization(
            GraviteeContext.getCurrentOrganization(),
            RoleScope.API
        );
        if (primaryOwnerRole == null) {
            throw new RoleNotFoundException("API_PRIMARY_OWNER");
        }
        //find primary owners usernames of each apis
        final List<String> apiIds = apis.stream().map(Api::getId).collect(toList());

        Set<MemberEntity> memberships = membershipService.getMembersByReferencesAndRole(
            MembershipReferenceType.API,
            apiIds,
            primaryOwnerRole.getId()
        );
        int poMissing = apis.size() - memberships.size();
        Stream<Api> streamApis = apis.stream();
        if (poMissing > 0) {
            Set<String> apiMembershipsIds = memberships.stream().map(MemberEntity::getReferenceId).collect(toSet());

            apiIds.removeAll(apiMembershipsIds);
            Optional<String> optionalApisAsString = apiIds.stream().reduce((a, b) -> a + " / " + b);
            String apisAsString = "?";
            if (optionalApisAsString.isPresent()) {
                apisAsString = optionalApisAsString.get();
            }
            LOGGER.error("{} apis has no identified primary owners in this list {}.", poMissing, apisAsString);
            streamApis = streamApis.filter(api -> !apiIds.contains(api.getId()));
        }

        Map<String, String> apiToMember = new HashMap<>(memberships.size());
        memberships.forEach(membership -> apiToMember.put(membership.getReferenceId(), membership.getId()));

        Map<String, PrimaryOwnerEntity> primaryOwnerIdToPrimaryOwnerEntity = new HashMap<>(memberships.size());
        final List<String> userIds = memberships
            .stream()
            .filter(membership -> membership.getType() == MembershipMemberType.USER)
            .map(MemberEntity::getId)
            .collect(toList());
        if (userIds != null && !userIds.isEmpty()) {
            userService
                .findByIds(userIds)
                .forEach(userEntity -> primaryOwnerIdToPrimaryOwnerEntity.put(userEntity.getId(), new PrimaryOwnerEntity(userEntity)));
        }
        final Set<String> groupIds = memberships
            .stream()
            .filter(membership -> membership.getType() == MembershipMemberType.GROUP)
            .map(MemberEntity::getId)
            .collect(Collectors.toSet());
        if (groupIds != null && !groupIds.isEmpty()) {
            groupService
                .findByIds(groupIds)
                .forEach(groupEntity -> primaryOwnerIdToPrimaryOwnerEntity.put(groupEntity.getId(), new PrimaryOwnerEntity(groupEntity)));
        }

        final List<CategoryEntity> categories = categoryService.findAll();
        return streamApis
            .map(
                publicApi -> this.convert(publicApi, primaryOwnerIdToPrimaryOwnerEntity.get(apiToMember.get(publicApi.getId())), categories)
            )
            .collect(toList());
    }

    private ApiEntity convert(Api api) {
        return convert(api, null, null);
    }

    private LifecycleState convert(EventType eventType) {
        LifecycleState lifecycleState;
        switch (eventType) {
            case START_API:
                lifecycleState = LifecycleState.STARTED;
                break;
            case STOP_API:
                lifecycleState = LifecycleState.STOPPED;
                break;
            default:
                throw new IllegalArgumentException("Unknown EventType " + eventType.toString() + " to convert EventType into Lifecycle");
        }
        return lifecycleState;
    }

    private ProxyModelEntity convert(Proxy proxy) {
        ProxyModelEntity proxyModelEntity = new ProxyModelEntity();

        proxyModelEntity.setCors(proxy.getCors());
        proxyModelEntity.setFailover(proxy.getFailover());
        proxyModelEntity.setGroups(proxy.getGroups());
        proxyModelEntity.setLogging(proxy.getLogging());
        proxyModelEntity.setPreserveHost(proxy.isPreserveHost());
        proxyModelEntity.setStripContextPath(proxy.isStripContextPath());
        proxyModelEntity.setVirtualHosts(proxy.getVirtualHosts());

        //add a default context-path to preserve compatibility on old templates
        if (proxy.getVirtualHosts() != null && !proxy.getVirtualHosts().isEmpty()) {
            proxyModelEntity.setContextPath(proxy.getVirtualHosts().get(0).getPath());
        }

        return proxyModelEntity;
    }

    /*
        Sort then paginate the provided list of apis.
     */
    private Page<Api> sortAndPaginate(List<Api> apis, Sortable sortable, Pageable pageable) {
        Comparator<Api> comparator = buildApiComparator(sortable, pageable, apis);
        pageable = buildPageable(pageable);

        int totalCount = apis.size();
        int startIndex = (pageable.getPageNumber() - 1) * pageable.getPageSize();

        if (pageable.getPageNumber() < 1 || (totalCount > 0 && startIndex >= totalCount)) {
            throw new PaginationInvalidException();
        }

        List<Api> subsetApis = apis.stream().sorted(comparator).skip(startIndex).limit(pageable.getPageSize()).collect(toList());

        return new Page<>(subsetApis, pageable.getPageNumber(), pageable.getPageSize(), apis.size());
    }

    /*
        Handy method to initialize a default pageable if none is provided.
     */
    private Pageable buildPageable(Pageable pageable) {
        if (pageable == null) {
            // No page specified, get all apis in one page.
            return new PageableImpl(1, Integer.MAX_VALUE);
        }

        return pageable;
    }

    /*
        Build and returns a comparator that can be used to sort the provided apis list.
        Depending on the field to compare, it maintains a map of api definitions internally.
        This increase the complexity but avoid unnecessary multiple json deserialization
     */
    private Comparator<Api> buildApiComparator(Sortable sortable, Pageable pageable, List<Api> apis) {
        Comparator<Api> comparator = (api1, api2) -> 0;

        if (pageable != null) {
            // Pagination requires sorting apis to be able to navigate through pages.
            comparator = comparing(api -> api.getName().toLowerCase());
        }

        if (sortable != null) {
            // We only support sorting by name or virtual_hosts. Sort by name by default.
            comparator = comparing(api -> api.getName().toLowerCase());

            if (sortable.getField().equalsIgnoreCase("virtual_hosts")) {
                Map<String, io.gravitee.definition.model.Api> apiDefinitions = new HashMap<>(apis.size());

                apis
                    .stream()
                    .filter(api -> api.getDefinition() != null)
                    .forEach(
                        api -> {
                            try {
                                apiDefinitions.put(
                                    api.getId(),
                                    objectMapper.readValue(api.getDefinition(), io.gravitee.definition.model.Api.class)
                                );
                            } catch (JsonProcessingException e) {
                                // Ignore invalid api definition.
                            }
                        }
                    );

                comparator =
                    (api1, api2) -> {
                        io.gravitee.definition.model.Api apiDefinition1 = apiDefinitions.get(api1.getId());
                        io.gravitee.definition.model.Api apiDefinition2 = apiDefinitions.get(api2.getId());

                        if (apiDefinition1 != null && apiDefinition2 != null) {
                            if (
                                apiDefinition1.getProxy().getVirtualHosts() != null &&
                                !apiDefinition1.getProxy().getVirtualHosts().isEmpty() &&
                                apiDefinition2.getProxy().getVirtualHosts() != null &&
                                !apiDefinition2.getProxy().getVirtualHosts().isEmpty()
                            ) {
                                return apiDefinition1
                                    .getProxy()
                                    .getVirtualHosts()
                                    .get(0)
                                    .getPath()
                                    .toLowerCase()
                                    .compareTo(apiDefinition2.getProxy().getVirtualHosts().get(0).getPath().toLowerCase());
                            }
                        }
                        return api1.getName().toLowerCase().compareTo(api2.getName().toLowerCase());
                    };
            }

            if (!sortable.isAscOrder()) {
                comparator = comparator.reversed();
            }
        }

        return comparator;
    }
}
