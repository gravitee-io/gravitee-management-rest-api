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
package io.gravitee.rest.api.service.impl.upgrade;

import io.gravitee.common.util.EnvironmentUtils;
import io.gravitee.repository.management.model.RoleScope;
import io.gravitee.rest.api.model.EnvironmentEntity;
import io.gravitee.rest.api.model.GroupEntity;
import io.gravitee.rest.api.model.configuration.identity.*;
import io.gravitee.rest.api.service.EnvironmentService;
import io.gravitee.rest.api.service.GroupService;
import io.gravitee.rest.api.service.OrganizationService;
import io.gravitee.rest.api.service.Upgrader;
import io.gravitee.rest.api.service.configuration.identity.IdentityProviderActivationService;
import io.gravitee.rest.api.service.configuration.identity.IdentityProviderActivationService.ActivationTarget;
import io.gravitee.rest.api.service.configuration.identity.IdentityProviderService;
import io.gravitee.rest.api.service.exceptions.EnvironmentNotFoundException;
import io.gravitee.rest.api.service.exceptions.OrganizationNotFoundException;
import io.gravitee.rest.api.service.impl.configuration.identity.IdentityProviderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class IdentityProviderUpgrader implements Upgrader, Ordered {
    private static final String description = "Configuration provided by the system. Every modifications will be overridden at the next startup.";
    private final Logger logger = LoggerFactory.getLogger(IdentityProviderUpgrader.class);

    private List<String> idpTypeNames = Arrays.stream(IdentityProviderType.values()).map(Enum::name).collect(Collectors.toList());
    private static final String DESCRIPTION = "Configuration provided by the system. Every modifications will be overridden at the next startup.";
    private static final String SECURITY_PROVIDERS_KEY = "security.providers";

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private GroupService groupService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private IdentityProviderService identityProviderService;

    @Autowired
    private IdentityProviderActivationService identityProviderActivationService;

    @Override
    public boolean upgrade() {
        boolean found = true;
        int idx = 0;
        int internalIdpOrder = 1;

        while (found) {
            String type = environment.getProperty(SECURITY_PROVIDERS_KEY + "[" + idx + "].type");
            found = (type != null);
            if (found) {
                if (idpTypeNames.contains(type.toUpperCase())) {
                    logger.info("Upsert identity provider config [{}]", type);
                    IdentityProviderType idpType = IdentityProviderType.valueOf(type.toUpperCase());

                    String id = environment.getProperty(SECURITY_PROVIDERS_KEY + "[" + idx + "].id");
                    if (id == null) {
                        id = type;
                    }
                    try {
                        identityProviderService.findById(id);
                    } catch (IdentityProviderNotFoundException e) {
                        id = createIdp(id, idpType, idx, internalIdpOrder);
                    }
                    // always update
                    updateIdp(id, idpType, idx, internalIdpOrder);
                    if (!isSocialConnector(idpType)) {
                        internalIdpOrder++;
                    }
                    
                    // update idp activations
                    updateIdpActivations(id, idx);
                } else {
                    logger.info("Unknown identity provider [{}]", type);
                }
            }
            idx++;
        }
        return true;
    }

    private String createIdp(String id, IdentityProviderType type, int providerIndex, int internalIdpOrder) {
        NewIdentityProviderEntity idp = new NewIdentityProviderEntity();
        idp.setName(id);
        idp.setType(type);
        idp.setDescription(DESCRIPTION);
        idp.setConfiguration(getConfiguration(providerIndex, type));
        idp.setEnabled(true);
        idp.setEmailRequired(Boolean.valueOf((String) idp.getConfiguration().getOrDefault("emailRequired", "false")));
        idp.setSyncMappings(Boolean.valueOf((String) idp.getConfiguration().getOrDefault("syncMappings", "false")));
        if (!isSocialConnector(type)) {
            idp.setOrder(internalIdpOrder);
        }
        Map<String, String> userProfileMapping = getUserProfileMapping(providerIndex);
        if (!userProfileMapping.isEmpty()) {
            idp.setUserProfileMapping(userProfileMapping);
        }

        return identityProviderService.create(idp).getId();
    }

    private void updateIdpActivations(String id, int providerIndex) {
        //remove all previous activations
        identityProviderActivationService.deactivateIdpOnAllTargets(id);

        ActivationTarget[] targets = getActivationsTarget(providerIndex);
        if (targets.length > 0) {
            identityProviderActivationService.activateIdpOnTargets(id, targets);
        }
    }

    private ActivationTarget[] getActivationsTarget(int providerIndex) {
        List<String> targetStrings = getListOfString("security.providers[" + providerIndex + "].activations");
        List<ActivationTarget> activationTargets = new ArrayList<>();
        targetStrings.forEach(target -> {
            final String[] orgEnv = target.split(":");
            if (orgEnv.length == 1) {
                try {
                    this.organizationService.findById(orgEnv[0]);
                    activationTargets.add(new ActivationTarget(orgEnv[0], IdentityProviderActivationReferenceType.ORGANIZATION));
                } catch (OrganizationNotFoundException onfe) {
                    logger.warn("Organization {} does not exist", orgEnv[0]);
                }
            } else if (orgEnv.length == 2) {
                try {
                    this.organizationService.findById(orgEnv[0]);
                    EnvironmentEntity env = this.environmentService.findById(orgEnv[1]);
                    if (env.getOrganizationId().equals(orgEnv[0])) {
                        activationTargets.add(new ActivationTarget(orgEnv[1], IdentityProviderActivationReferenceType.ENVIRONMENT));
                    } else {
                        logger.warn("Environment {} does not exist in organization {}", orgEnv[1], orgEnv[0]);
                    }
                } catch (OrganizationNotFoundException onfe) {
                    logger.warn("Organization {} does not exist", orgEnv[0]);
                } catch (EnvironmentNotFoundException Enfe) {
                    logger.warn("Environment {} does not exist", orgEnv[1]);
                }
            }
        });
        return activationTargets.toArray(new ActivationTarget[activationTargets.size()]);
    }

    private void updateIdp(String id, IdentityProviderType type, int providerIndex, int internalIdpOrder) {
        UpdateIdentityProviderEntity idp = new UpdateIdentityProviderEntity();
        idp.setName(id);
        idp.setDescription(DESCRIPTION);
        idp.setConfiguration(getConfiguration(providerIndex, type));
        idp.setEmailRequired(Boolean.valueOf((String) idp.getConfiguration().getOrDefault("emailRequired", "false")));
        idp.setEnabled(true);
        if (!isSocialConnector(type)) {
            idp.setOrder(internalIdpOrder);
        }
        idp.setSyncMappings(Boolean.valueOf((String) idp.getConfiguration().getOrDefault("syncMappings", "false")));
        Map<String, String> userProfileMapping = getUserProfileMapping(providerIndex);
        if (!userProfileMapping.isEmpty()) {
            idp.setUserProfileMapping(userProfileMapping);
        }

        List<GroupMappingEntity> groupMappings = getGroupMappings(providerIndex);
        if (!groupMappings.isEmpty()) {
            idp.setGroupMappings(groupMappings);
        }

        List<RoleMappingEntity> roleMappings = getRoleMappings(providerIndex);
        if (!roleMappings.isEmpty()) {
            idp.setRoleMappings(roleMappings);
        }

        identityProviderService.update(id, idp);
    }

    private Map<String, Object> getConfiguration(int providerIndex, IdentityProviderType type) {
        HashMap<String, Object> config = new HashMap<>();

        String prefix = SECURITY_PROVIDERS_KEY + "[" + providerIndex + "].";
        Boolean booleanValue;
        switch (type) {
            case GITHUB:
            case GOOGLE:
            case GRAVITEEIO_AM:
            case OIDC:
                putIfNotNull(config, prefix, "clientId");
                putIfNotNull(config, prefix, "clientSecret");
                putIfNotNull(config, prefix, "color");
                putIfNotNull(config, prefix, "tokenEndpoint");
                putIfNotNull(config, prefix, "authorizeEndpoint");
                putIfNotNull(config, prefix, "tokenIntrospectionEndpoint");
                putIfNotNull(config, prefix, "userInfoEndpoint");
                putIfNotNull(config, prefix, "userLogoutEndpoint");
                putIfNotNull(config, prefix, "serverURL");
                putIfNotNull(config, prefix, "domain");

                List<String> scopes = getListOfString(prefix + "scopes");
                if (!scopes.isEmpty()) {
                    config.put("scopes", scopes);
                }
                break;

            case GRAVITEE:
                booleanValue = environment.getProperty(prefix + "allow-email-in-search-results", Boolean.class);
                if (booleanValue != null) {
                    config.put("allowEmailInSearchResults", booleanValue);
                }
                break;
                
            case LDAP:
                Map<String, String> ldapContext = getLdapContext(prefix + "context.");
                if (!ldapContext.isEmpty()) {
                    config.put("context", ldapContext);
                }
                Map<String, Object> ldapAuthentication = getLdapAuthentication(prefix + "authentication.");
                if (!ldapAuthentication.isEmpty()) {
                    config.put("authentication", ldapAuthentication);
                }
                Map<String, Object> ldapLookup = getLdapLookup(prefix + "lookup.");
                if (!ldapLookup.isEmpty()) {
                    config.put("lookup", ldapLookup);
                }
                break;
                
            case MEMORY:
                booleanValue = environment.getProperty(prefix + "allow-email-in-search-results", Boolean.class);
                if (booleanValue != null) {
                    config.put("allowEmailInSearchResults", booleanValue);
                }
                String value = environment.getProperty(prefix + "password-encoding-algo");
                if (value != null) {
                    config.put("passwordEncodingAlgo", value);
                }
                List<Map<String, Object>> memoryUsers = getListOfInMemoryUsers(prefix + "users");
                if (!memoryUsers.isEmpty()) {
                    config.put("users", memoryUsers);
                }
                break;
        }
        return config;
    }

    private List<String> getListOfString(String listName) {
        boolean found = true;
        int idx = 0;
        ArrayList<String> scopes = new ArrayList<>();

        while (found) {
            String scope = environment.getProperty(listName + "[" + idx + "]");
            found = (scope != null);
            if (found) {
                scopes.add(scope);
            }
            idx++;
        }
        return scopes;
    }

    private List<Map<String, Object>> getListOfInMemoryUsers(String inMemoryUsersKey) {
        boolean found = true;
        int idx = 0;
        Map<String, Object> userProps;
        List<Map<String, Object>> users = new ArrayList<>();
        
        while (found) {
            String user = environment.getProperty(inMemoryUsersKey + "[" + idx + "].user");
            found = (user != null);
            if (found) {
                userProps = new HashMap<>();
                putIfNotNull(userProps, inMemoryUsersKey + "[" + idx + "].", "username");
                putIfNotNull(userProps, inMemoryUsersKey + "[" + idx + "].", "password");
                List<String> roles = environment.getProperty(inMemoryUsersKey + "[" + idx + "].roles", List.class);
                if (roles != null && !roles.isEmpty()) {
                    userProps.put("roles", roles);
                }
                if(!userProps.isEmpty()) {
                    users.add(userProps);
                }
            }
            idx++;
        }
        return users;
    }

    private Map<String, String> getLdapContext(String ldapContextKey) {
        Map<String, String> ldapContext = new HashMap<>();
        putIfNotNull(ldapContext, ldapContextKey, "username");
        putIfNotNull(ldapContext, ldapContextKey, "password");
        putIfNotNull(ldapContext, ldapContextKey, "url");
        putIfNotNull(ldapContext, ldapContextKey, "base");
        
        return ldapContext;
    }

    private Map<String, Object> getLdapAuthentication(String ldapAuthenticationKey) {
        Map<String, Object> ldapAuthentication = new HashMap<>();
        
        Map<String, String> authenticationUser = new HashMap<>();
        putIfNotNull(authenticationUser, ldapAuthenticationKey + "user.", "base");
        putIfNotNull(authenticationUser, ldapAuthenticationKey + "user.", "filter");
        putIfNotNull(authenticationUser, ldapAuthenticationKey + "user.", "photo-attribute");
        if (!authenticationUser.isEmpty()) {
            ldapAuthentication.put("user", authenticationUser);
        }
        
        Map<String, Object> authenticationGroup = new HashMap<>();
        putIfNotNull(authenticationGroup, ldapAuthenticationKey + "group.", "base");
        putIfNotNull(authenticationGroup, ldapAuthenticationKey + "group.", "filter");

        Map<String, Object> authenticationGroupRole = new HashMap<>();
        putIfNotNull(authenticationGroupRole, ldapAuthenticationKey + "group.role.", "attribute");
        
        Map<String, Object> authenticationGroupRoleMapper = new HashMap<>();
        String prefixMapperKey = ldapAuthenticationKey + "group.role.mapper.";
        Map<String, Object> config = EnvironmentUtils.getPropertiesStartingWith(environment, prefixMapperKey);
        config.forEach((key, value) -> authenticationGroupRoleMapper.put(key.replace(prefixMapperKey, ""), value));
        if(!authenticationGroupRoleMapper.isEmpty()) {
            authenticationGroupRole.put("mapper", authenticationGroupRoleMapper);
        }
        if (!authenticationGroupRole.isEmpty()) {
            authenticationGroup.put("role", authenticationGroupRole);
        }
        
        if (!authenticationGroup.isEmpty()) {
            ldapAuthentication.put("group", authenticationGroup);
        }
        
        return ldapAuthentication;
    }

    private Map<String, Object> getLdapLookup(String ldapLookupKey) {
        Map<String, Object> ldapLookup = new HashMap<>();
        Boolean value = environment.getProperty(ldapLookupKey + "allow-email-in-search-results", Boolean.class);
        if (value != null) {
            ldapLookup.put("allowEmailInSearchResults", value);
        }

        Map<String, String> lookupUser = new HashMap<>();
        putIfNotNull(lookupUser, ldapLookupKey + "user.", "base");
        putIfNotNull(lookupUser, ldapLookupKey + "user.", "filter");
        if (!lookupUser.isEmpty()) {
            ldapLookup.put("user", lookupUser);
        }
        
        return ldapLookup;
    }
    
    private Map<String, String> getUserProfileMapping(int providerIndex) {
        HashMap<String, String> mapping = new HashMap<>();

        String prefix = SECURITY_PROVIDERS_KEY + "[" + providerIndex + "].userMapping.";
        putIfNotNull(mapping, prefix, "id");
        putIfNotNull(mapping, prefix, "email");
        putIfNotNull(mapping, prefix, "lastname");
        putIfNotNull(mapping, prefix, "firstname");
        putIfNotNull(mapping, prefix, "picture");

        return mapping;
    }

    private List<GroupMappingEntity> getGroupMappings(int providerIndex) {
        boolean found = true;
        int idx = 0;
        List<GroupMappingEntity> mapping = new ArrayList<>();

        while (found) {
            String condition = environment.getProperty(SECURITY_PROVIDERS_KEY + "[" + providerIndex + "].groupMapping[" + idx + "].condition");
            found = (condition != null);
            if (found) {
                GroupMappingEntity groupMappingEntity = new GroupMappingEntity();
                groupMappingEntity.setCondition(condition);
                List<String> groupNames = getListOfString(SECURITY_PROVIDERS_KEY + "[" + providerIndex + "].groupMapping[" + idx + "].groups");
                if (!groupNames.isEmpty()) {
                    List<String> groups = new ArrayList<>();
                    groupNames.forEach(groupName -> {
                        List<GroupEntity> groupsFound = groupService.findByName(groupName);

                        if (groupsFound != null && groupsFound.size() == 1) {
                            groups.add(groupsFound.get(0).getId());
                        }
                    });

                    groupMappingEntity.setGroups(groups);
                }
                mapping.add(groupMappingEntity);
            }
            idx++;
        }


        return mapping;
    }

    private List<RoleMappingEntity> getRoleMappings(int providerIndex) {
        boolean found = true;
        int idx = 0;
        List<RoleMappingEntity> mapping = new ArrayList<>();

        while (found) {
            String condition = environment.getProperty(SECURITY_PROVIDERS_KEY + "[" + providerIndex + "].roleMapping[" + idx + "].condition");
            found = (condition != null);
            if (found) {
                RoleMappingEntity roleMappingEntity = new RoleMappingEntity();
                roleMappingEntity.setCondition(condition);
                List<String> roles = getListOfString(SECURITY_PROVIDERS_KEY + "[" + providerIndex + "].roleMapping[" + idx + "].roles");
                if (!roles.isEmpty()) {
                    List<String> organizationsRoles = new ArrayList<>();
                    List<String> environmentsRoles = new ArrayList<>();
                    roles.forEach(role -> {
                        if (role.startsWith(RoleScope.ENVIRONMENT.name())) {
                            environmentsRoles.add(role.replace(RoleScope.ENVIRONMENT.name() + ":", ""));
                        }
                        if (role.startsWith(RoleScope.ORGANIZATION.name())) {
                            organizationsRoles.add(role.replace(RoleScope.ORGANIZATION.name() + ":", ""));
                        }
                    });
                    roleMappingEntity.setOrganizations(organizationsRoles);
                    roleMappingEntity.setEnvironments(environmentsRoles);
                }
                mapping.add(roleMappingEntity);
            }
            idx++;
        }


        return mapping;
    }

    private void putIfNotNull(Map config, String prefix, String key) {
        String value = environment.getProperty(prefix + key);
        if (value != null) {
            config.put(key, value);
        }
    }

    private boolean isSocialConnector(IdentityProviderType type) {
        return type == IdentityProviderType.GITHUB
                || type == IdentityProviderType.GOOGLE
                || type == IdentityProviderType.GRAVITEEIO_AM
                || type == IdentityProviderType.OIDC;
    }
    @Override
    public int getOrder() {
        return 350;
    }
}
