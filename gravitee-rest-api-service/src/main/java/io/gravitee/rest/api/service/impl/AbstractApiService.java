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

import static io.gravitee.repository.management.model.Api.AuditEvent.API_CREATED;
import static io.gravitee.repository.management.model.Api.AuditEvent.API_UPDATED;
import static io.gravitee.rest.api.model.WorkflowReferenceType.API;
import static io.gravitee.rest.api.model.WorkflowState.DRAFT;
import static io.gravitee.rest.api.model.WorkflowType.REVIEW;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.common.component.Lifecycle;
import io.gravitee.definition.model.*;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.definition.model.flow.Step;
import io.gravitee.definition.model.services.discovery.EndpointDiscoveryService;
import io.gravitee.definition.model.services.healthcheck.HealthCheckService;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.ApiRepository;
import io.gravitee.repository.management.model.Api;
import io.gravitee.repository.management.model.ApiLifecycleState;
import io.gravitee.repository.management.model.GroupEvent;
import io.gravitee.repository.management.model.LifecycleState;
import io.gravitee.repository.management.model.Workflow;
import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.model.api.ApiEntity;
import io.gravitee.rest.api.model.api.ApiEntrypointEntity;
import io.gravitee.rest.api.model.api.UpdateApiEntity;
import io.gravitee.rest.api.model.notification.GenericNotificationConfigEntity;
import io.gravitee.rest.api.model.parameters.Key;
import io.gravitee.rest.api.model.parameters.ParameterReferenceType;
import io.gravitee.rest.api.model.permissions.RoleScope;
import io.gravitee.rest.api.model.permissions.SystemRole;
import io.gravitee.rest.api.model.settings.ApiPrimaryOwnerMode;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.service.common.GraviteeContext;
import io.gravitee.rest.api.service.common.RandomString;
import io.gravitee.rest.api.service.exceptions.*;
import io.gravitee.rest.api.service.impl.upgrade.DefaultMetadataUpgrader;
import io.gravitee.rest.api.service.notification.ApiHook;
import io.gravitee.rest.api.service.notification.HookScope;
import io.gravitee.rest.api.service.notification.NotificationParamsBuilder;
import io.gravitee.rest.api.service.notification.NotificationTemplateService;
import io.gravitee.rest.api.service.search.SearchEngineService;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.CronTrigger;

/**
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class AbstractApiService extends AbstractService {

    protected final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

    // RFC 6454 section-7.1, serialized-origin regex from RFC 3986
    private static final Pattern CORS_REGEX_PATTERN = Pattern.compile("^((\\*)|(null)|(^(([^:\\/?#]+):)?(\\/\\/([^\\/?#]*))?))$");
    private static final String[] CORS_REGEX_CHARS = new String[] { "{", "[", "(", "*" };
    private static final Pattern DUPLICATE_SLASH_REMOVER = Pattern.compile("(?<!(http:|https:))[//]+");
    private static final String LOGGING_DELIMITER_BASE = "\\s+(\\|\\||\\&\\&)\\s+";
    private static final Pattern LOGGING_MAX_DURATION_PATTERN = Pattern.compile(
        "(?<before>.*)\\#request.timestamp\\s*\\<\\=?\\s*(?<timestamp>\\d*)l(?<after>.*)"
    );
    private static final String LOGGING_MAX_DURATION_CONDITION = "#request.timestamp <= %dl";
    private static final String URI_PATH_SEPARATOR = "/";

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected ApiMetadataService apiMetadataService;

    @Autowired
    protected ApiRepository apiRepository;

    @Autowired
    protected AuditService auditService;

    @Autowired
    protected CategoryService categoryService;

    @Autowired
    private EntrypointService entrypointService;

    @Autowired
    protected GenericNotificationConfigService genericNotificationConfigService;

    @Autowired
    protected GroupService groupService;

    @Autowired
    protected MembershipService membershipService;

    @Autowired
    protected NotificationTemplateService notificationTemplateService;

    @Autowired
    protected NotifierService notifierService;

    @Autowired
    protected PageService pageService;

    @Autowired
    protected ParameterService parameterService;

    @Autowired
    protected PlanService planService;

    @Autowired
    protected PolicyService policyService;

    @Autowired
    protected RoleService roleService;

    @Autowired
    protected SearchEngineService searchEngineService;

    @Autowired
    private TagService tagService;

    @Autowired
    protected UserService userService;

    @Autowired
    protected VirtualHostService virtualHostService;

    @Autowired
    protected WorkflowService workflowService;

    protected void checkGroupExistence(Set<String> groups) {
        // check the existence of groups
        if (groups != null && !groups.isEmpty()) {
            try {
                groupService.findByIds(new HashSet(groups));
            } catch (GroupsNotFoundException gnfe) {
                throw new InvalidDataException("These groups [" + gnfe.getParameters().get("groups") + "] do not exist");
            }
        }
    }

    protected ApiEntity convert(Api api, PrimaryOwnerEntity primaryOwner, List<CategoryEntity> categories) {
        ApiEntity apiEntity = new ApiEntity();

        apiEntity.setId(api.getId());
        apiEntity.setName(api.getName());
        apiEntity.setDeployedAt(api.getDeployedAt());
        apiEntity.setCreatedAt(api.getCreatedAt());
        apiEntity.setGroups(api.getGroups());
        apiEntity.setDisableMembershipNotifications(api.isDisableMembershipNotifications());
        apiEntity.setReferenceType(GraviteeContext.ReferenceContextType.ENVIRONMENT.name());
        apiEntity.setReferenceId(api.getEnvironmentId());

        if (api.getDefinition() != null) {
            try {
                io.gravitee.definition.model.Api apiDefinition = objectMapper.readValue(
                        api.getDefinition(),
                        io.gravitee.definition.model.Api.class
                );

                apiEntity.setProxy(apiDefinition.getProxy());
                apiEntity.setPaths(apiDefinition.getPaths());
                apiEntity.setServices(apiDefinition.getServices());
                apiEntity.setResources(apiDefinition.getResources());
                apiEntity.setProperties(apiDefinition.getProperties());
                apiEntity.setTags(apiDefinition.getTags());
                if (apiDefinition.getDefinitionVersion() != null) {
                    apiEntity.setGraviteeDefinitionVersion(apiDefinition.getDefinitionVersion().getLabel());
                }
                if (apiDefinition.getFlowMode() != null) {
                    apiEntity.setFlowMode(apiDefinition.getFlowMode());
                }
                if (DefinitionVersion.V2.equals(apiDefinition.getDefinitionVersion())) {
                    apiEntity.setFlows(apiDefinition.getFlows());
                    apiEntity.setPlans(new ArrayList<>(apiDefinition.getPlans()));
                } else {
                    apiEntity.setFlows(null);
                    apiEntity.setPlans(null);
                }

                // Issue https://github.com/gravitee-io/issues/issues/3356
                if (apiDefinition.getProxy().getVirtualHosts() != null && !apiDefinition.getProxy().getVirtualHosts().isEmpty()) {
                    apiEntity.setContextPath(apiDefinition.getProxy().getVirtualHosts().get(0).getPath());
                }

                if (apiDefinition.getPathMappings() != null) {
                    apiEntity.setPathMappings(new HashSet<>(apiDefinition.getPathMappings().keySet()));
                }
                apiEntity.setResponseTemplates(apiDefinition.getResponseTemplates());
            } catch (IOException ioe) {
                LOGGER.error("Unexpected error while generating API definition", ioe);
            }
        }

        apiEntity.setUpdatedAt(api.getUpdatedAt());
        apiEntity.setVersion(api.getVersion());
        apiEntity.setDescription(api.getDescription());
        apiEntity.setPicture(api.getPicture());
        apiEntity.setBackground(api.getBackground());
        apiEntity.setLabels(api.getLabels());

        final Set<String> apiCategories = api.getCategories();
        if (apiCategories != null) {
            if (categories == null) {
                categories = categoryService.findAll();
            }
            final Set<String> newApiCategories = new HashSet<>(apiCategories.size());
            for (final String apiView : apiCategories) {
                final Optional<CategoryEntity> optionalView = categories.stream().filter(c -> apiView.equals(c.getId())).findAny();
                optionalView.ifPresent(category -> newApiCategories.add(category.getKey()));
            }
            apiEntity.setCategories(newApiCategories);
        }
        final LifecycleState state = api.getLifecycleState();
        if (state != null) {
            apiEntity.setState(Lifecycle.State.valueOf(state.name()));
        }
        if (api.getVisibility() != null) {
            apiEntity.setVisibility(io.gravitee.rest.api.model.Visibility.valueOf(api.getVisibility().toString()));
        }

        if (primaryOwner != null) {
            apiEntity.setPrimaryOwner(primaryOwner);
        }
        final ApiLifecycleState lifecycleState = api.getApiLifecycleState();
        if (lifecycleState != null) {
            apiEntity.setLifecycleState(io.gravitee.rest.api.model.api.ApiLifecycleState.valueOf(lifecycleState.name()));
        }

        if (parameterService.findAsBoolean(Key.API_REVIEW_ENABLED, api.getEnvironmentId(), ParameterReferenceType.ENVIRONMENT)) {
            final List<Workflow> workflows = workflowService.findByReferenceAndType(API, api.getId(), REVIEW);
            if (workflows != null && !workflows.isEmpty()) {
                apiEntity.setWorkflowState(WorkflowState.valueOf(workflows.get(0).getState()));
            }
        }

        return apiEntity;
    }

    protected UpdateApiEntity convert(final ApiEntity apiEntity) {
        final UpdateApiEntity updateApiEntity = new UpdateApiEntity();
        updateApiEntity.setDescription(apiEntity.getDescription());
        updateApiEntity.setName(apiEntity.getName());
        updateApiEntity.setVersion(apiEntity.getVersion());
        updateApiEntity.setGraviteeDefinitionVersion(apiEntity.getGraviteeDefinitionVersion());
        updateApiEntity.setGroups(apiEntity.getGroups());
        updateApiEntity.setLabels(apiEntity.getLabels());
        updateApiEntity.setLifecycleState(apiEntity.getLifecycleState());
        updateApiEntity.setPicture(apiEntity.getPicture());
        updateApiEntity.setBackground(apiEntity.getBackground());
        updateApiEntity.setProperties(apiEntity.getProperties());
        updateApiEntity.setProxy(apiEntity.getProxy());
        updateApiEntity.setResources(apiEntity.getResources());
        updateApiEntity.setResponseTemplates(apiEntity.getResponseTemplates());
        updateApiEntity.setServices(apiEntity.getServices());
        updateApiEntity.setTags(apiEntity.getTags());
        updateApiEntity.setCategories(apiEntity.getCategories());
        updateApiEntity.setVisibility(apiEntity.getVisibility());
        updateApiEntity.setPaths(apiEntity.getPaths());
        updateApiEntity.setFlows(apiEntity.getFlows());
        updateApiEntity.setPathMappings(apiEntity.getPathMappings());
        updateApiEntity.setDisableMembershipNotifications(apiEntity.isDisableMembershipNotifications());
        updateApiEntity.setPlans(apiEntity.getPlans());
        return updateApiEntity;
    }

    protected ApiEntity create0(
        UpdateApiEntity api,
        String userId,
        boolean createSystemFolder,
        JsonNode apiDefinition,
        String currentEnvironment,
        String apiId
    ) throws ApiAlreadyExistsException {
        try {
            LOGGER.debug("Create {} for user {}", api, userId);

            String id = apiId != null && UUID.fromString(apiId) != null ? apiId : RandomString.generate();

            Optional<Api> checkApi = apiRepository.findById(id);
            if (checkApi.isPresent()) {
                final String environmentId = checkApi.get().getEnvironmentId();
                if (environmentId.equals(currentEnvironment)) {
                    // We are trying to create an API that exists in the current environment
                    throw new ApiAlreadyExistsException(id);
                }
                // compute another apiId based on the currentEnvironment
                id = UUID.nameUUIDFromBytes((id + currentEnvironment).getBytes()).toString();
            }

            // if user changes sharding tags, then check if he is allowed to do it
            checkShardingTags(api, null);

            // format context-path and check if context path is unique
            final Collection<VirtualHost> sanitizedVirtualHosts = virtualHostService.sanitizeAndValidate(api.getProxy().getVirtualHosts(), currentEnvironment);
            api.getProxy().setVirtualHosts(new ArrayList<>(sanitizedVirtualHosts));

            // check endpoints name
            checkEndpointsName(api);

            // check HC inheritance
            checkHealthcheckInheritance(api);

            addLoggingMaxDuration(api.getProxy().getLogging());

            // check if there is regex errors in plaintext fields
            validateRegexfields(api);

            // check policy configurations.
            checkPolicyConfigurations(api);

            // check primary owner
            PrimaryOwnerEntity primaryOwner = findPrimaryOwner(apiDefinition, userId);

            if (apiDefinition != null) {
                apiDefinition = ((ObjectNode) apiDefinition).put("id", id);
            }

            Api repoApi = convert(id, api, apiDefinition != null ? apiDefinition.toString() : null);

            if (repoApi != null) {
                repoApi.setId(id);
                repoApi.setEnvironmentId(currentEnvironment);
                // Set date fields
                repoApi.setCreatedAt(new Date());
                repoApi.setUpdatedAt(repoApi.getCreatedAt());
                // Be sure that lifecycle is set to STOPPED by default and visibility is private
                repoApi.setLifecycleState(LifecycleState.STOPPED);

                if (api.getVisibility() == null) {
                    repoApi.setVisibility(io.gravitee.repository.management.model.Visibility.PRIVATE);
                } else {
                    repoApi.setVisibility(io.gravitee.repository.management.model.Visibility.valueOf(api.getVisibility().toString()));
                }

                // Add Default groups
                Set<String> defaultGroups = groupService
                    .findByEvent(GroupEvent.API_CREATE)
                    .stream()
                    .map(GroupEntity::getId)
                    .collect(toSet());
                if (!defaultGroups.isEmpty() && repoApi.getGroups() == null) {
                    repoApi.setGroups(defaultGroups);
                } else if (!defaultGroups.isEmpty()) {
                    repoApi.getGroups().addAll(defaultGroups);
                }

                // if po is a group, add it as a member of the API
                if (ApiPrimaryOwnerMode.GROUP.name().equals(primaryOwner.getType())) {
                    if (repoApi.getGroups() == null) {
                        repoApi.setGroups(new HashSet<>());
                    }
                    repoApi.getGroups().add(primaryOwner.getId());
                }

                repoApi.setApiLifecycleState(ApiLifecycleState.CREATED);
                if (parameterService.findAsBoolean(Key.API_REVIEW_ENABLED, ParameterReferenceType.ENVIRONMENT)) {
                    workflowService.create(WorkflowReferenceType.API, id, REVIEW, userId, DRAFT, "");
                }

                Api createdApi = apiRepository.create(repoApi);

                if (createSystemFolder) {
                    createSystemFolder(createdApi.getId());
                }

                // Audit
                auditService.createApiAuditLog(
                    createdApi.getId(),
                    Collections.emptyMap(),
                    API_CREATED,
                    createdApi.getCreatedAt(),
                    null,
                    createdApi
                );

                // Add the primary owner of the newly created API
                if (primaryOwner != null) {
                    membershipService.addRoleToMemberOnReference(
                        new MembershipService.MembershipReference(MembershipReferenceType.API, createdApi.getId()),
                        new MembershipService.MembershipMember(
                            primaryOwner.getId(),
                            null,
                            MembershipMemberType.valueOf(primaryOwner.getType())
                        ),
                        new MembershipService.MembershipRole(RoleScope.API, SystemRole.PRIMARY_OWNER.name())
                    );

                    // create the default mail notification
                    final String emailMetadataValue = "${(api.primaryOwner.email)!''}";

                    GenericNotificationConfigEntity notificationConfigEntity = new GenericNotificationConfigEntity();
                    notificationConfigEntity.setName("Default Mail Notifications");
                    notificationConfigEntity.setReferenceType(HookScope.API.name());
                    notificationConfigEntity.setReferenceId(createdApi.getId());
                    notificationConfigEntity.setHooks(Arrays.stream(ApiHook.values()).map(Enum::name).collect(toList()));
                    notificationConfigEntity.setNotifier(NotifierServiceImpl.DEFAULT_EMAIL_NOTIFIER_ID);
                    notificationConfigEntity.setConfig(emailMetadataValue);
                    genericNotificationConfigService.create(notificationConfigEntity);

                    // create the default mail support metadata
                    NewApiMetadataEntity newApiMetadataEntity = new NewApiMetadataEntity();
                    newApiMetadataEntity.setFormat(MetadataFormat.MAIL);
                    newApiMetadataEntity.setName(DefaultMetadataUpgrader.METADATA_EMAIL_SUPPORT_KEY);
                    newApiMetadataEntity.setDefaultValue(emailMetadataValue);
                    newApiMetadataEntity.setValue(emailMetadataValue);
                    newApiMetadataEntity.setApiId(createdApi.getId());
                    apiMetadataService.create(newApiMetadataEntity);

                    //TODO add membership log
                    ApiEntity apiEntity = convert(createdApi, primaryOwner, null);
                    ApiEntity apiWithMetadata = fetchMetadataForApi(apiEntity);

                    searchEngineService.index(apiWithMetadata, false);
                    return apiEntity;
                } else {
                    LOGGER.error("Unable to create API {} because primary owner role has not been found.", createdApi.getName());
                    throw new TechnicalManagementException(
                        "Unable to create API " + createdApi.getName() + " because primary owner role has not been found"
                    );
                }
            } else {
                LOGGER.error("Unable to create API {} because of previous error.", api.getName());
                throw new TechnicalManagementException("Unable to create API " + api.getName());
            }
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to create {} for user {}", api, userId, ex);
            throw new TechnicalManagementException("An error occurs while trying create " + api + " for user " + userId, ex);
        }
    }

    protected void createSystemFolder(String apiId) {
        NewPageEntity asideSystemFolder = new NewPageEntity();
        asideSystemFolder.setName(SystemFolderType.ASIDE.folderName());
        asideSystemFolder.setPublished(true);
        asideSystemFolder.setType(PageType.SYSTEM_FOLDER);
        asideSystemFolder.setVisibility(io.gravitee.rest.api.model.Visibility.PUBLIC);
        pageService.createPage(apiId, asideSystemFolder, GraviteeContext.getCurrentEnvironment());
    }

    protected ApiEntity fetchMetadataForApi(ApiEntity apiEntity) {
        List<ApiMetadataEntity> metadataList = apiMetadataService.findAllByApi(apiEntity.getId());
        final Map<String, Object> mapMetadata = new HashMap<>(metadataList.size());

        metadataList.forEach(
            metadata -> mapMetadata.put(metadata.getKey(), metadata.getValue() == null ? metadata.getDefaultValue() : metadata.getValue())
        );

        String decodedValue =
            this.notificationTemplateService.resolveInlineTemplateWithParam(
                    apiEntity.getId(),
                    new StringReader(mapMetadata.toString()),
                    Collections.singletonMap("api", apiEntity)
                );
        Map<String, Object> metadataDecoded = Arrays
            .stream(decodedValue.substring(1, decodedValue.length() - 1).split(", "))
            .map(entry -> entry.split("="))
            .collect(Collectors.toMap(entry -> entry[0], entry -> entry.length > 1 ? entry[1] : ""));
        apiEntity.setMetadata(metadataDecoded);

        return apiEntity;
    }

    protected Api findApiById(String apiId, String currentEnvironment) {
        try {
            LOGGER.debug("Find API by ID: {}", apiId);

            Optional<Api> optApi = apiRepository.findById(apiId);

            if (currentEnvironment != null) {
                optApi = optApi.filter(result -> result.getEnvironmentId().equals(currentEnvironment));
            }

            if (optApi.isPresent()) {
                return optApi.get();
            }

            throw new ApiNotFoundException(apiId);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find an API using its ID: {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to find an API using its ID: " + apiId, ex);
        }
    }

    protected ApiEntity findById(String apiId) {
        final Api api = this.findApiById(apiId, GraviteeContext.getCurrentEnvironment());
        ApiEntity apiEntity = convert(api, getPrimaryOwner(api.getId()), null);

        // Compute entrypoints
        calculateEntrypoints(apiEntity, api.getEnvironmentId());

        return apiEntity;
    }

    protected PrimaryOwnerEntity getPrimaryOwner(String apiId) throws TechnicalManagementException {
        MembershipEntity primaryOwnerMemberEntity = membershipService.getPrimaryOwner(
            io.gravitee.rest.api.model.MembershipReferenceType.API,
            apiId
        );
        if (primaryOwnerMemberEntity == null) {
            LOGGER.error("The API {} doesn't have any primary owner.", apiId);
            throw new TechnicalManagementException("The API " + apiId + " doesn't have any primary owner.");
        }
        if (MembershipMemberType.GROUP == primaryOwnerMemberEntity.getMemberType()) {
            return new PrimaryOwnerEntity(groupService.findById(primaryOwnerMemberEntity.getMemberId()));
        }
        return new PrimaryOwnerEntity(userService.findById(primaryOwnerMemberEntity.getMemberId()));
    }

    protected Set<String> removePOGroups(Set<String> groups, String apiId) {
        Stream<GroupEntity> groupEntityStream = groupService.findByIds(groups).stream();

        if (apiId != null) {
            final MembershipEntity primaryOwner = membershipService.getPrimaryOwner(MembershipReferenceType.API, apiId);
            if (primaryOwner.getMemberType() == MembershipMemberType.GROUP) {
                // don't remove the primary owner group of this API.
                groupEntityStream =
                    groupEntityStream.filter(
                        group -> StringUtils.isEmpty(group.getApiPrimaryOwner()) || group.getId().equals(primaryOwner.getMemberId())
                    );
            } else {
                groupEntityStream = groupEntityStream.filter(group -> StringUtils.isEmpty(group.getApiPrimaryOwner()));
            }
        } else {
            groupEntityStream = groupEntityStream.filter(group -> StringUtils.isEmpty(group.getApiPrimaryOwner()));
        }

        return groupEntityStream.map(GroupEntity::getId).collect(Collectors.toSet());
    }

    protected ApiEntity update(String apiId, UpdateApiEntity updateApiEntity, boolean checkPlans) {
        try {
            LOGGER.debug("Update API {}", apiId);

            Optional<Api> optApiToUpdate = apiRepository.findById(apiId);
            if (!optApiToUpdate.isPresent()) {
                throw new ApiNotFoundException(apiId);
            }

            // check if entrypoints are unique
            final Collection<VirtualHost> sanitizedVirtualHosts = virtualHostService.sanitizeAndValidate(
                updateApiEntity.getProxy().getVirtualHosts(),
                apiId, GraviteeContext.getCurrentEnvironment()
            );
            updateApiEntity.getProxy().setVirtualHosts(new ArrayList<>(sanitizedVirtualHosts));

            // check endpoints presence
            checkEndpointsExists(updateApiEntity);

            // check endpoints name
            checkEndpointsName(updateApiEntity);

            // check HC inheritance
            checkHealthcheckInheritance(updateApiEntity);

            // validate HC cron schedule
            validateHealtcheckSchedule(updateApiEntity);

            // check CORS Allow-origin format
            checkAllowOriginFormat(updateApiEntity);

            addLoggingMaxDuration(updateApiEntity.getProxy().getLogging());

            // check if there is regex errors in plaintext fields
            validateRegexfields(updateApiEntity);

            // check policy configurations.
            checkPolicyConfigurations(updateApiEntity);

            final ApiEntity apiToCheck = convert(optApiToUpdate.get(), null, null);

            // if user changes sharding tags, then check if he is allowed to do it
            checkShardingTags(updateApiEntity, apiToCheck);

            // if lifecycle state not provided, set the saved one
            if (updateApiEntity.getLifecycleState() == null) {
                updateApiEntity.setLifecycleState(apiToCheck.getLifecycleState());
            }

            // check lifecycle state
            checkLifecycleState(updateApiEntity, apiToCheck);

            Set<String> groups = updateApiEntity.getGroups();
            if (groups != null && !groups.isEmpty()) {
                // check the existence of groups
                checkGroupExistence(groups);

                // remove PO group if exists
                groups = removePOGroups(groups, apiId);
                updateApiEntity.setGroups(groups);
            }

            // add a default path, if version is v1
            if (
                Objects.equals(updateApiEntity.getGraviteeDefinitionVersion(), DefinitionVersion.V1.getLabel()) &&
                (updateApiEntity.getPaths() == null || updateApiEntity.getPaths().isEmpty())
            ) {
                updateApiEntity.setPaths(singletonMap("/", new ArrayList<>()));
            }

            if (updateApiEntity.getPlans() == null) {
                updateApiEntity.setPlans(new ArrayList<>());
            } else if (checkPlans) {
                List<Plan> existingPlans = apiToCheck.getPlans();
                Map<String, String> planStatuses = new HashMap<>();
                if (existingPlans != null && !existingPlans.isEmpty()) {
                    planStatuses.putAll(existingPlans.stream().collect(toMap(Plan::getId, Plan::getStatus)));
                }

                updateApiEntity
                    .getPlans()
                    .forEach(
                        planToUpdate -> {
                            if (
                                !planStatuses.containsKey(planToUpdate.getId()) ||
                                (
                                    planStatuses.containsKey(planToUpdate.getId()) &&
                                    planStatuses.get(planToUpdate.getId()).equalsIgnoreCase(PlanStatus.CLOSED.name()) &&
                                    !planStatuses.get(planToUpdate.getId()).equalsIgnoreCase(planToUpdate.getStatus())
                                )
                            ) {
                                throw new InvalidDataException("Invalid status for plan '" + planToUpdate.getName() + "'");
                            }
                        }
                    );
            }

            Api apiToUpdate = optApiToUpdate.get();

            if (io.gravitee.rest.api.model.api.ApiLifecycleState.DEPRECATED.equals(updateApiEntity.getLifecycleState())) {
                planService
                    .findByApi(apiId)
                    .forEach(
                        plan -> {
                            if (PlanStatus.PUBLISHED.equals(plan.getStatus()) || PlanStatus.STAGING.equals(plan.getStatus())) {
                                planService.deprecate(plan.getId(), true);
                                updateApiEntity
                                    .getPlans()
                                    .stream()
                                    .filter(p -> p.getId().equals(plan.getId()))
                                    .forEach(p -> p.setStatus(PlanStatus.DEPRECATED.name()));
                            }
                        }
                    );
            }

            Api api = convert(apiId, updateApiEntity, apiToUpdate.getDefinition());

            if (api != null) {
                api.setId(apiId.trim());
                api.setUpdatedAt(new Date());

                // Copy fields from existing values
                api.setEnvironmentId(apiToUpdate.getEnvironmentId());
                api.setDeployedAt(apiToUpdate.getDeployedAt());
                api.setCreatedAt(apiToUpdate.getCreatedAt());
                api.setLifecycleState(apiToUpdate.getLifecycleState());
                // If no new picture and the current picture url is not the default one, keep the current picture
                if (
                    updateApiEntity.getPicture() == null &&
                    updateApiEntity.getPictureUrl() != null &&
                    updateApiEntity.getPictureUrl().indexOf("?hash") > 0
                ) {
                    api.setPicture(apiToUpdate.getPicture());
                }
                if (
                    updateApiEntity.getBackground() == null &&
                    updateApiEntity.getBackgroundUrl() != null &&
                    updateApiEntity.getBackgroundUrl().indexOf("?hash") > 0
                ) {
                    api.setBackground(apiToUpdate.getBackground());
                }
                if (updateApiEntity.getGroups() == null) {
                    api.setGroups(apiToUpdate.getGroups());
                }
                if (updateApiEntity.getLabels() == null && apiToUpdate.getLabels() != null) {
                    api.setLabels(new ArrayList<>(new HashSet<>(apiToUpdate.getLabels())));
                }
                if (updateApiEntity.getCategories() == null) {
                    api.setCategories(apiToUpdate.getCategories());
                }

                if (ApiLifecycleState.DEPRECATED.equals(api.getApiLifecycleState())) {
                    notifierService.trigger(
                        ApiHook.API_DEPRECATED,
                        apiId,
                        new NotificationParamsBuilder().api(apiToCheck).user(userService.findById(getAuthenticatedUsername())).build()
                    );
                }

                Api updatedApi = apiRepository.update(api);

                // Audit
                auditService.createApiAuditLog(
                    updatedApi.getId(),
                    Collections.emptyMap(),
                    API_UPDATED,
                    updatedApi.getUpdatedAt(),
                    apiToUpdate,
                    updatedApi
                );

                if (parameterService.findAsBoolean(Key.LOGGING_AUDIT_TRAIL_ENABLED, ParameterReferenceType.ENVIRONMENT)) {
                    // Audit API logging if option is enabled
                    auditApiLogging(apiToUpdate, updatedApi);
                }

                ApiEntity apiEntity = convert(singletonList(updatedApi)).iterator().next();
                ApiEntity apiWithMetadata = fetchMetadataForApi(apiEntity);

                searchEngineService.index(apiWithMetadata, false);

                return apiEntity;
            } else {
                LOGGER.error("Unable to update API {} because of previous error.", apiId);
                throw new TechnicalManagementException("Unable to update API " + apiId);
            }
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to update API {}", apiId, ex);
            throw new TechnicalManagementException("An error occurs while trying to update API " + apiId, ex);
        }
    }

    private void addLoggingMaxDuration(Logging logging) {
        if (logging != null && !LoggingMode.NONE.equals(logging.getMode())) {
            Optional<Long> optionalMaxDuration = parameterService
                .findAll(Key.LOGGING_DEFAULT_MAX_DURATION, Long::valueOf, ParameterReferenceType.ORGANIZATION)
                .stream()
                .findFirst();
            if (optionalMaxDuration.isPresent() && optionalMaxDuration.get() > 0) {
                long maxEndDate = System.currentTimeMillis() + optionalMaxDuration.get();

                // if no condition set, add one
                if (logging.getCondition() == null || logging.getCondition().isEmpty()) {
                    logging.setCondition(String.format(LOGGING_MAX_DURATION_CONDITION, maxEndDate));
                } else {
                    Matcher matcher = LOGGING_MAX_DURATION_PATTERN.matcher(logging.getCondition());
                    if (matcher.matches()) {
                        String currentDurationAsStr = matcher.group("timestamp");
                        String before = formatExpression(matcher, "before");
                        String after = formatExpression(matcher, "after");
                        try {
                            final long currentDuration = Long.parseLong(currentDurationAsStr);
                            if (currentDuration > maxEndDate || (!before.isEmpty() || !after.isEmpty())) {
                                logging.setCondition(before + String.format(LOGGING_MAX_DURATION_CONDITION, maxEndDate) + after);
                            }
                        } catch (NumberFormatException nfe) {
                            LOGGER.error("Wrong format of the logging condition. Add the default one", nfe);
                            logging.setCondition(before + String.format(LOGGING_MAX_DURATION_CONDITION, maxEndDate) + after);
                        }
                    } else {
                        logging.setCondition(String.format(LOGGING_MAX_DURATION_CONDITION, maxEndDate) + " && " + logging.getCondition());
                    }
                }
            }
        }
    }

    private void assertEndpointNameNotContainsInvalidCharacters(String name) {
        if (name != null && name.contains(":")) {
            throw new EndpointNameInvalidException(name);
        }
    }

    private void auditApiLogging(Api apiToUpdate, Api apiUpdated) {
        try {
            // get old logging configuration
            io.gravitee.definition.model.Api apiToUpdateDefinition = objectMapper.readValue(
                apiToUpdate.getDefinition(),
                io.gravitee.definition.model.Api.class
            );
            Logging loggingToUpdate = apiToUpdateDefinition.getProxy().getLogging();

            // get new logging configuration
            io.gravitee.definition.model.Api apiUpdatedDefinition = objectMapper.readValue(
                apiUpdated.getDefinition(),
                io.gravitee.definition.model.Api.class
            );
            Logging loggingUpdated = apiUpdatedDefinition.getProxy().getLogging();

            // no changes for logging configuration, continue
            if (
                loggingToUpdate == loggingUpdated ||
                (
                    loggingToUpdate != null &&
                    loggingUpdated != null &&
                    Objects.equals(loggingToUpdate.getMode(), loggingUpdated.getMode()) &&
                    Objects.equals(loggingToUpdate.getCondition(), loggingUpdated.getCondition())
                )
            ) {
                return;
            }

            // determine the audit event type
            Api.AuditEvent auditEvent;
            if (
                (loggingToUpdate == null || loggingToUpdate.getMode().equals(LoggingMode.NONE)) &&
                (!loggingUpdated.getMode().equals(LoggingMode.NONE))
            ) {
                auditEvent = Api.AuditEvent.API_LOGGING_ENABLED;
            } else if (
                (loggingToUpdate != null && !loggingToUpdate.getMode().equals(LoggingMode.NONE)) &&
                (loggingUpdated.getMode().equals(LoggingMode.NONE))
            ) {
                auditEvent = Api.AuditEvent.API_LOGGING_DISABLED;
            } else {
                auditEvent = Api.AuditEvent.API_LOGGING_UPDATED;
            }

            // Audit
            auditService.createApiAuditLog(
                apiUpdated.getId(),
                Collections.emptyMap(),
                auditEvent,
                new Date(),
                loggingToUpdate,
                loggingUpdated
            );
        } catch (Exception ex) {
            LOGGER.error("An error occurs while auditing API logging configuration for API: {}", apiUpdated.getId(), ex);
            throw new TechnicalManagementException(
                "An error occurs while auditing API logging configuration for API: " + apiUpdated.getId(),
                ex
            );
        }
    }

    private String buildApiDefinition(String apiId, String apiDefinition, UpdateApiEntity updateApiEntity) {
        try {
            io.gravitee.definition.model.Api updateApiDefinition;
            if (apiDefinition == null || apiDefinition.isEmpty()) {
                updateApiDefinition = new io.gravitee.definition.model.Api();
                updateApiDefinition.setDefinitionVersion(DefinitionVersion.valueOfLabel(updateApiEntity.getGraviteeDefinitionVersion()));
            } else {
                updateApiDefinition = objectMapper.readValue(apiDefinition, io.gravitee.definition.model.Api.class);
            }
            updateApiDefinition.setId(apiId);
            updateApiDefinition.setName(updateApiEntity.getName());
            updateApiDefinition.setVersion(updateApiEntity.getVersion());
            updateApiDefinition.setProxy(updateApiEntity.getProxy());

            if (StringUtils.isNotEmpty(updateApiEntity.getGraviteeDefinitionVersion())) {
                updateApiDefinition.setDefinitionVersion(DefinitionVersion.valueOfLabel(updateApiEntity.getGraviteeDefinitionVersion()));
            }

            if (updateApiEntity.getFlowMode() != null) {
                updateApiDefinition.setFlowMode(updateApiEntity.getFlowMode());
            }

            if (updateApiEntity.getPaths() != null) {
                updateApiDefinition.setPaths(updateApiEntity.getPaths());
            }
            if (updateApiEntity.getPathMappings() != null) {
                updateApiDefinition.setPathMappings(
                    updateApiEntity
                        .getPathMappings()
                        .stream()
                        .collect(toMap(pathMapping -> pathMapping, pathMapping -> Pattern.compile("")))
                );
            }
            if (updateApiEntity.getFlows() != null) {
                updateApiDefinition.setFlows(updateApiEntity.getFlows());
            }
            if (updateApiEntity.getPlans() != null) {
                List<Plan> plans = updateApiEntity.getPlans().stream().filter(plan -> plan.getId() != null).collect(toList());
                updateApiDefinition.setPlans(plans);
            }

            updateApiDefinition.setServices(updateApiEntity.getServices());
            updateApiDefinition.setResources(updateApiEntity.getResources());
            updateApiDefinition.setProperties(updateApiEntity.getProperties());
            updateApiDefinition.setTags(updateApiEntity.getTags());

            updateApiDefinition.setResponseTemplates(updateApiEntity.getResponseTemplates());
            return objectMapper.writeValueAsString(updateApiDefinition);
        } catch (JsonProcessingException jse) {
            LOGGER.error("Unexpected error while generating API definition", jse);
            throw new TechnicalManagementException("An error occurs while trying to parse API definition " + jse);
        }
    }

    private void calculateEntrypoints(ApiEntity api, String environmentId) {
        List<ApiEntrypointEntity> apiEntrypoints = new ArrayList<>();

        if (api.getProxy() != null) {
            String defaultEntrypoint = parameterService.find(Key.PORTAL_ENTRYPOINT, environmentId, ParameterReferenceType.ENVIRONMENT);
            final String scheme = getScheme(defaultEntrypoint);
            if (api.getTags() != null && !api.getTags().isEmpty()) {
                List<EntrypointEntity> entrypoints = entrypointService.findByReference(
                    GraviteeContext.getCurrentOrganization(),
                    EntrypointReferenceType.ORGANIZATION
                );
                entrypoints.forEach(
                    entrypoint -> {
                        Set<String> tagEntrypoints = new HashSet<>(Arrays.asList(entrypoint.getTags()));
                        tagEntrypoints.retainAll(api.getTags());

                        if (tagEntrypoints.size() == entrypoint.getTags().length) {
                            api
                                .getProxy()
                                .getVirtualHosts()
                                .forEach(
                                    virtualHost -> {
                                        String targetHost = (virtualHost.getHost() == null || !virtualHost.isOverrideEntrypoint())
                                            ? entrypoint.getValue()
                                            : virtualHost.getHost();
                                        if (!targetHost.toLowerCase().startsWith("http")) {
                                            targetHost = scheme + "://" + targetHost;
                                        }
                                        apiEntrypoints.add(
                                            new ApiEntrypointEntity(
                                                tagEntrypoints,
                                                DUPLICATE_SLASH_REMOVER
                                                    .matcher(targetHost + URI_PATH_SEPARATOR + virtualHost.getPath())
                                                    .replaceAll(URI_PATH_SEPARATOR),
                                                virtualHost.getHost()
                                            )
                                        );
                                    }
                                );
                        }
                    }
                );
            }

            // If empty, get the default entrypoint
            if (apiEntrypoints.isEmpty()) {
                api
                    .getProxy()
                    .getVirtualHosts()
                    .forEach(
                        virtualHost -> {
                            String targetHost = (virtualHost.getHost() == null || !virtualHost.isOverrideEntrypoint())
                                ? defaultEntrypoint
                                : virtualHost.getHost();
                            if (!targetHost.toLowerCase().startsWith("http")) {
                                targetHost = scheme + "://" + targetHost;
                            }
                            apiEntrypoints.add(
                                new ApiEntrypointEntity(
                                    DUPLICATE_SLASH_REMOVER
                                        .matcher(targetHost + URI_PATH_SEPARATOR + virtualHost.getPath())
                                        .replaceAll(URI_PATH_SEPARATOR),
                                    virtualHost.getHost()
                                )
                            );
                        }
                    );
            }
        }

        api.setEntrypoints(apiEntrypoints);
    }

    private void checkAllowOriginFormat(UpdateApiEntity updateApiEntity) {
        if (updateApiEntity.getProxy() != null && updateApiEntity.getProxy().getCors() != null) {
            final Set<String> accessControlAllowOrigin = updateApiEntity.getProxy().getCors().getAccessControlAllowOrigin();
            if (accessControlAllowOrigin != null && !accessControlAllowOrigin.isEmpty()) {
                for (String allowOriginItem : accessControlAllowOrigin) {
                    if (!CORS_REGEX_PATTERN.matcher(allowOriginItem).matches()) {
                        if (StringUtils.indexOfAny(allowOriginItem, CORS_REGEX_CHARS) >= 0) {
                            try {
                                //the origin could be a regex
                                Pattern.compile(allowOriginItem);
                            } catch (PatternSyntaxException e) {
                                throw new AllowOriginNotAllowedException(allowOriginItem);
                            }
                        } else {
                            throw new AllowOriginNotAllowedException(allowOriginItem);
                        }
                    }
                }
            }
        }
    }

    private void checkEndpointsExists(UpdateApiEntity api) {
        if (api.getProxy().getGroups() == null || api.getProxy().getGroups().isEmpty()) {
            throw new EndpointMissingException();
        }

        EndpointGroup endpointGroup = api.getProxy().getGroups().iterator().next();
        //Is service discovery enabled ?
        EndpointDiscoveryService endpointDiscoveryService = endpointGroup.getServices() == null
            ? null
            : endpointGroup.getServices().get(EndpointDiscoveryService.class);
        if (
            (endpointDiscoveryService == null || !endpointDiscoveryService.isEnabled()) &&
            (endpointGroup.getEndpoints() == null || endpointGroup.getEndpoints().isEmpty())
        ) {
            throw new EndpointMissingException();
        }
    }

    private void checkEndpointsName(UpdateApiEntity api) {
        if (api.getProxy() != null && api.getProxy().getGroups() != null) {
            for (EndpointGroup group : api.getProxy().getGroups()) {
                assertEndpointNameNotContainsInvalidCharacters(group.getName());
                if (group.getEndpoints() != null) {
                    for (Endpoint endpoint : group.getEndpoints()) {
                        assertEndpointNameNotContainsInvalidCharacters(endpoint.getName());
                    }
                }
            }
        }
    }

    private void checkHealthcheckInheritance(UpdateApiEntity api) {
        boolean inherit = false;

        if (api.getProxy() != null && api.getProxy().getGroups() != null) {
            for (EndpointGroup group : api.getProxy().getGroups()) {
                if (group.getEndpoints() != null) {
                    for (Endpoint endpoint : group.getEndpoints()) {
                        if (endpoint instanceof HttpEndpoint) {
                            HttpEndpoint httpEndpoint = (HttpEndpoint) endpoint;
                            if (httpEndpoint.getHealthCheck() != null && httpEndpoint.getHealthCheck().isInherit()) {
                                inherit = true;
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (inherit) {
            //if endpoints are set to inherit HC configuration, this configuration must exists.
            boolean hcServiceExists = false;
            if (api.getServices() != null) {
                io.gravitee.definition.model.services.healthcheck.HealthCheckService healthCheckService = api
                    .getServices()
                    .get(HealthCheckService.class);
                hcServiceExists = healthCheckService != null;
            }

            if (!hcServiceExists) {
                throw new HealthcheckInheritanceException();
            }
        }
    }

    private void checkLifecycleState(final UpdateApiEntity updateApiEntity, final ApiEntity existingAPI) {
        if (io.gravitee.rest.api.model.api.ApiLifecycleState.DEPRECATED.equals(existingAPI.getLifecycleState())) {
            throw new LifecycleStateChangeNotAllowedException(updateApiEntity.getLifecycleState().name());
        }
        if (existingAPI.getLifecycleState().name().equals(updateApiEntity.getLifecycleState().name())) {
            return;
        }
        if (io.gravitee.rest.api.model.api.ApiLifecycleState.ARCHIVED.equals(existingAPI.getLifecycleState())) {
            if (!io.gravitee.rest.api.model.api.ApiLifecycleState.ARCHIVED.equals(updateApiEntity.getLifecycleState())) {
                throw new LifecycleStateChangeNotAllowedException(updateApiEntity.getLifecycleState().name());
            }
        } else if (io.gravitee.rest.api.model.api.ApiLifecycleState.UNPUBLISHED.equals(existingAPI.getLifecycleState())) {
            if (io.gravitee.rest.api.model.api.ApiLifecycleState.CREATED.equals(updateApiEntity.getLifecycleState())) {
                throw new LifecycleStateChangeNotAllowedException(updateApiEntity.getLifecycleState().name());
            }
        } else if (io.gravitee.rest.api.model.api.ApiLifecycleState.CREATED.equals(existingAPI.getLifecycleState())) {
            if (WorkflowState.IN_REVIEW.equals(existingAPI.getWorkflowState())) {
                throw new LifecycleStateChangeNotAllowedException(updateApiEntity.getLifecycleState().name());
            }
        }
    }

    private void checkPolicyConfigurations(final UpdateApiEntity updateApiEntity) {
        if (updateApiEntity.getPaths() != null) {
            updateApiEntity
                .getPaths()
                .forEach(
                    (s, rules) ->
                        rules
                            .stream()
                            .filter(Rule::isEnabled)
                            .map(Rule::getPolicy)
                            .forEach(policy -> policyService.validatePolicyConfiguration(policy))
                );
        }
        if (updateApiEntity.getFlows() != null) {
            updateApiEntity
                .getFlows()
                .stream()
                .filter(flow -> flow.getPre() != null)
                .forEach(
                    flow -> flow.getPre().stream().filter(Step::isEnabled).forEach(step -> policyService.validatePolicyConfiguration(step))
                );

            updateApiEntity
                .getFlows()
                .stream()
                .filter(flow -> flow.getPost() != null)
                .forEach(
                    flow -> flow.getPost().stream().filter(Step::isEnabled).forEach(step -> policyService.validatePolicyConfiguration(step))
                );
        }
    }

    private void checkShardingTags(final UpdateApiEntity updateApiEntity, final ApiEntity existingAPI) {
        final Set<String> tagsToUpdate = updateApiEntity.getTags() == null ? new HashSet<>() : updateApiEntity.getTags();
        final Set<String> updatedTags;
        if (existingAPI == null) {
            updatedTags = tagsToUpdate;
        } else {
            final Set<String> existingAPITags = existingAPI.getTags() == null ? new HashSet<>() : existingAPI.getTags();
            updatedTags = existingAPITags.stream().filter(tag -> !tagsToUpdate.contains(tag)).collect(toSet());
            updatedTags.addAll(tagsToUpdate.stream().filter(tag -> !existingAPITags.contains(tag)).collect(toSet()));
        }
        if (updatedTags != null && !updatedTags.isEmpty()) {
            final Set<String> userTags = tagService.findByUser(
                getAuthenticatedUsername(),
                GraviteeContext.getCurrentOrganization(),
                TagReferenceType.ORGANIZATION
            );
            if (!userTags.containsAll(updatedTags)) {
                final String[] notAllowedTags = updatedTags.stream().filter(tag -> !userTags.contains(tag)).toArray(String[]::new);
                throw new TagNotAllowedException(notAllowedTags);
            }
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

    private Api convert(String apiId, UpdateApiEntity updateApiEntity, String apiDefinition) {
        Api api = new Api();
        api.setId(apiId);
        if (updateApiEntity.getVisibility() != null) {
            api.setVisibility(io.gravitee.repository.management.model.Visibility.valueOf(updateApiEntity.getVisibility().toString()));
        }

        api.setVersion(updateApiEntity.getVersion().trim());
        api.setName(updateApiEntity.getName().trim());
        api.setDescription(updateApiEntity.getDescription().trim());
        api.setPicture(updateApiEntity.getPicture());
        api.setBackground(updateApiEntity.getBackground());

        api.setDefinition(buildApiDefinition(apiId, apiDefinition, updateApiEntity));

        final Set<String> apiCategories = updateApiEntity.getCategories();
        if (apiCategories != null) {
            final List<CategoryEntity> categories = categoryService.findAll();
            final Set<String> newApiCategories = new HashSet<>(apiCategories.size());
            for (final String apiCategory : apiCategories) {
                final Optional<CategoryEntity> optionalCategory = categories
                    .stream()
                    .filter(c -> apiCategory.equals(c.getKey()) || apiCategory.equals(c.getId()))
                    .findAny();
                optionalCategory.ifPresent(category -> newApiCategories.add(category.getId()));
            }
            api.setCategories(newApiCategories);
        }

        if (updateApiEntity.getLabels() != null) {
            api.setLabels(new ArrayList<>(new HashSet<>(updateApiEntity.getLabels())));
        }

        api.setGroups(updateApiEntity.getGroups());
        api.setDisableMembershipNotifications(updateApiEntity.isDisableMembershipNotifications());

        if (updateApiEntity.getLifecycleState() != null) {
            api.setApiLifecycleState(ApiLifecycleState.valueOf(updateApiEntity.getLifecycleState().name()));
        }
        return api;
    }

    private PrimaryOwnerEntity findPrimaryOwner(JsonNode apiDefinition, String userId) {
        ApiPrimaryOwnerMode poMode = ApiPrimaryOwnerMode.valueOf(
            this.parameterService.find(Key.API_PRIMARY_OWNER_MODE, ParameterReferenceType.ENVIRONMENT)
        );
        PrimaryOwnerEntity primaryOwnerFromDefinition = findPrimaryOwnerFromApiDefinition(apiDefinition);
        switch (poMode) {
            case USER:
                if (primaryOwnerFromDefinition == null || ApiPrimaryOwnerMode.GROUP.name().equals(primaryOwnerFromDefinition.getType())) {
                    return new PrimaryOwnerEntity(userService.findById(userId));
                }
                if (ApiPrimaryOwnerMode.USER.name().equals(primaryOwnerFromDefinition.getType())) {
                    try {
                        return new PrimaryOwnerEntity(userService.findById(primaryOwnerFromDefinition.getId()));
                    } catch (UserNotFoundException unfe) {
                        return new PrimaryOwnerEntity(userService.findById(userId));
                    }
                }
                break;
            case GROUP:
                if (primaryOwnerFromDefinition == null) {
                    return getFirstPoGroupUserBelongsTo(userId);
                }
                if (ApiPrimaryOwnerMode.GROUP.name().equals(primaryOwnerFromDefinition.getType())) {
                    try {
                        return new PrimaryOwnerEntity(groupService.findById(primaryOwnerFromDefinition.getId()));
                    } catch (GroupNotFoundException unfe) {
                        return getFirstPoGroupUserBelongsTo(userId);
                    }
                }
                if (ApiPrimaryOwnerMode.USER.name().equals(primaryOwnerFromDefinition.getType())) {
                    try {
                        final String poUserId = primaryOwnerFromDefinition.getId();
                        userService.findById(poUserId);
                        final Set<GroupEntity> poGroupsOfPoUser = groupService
                            .findByUser(poUserId)
                            .stream()
                            .filter(group -> group.getApiPrimaryOwner() != null && !group.getApiPrimaryOwner().isEmpty())
                            .collect(toSet());
                        if (poGroupsOfPoUser.isEmpty()) {
                            return getFirstPoGroupUserBelongsTo(userId);
                        }
                        return new PrimaryOwnerEntity(poGroupsOfPoUser.iterator().next());
                    } catch (UserNotFoundException unfe) {
                        return getFirstPoGroupUserBelongsTo(userId);
                    }
                }
                break;
            case HYBRID:
            default:
                if (primaryOwnerFromDefinition == null) {
                    return new PrimaryOwnerEntity(userService.findById(userId));
                }
                if (ApiPrimaryOwnerMode.GROUP.name().equals(primaryOwnerFromDefinition.getType())) {
                    try {
                        return new PrimaryOwnerEntity(groupService.findById(primaryOwnerFromDefinition.getId()));
                    } catch (GroupNotFoundException unfe) {
                        try {
                            return getFirstPoGroupUserBelongsTo(userId);
                        } catch (NoPrimaryOwnerGroupForUserException ex) {
                            return new PrimaryOwnerEntity(userService.findById(userId));
                        }
                    }
                }
                if (ApiPrimaryOwnerMode.USER.name().equals(primaryOwnerFromDefinition.getType())) {
                    try {
                        return new PrimaryOwnerEntity(userService.findById(primaryOwnerFromDefinition.getId()));
                    } catch (UserNotFoundException unfe) {
                        return new PrimaryOwnerEntity(userService.findById(userId));
                    }
                }
                break;
        }

        return new PrimaryOwnerEntity(userService.findById(userId));
    }

    private PrimaryOwnerEntity findPrimaryOwnerFromApiDefinition(JsonNode apiDefinition) {
        PrimaryOwnerEntity primaryOwnerEntity = null;
        if (apiDefinition != null && apiDefinition.has("primaryOwner")) {
            try {
                primaryOwnerEntity = objectMapper.readValue(apiDefinition.get("primaryOwner").toString(), PrimaryOwnerEntity.class);
            } catch (JsonProcessingException e) {
                LOGGER.warn("Cannot parse primary owner from definition, continue with current user", e);
            }
        }
        return primaryOwnerEntity;
    }

    private String formatExpression(final Matcher matcher, final String group) {
        String matchedExpression = matcher.group(group);
        final boolean expressionBlank = matchedExpression == null || "".equals(matchedExpression);
        final boolean after = "after".equals(group);

        String expression;
        if (after) {
            if (matchedExpression.startsWith(" && (") && matchedExpression.endsWith(")")) {
                matchedExpression = matchedExpression.substring(5, matchedExpression.length() - 1);
            }
            expression = expressionBlank ? "" : " && (" + matchedExpression + ")";
            expression = expression.replaceAll("\\(" + LOGGING_DELIMITER_BASE, "\\(");
        } else {
            if (matchedExpression.startsWith("(") && matchedExpression.endsWith(") && ")) {
                matchedExpression = matchedExpression.substring(1, matchedExpression.length() - 5);
            }
            expression = expressionBlank ? "" : "(" + matchedExpression + ") && ";
            expression = expression.replaceAll(LOGGING_DELIMITER_BASE + "\\)", "\\)");
        }
        return expression;
    }

    private PrimaryOwnerEntity getFirstPoGroupUserBelongsTo(String userId) {
        final Set<GroupEntity> poGroupsOfCurrentUser = groupService
            .findByUser(userId)
            .stream()
            .filter(group -> !StringUtils.isEmpty(group.getApiPrimaryOwner()))
            .collect(toSet());
        if (poGroupsOfCurrentUser.isEmpty()) {
            throw new NoPrimaryOwnerGroupForUserException(userId);
        }
        return new PrimaryOwnerEntity(poGroupsOfCurrentUser.iterator().next());
    }

    private String getScheme(String defaultEntrypoint) {
        String scheme = "https";
        if (defaultEntrypoint != null) {
            try {
                scheme = new URL(defaultEntrypoint).getProtocol();
            } catch (MalformedURLException e) {
                // return default scheme
            }
        }
        return scheme;
    }

    private void validateHealtcheckSchedule(UpdateApiEntity api) {
        if (api.getServices() != null) {
            HealthCheckService healthCheckService = api.getServices().get(HealthCheckService.class);
            if (healthCheckService != null) {
                String schedule = healthCheckService.getSchedule();
                if (schedule != null) {
                    try {
                        new CronTrigger(schedule);
                    } catch (IllegalArgumentException e) {
                        throw new InvalidDataException(e);
                    }
                }
            }
        }
    }

    private void validateRegexfields(final UpdateApiEntity updateApiEntity) {
        // validate regex on paths
        if (updateApiEntity.getPaths() != null) {
            updateApiEntity
                .getPaths()
                .forEach(
                    (path, v) -> {
                        try {
                            Pattern.compile(path);
                        } catch (java.util.regex.PatternSyntaxException pse) {
                            LOGGER.error("An error occurs while trying to parse the path {}", path, pse);
                            throw new TechnicalManagementException("An error occurs while trying to parse the path " + path, pse);
                        }
                    }
                );
        }

        // validate regex on pathMappings
        if (updateApiEntity.getPathMappings() != null) {
            updateApiEntity
                .getPathMappings()
                .forEach(
                    pathMapping -> {
                        try {
                            Pattern.compile(pathMapping);
                        } catch (java.util.regex.PatternSyntaxException pse) {
                            LOGGER.error("An error occurs while trying to parse the path mapping {}", pathMapping, pse);
                            throw new TechnicalManagementException(
                                "An error occurs while trying to parse the path mapping" + pathMapping,
                                pse
                            );
                        }
                    }
                );
        }
    }
}
