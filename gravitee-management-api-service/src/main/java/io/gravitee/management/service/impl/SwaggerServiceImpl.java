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
package io.gravitee.management.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.MediaType;
import io.gravitee.definition.model.Path;
import io.gravitee.definition.model.Rule;
import io.gravitee.management.model.ImportSwaggerDescriptorEntity;
import io.gravitee.management.model.PageEntity;
import io.gravitee.management.model.api.NewSwaggerApiEntity;
import io.gravitee.management.model.api.UpdateSwaggerApiEntity;
import io.gravitee.management.service.SwaggerService;
import io.gravitee.management.service.exceptions.SwaggerDescriptorException;
import io.gravitee.management.service.impl.swagger.v2.SwaggerCompositePolicyVisitor;
import io.gravitee.management.service.impl.swagger.v3.OAICompositePolicyVisitor;
import io.swagger.models.Scheme;
import io.swagger.models.Swagger;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.parser.SwaggerCompatConverter;
import io.swagger.parser.SwaggerParser;
import io.swagger.parser.util.RemoteUrl;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Azize ELAMRANI (azize.elamrani at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class SwaggerServiceImpl implements SwaggerService {

    private final Logger logger = LoggerFactory.getLogger(SwaggerServiceImpl.class);

    @Value("${swagger.scheme:https}")
    private String defaultScheme;

    @Autowired
    private OAICompositePolicyVisitor oaiPolicyVisitor;

    @Autowired
    private SwaggerCompositePolicyVisitor swaggerPolicyVisitor;

    static {
        System.setProperty(String.format("%s.trustAll", RemoteUrl.class.getName()), Boolean.TRUE.toString());
        System.setProperty(String.format("%s.trustAll", io.swagger.v3.parser.util.RemoteUrl.class.getName()), Boolean.TRUE.toString());
    }

    @Override
    public NewSwaggerApiEntity prepare(ImportSwaggerDescriptorEntity swaggerDescriptor) {
        NewSwaggerApiEntity apiEntity;

        // try to read swagger in version 2
        apiEntity = prepareV2(swaggerDescriptor);

        // try to read swagger in version 3 (openAPI)
        if (apiEntity == null) {
            apiEntity = prepareV3(swaggerDescriptor);
        }

        // try to read swagger in version 1
        if (apiEntity == null) {
            apiEntity = prepareV1(swaggerDescriptor);
        }

        if (apiEntity == null) {
            throw new SwaggerDescriptorException();
        }

        return apiEntity;
    }

    private NewSwaggerApiEntity prepareV1(ImportSwaggerDescriptorEntity swaggerDescriptor) {
        NewSwaggerApiEntity apiEntity;
        try {
            logger.info("Loading an old Swagger descriptor from {}", swaggerDescriptor.getPayload());
            if (swaggerDescriptor.getType() == ImportSwaggerDescriptorEntity.Type.INLINE) {
                File temp = null;
                try {
                    temp = createTmpSwagger1File(swaggerDescriptor.getPayload());
                    apiEntity = mapSwagger12ToNewApi(new SwaggerCompatConverter().read(temp.getAbsolutePath()), swaggerDescriptor.isWithPolicyMocks());
                } finally {
                    if (temp != null) {
                        temp.delete();
                    }
                }
            } else {
                apiEntity = mapSwagger12ToNewApi(new SwaggerCompatConverter().read(swaggerDescriptor.getPayload()), swaggerDescriptor.isWithPolicyMocks());
            }
        } catch (IOException ioe) {
            logger.error("Can not read old Swagger specification", ioe);
            throw new SwaggerDescriptorException();
        }
        return apiEntity;
    }

    private NewSwaggerApiEntity prepareV2(ImportSwaggerDescriptorEntity swaggerDescriptor) {
        NewSwaggerApiEntity apiEntity;
        logger.info("Trying to loading a Swagger descriptor in v2");
        if (swaggerDescriptor.getType() == ImportSwaggerDescriptorEntity.Type.INLINE) {
            apiEntity = mapSwagger12ToNewApi(new SwaggerParser().parse(swaggerDescriptor.getPayload()), swaggerDescriptor.isWithPolicyMocks());
        } else {
            apiEntity = mapSwagger12ToNewApi(new SwaggerParser().read(swaggerDescriptor.getPayload()), swaggerDescriptor.isWithPolicyMocks());
        }
        return apiEntity;
    }

    private NewSwaggerApiEntity prepareV3(ImportSwaggerDescriptorEntity swaggerDescriptor) {
        NewSwaggerApiEntity apiEntity;
        logger.info("Trying to loading an OpenAPI descriptor");
        if (swaggerDescriptor.getType() == ImportSwaggerDescriptorEntity.Type.INLINE) {
            apiEntity = mapOpenApiToNewApi(new OpenAPIV3Parser().readContents(swaggerDescriptor.getPayload()), swaggerDescriptor.isWithPolicyMocks());
        } else {
            apiEntity = mapOpenApiToNewApi(new OpenAPIV3Parser().readWithInfo(swaggerDescriptor.getPayload(), (List<AuthorizationValue>) null), swaggerDescriptor.isWithPolicyMocks());
        }
        return apiEntity;
    }

    @Override
    public void transform(final PageEntity page) {
        if (page.getContent() != null
                && page.getConfiguration() != null
                && page.getConfiguration().get("tryItURL") != null
                && !page.getConfiguration().get("tryItURL").isEmpty()) {

            Object swagger = transformV2(page.getContent(), page.getConfiguration());

            if (swagger == null) {
                swagger = transformV1(page.getContent(), page.getConfiguration());
            }

            if (swagger == null) {
                swagger = transformV3(page.getContent(), page.getConfiguration());
            }

            if (swagger == null) {
                throw new SwaggerDescriptorException();
            }

            if (page.getContentType().equalsIgnoreCase(MediaType.APPLICATION_JSON)) {
                try {
                    page.setContent(Json.pretty().writeValueAsString(swagger));
                } catch (JsonProcessingException e) {
                    logger.error("Unexpected error", e);
                }
            } else {
                try {
                    page.setContent(Yaml.pretty().writeValueAsString(swagger));
                } catch (JsonProcessingException e) {
                    logger.error("Unexpected error", e);
                }
            }
        }
    }

    private File createTmpSwagger1File(String content) {
        // Create temporary file for Swagger parser (only for descriptor version < 2.x)
        File temp = null;
        String fileName = "gio_swagger_" + System.currentTimeMillis();
        BufferedWriter bw = null;
        FileWriter out = null;
        Swagger swagger = null;
        try {
            temp = File.createTempFile(fileName, ".tmp");
            out = new FileWriter(temp);
            bw = new BufferedWriter(out);
            bw.write(content);
            bw.close();
        } catch (IOException ioe) {
            // Fallback to the new parser
        } finally {
            if (bw != null) {
                try {
                    bw.close();
                } catch (IOException e) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                }
            }
        }
        return temp;
    }

    private Swagger transformV1(String content, Map<String, String> config) {
        // Create temporary file for Swagger parser (only for descriptor version < 2.x)
        File temp = null;
        Swagger swagger = null;
        try {
            temp = createTmpSwagger1File(content);
            swagger = new SwaggerCompatConverter().read(temp.getAbsolutePath());
            if (swagger != null && config != null && config.get("tryItURL") != null) {
                URI newURI = URI.create(config.get("tryItURL"));
                swagger.setSchemes(Collections.singletonList(Scheme.forValue(newURI.getScheme())));
                swagger.setHost((newURI.getPort() != -1) ? newURI.getHost() + ':' + newURI.getPort() : newURI.getHost());
                swagger.setBasePath((newURI.getRawPath().isEmpty()) ? "/" : newURI.getRawPath());
            }
        } catch (IOException ioe) {
            // Fallback to the new parser
        } finally {
            if (temp != null) {
                temp.delete();
            }
        }
        return swagger;
    }

    private Swagger transformV2(String content, Map<String, String> config) {
        Swagger swagger = new SwaggerParser().parse(content);
        if (swagger != null && config != null && config.get("tryItURL") != null) {
            URI newURI = URI.create(config.get("tryItURL"));
            swagger.setSchemes(Collections.singletonList(Scheme.forValue(newURI.getScheme())));
            swagger.setHost((newURI.getPort() != -1) ? newURI.getHost() + ':' + newURI.getPort() : newURI.getHost());
            swagger.setBasePath((newURI.getRawPath().isEmpty()) ? "/" : newURI.getRawPath());
        }
        return swagger;
    }

    private OpenAPI transformV3(String content, Map<String, String> config) {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, null);
        if (result != null && config != null && config.get("tryItURL") != null) {
            URI newURI = URI.create(config.get("tryItURL"));
            result.getOpenAPI().getServers().forEach(server -> {
                try {
                    server.setUrl(new URI(newURI.getScheme(),
                            newURI.getUserInfo(),
                            newURI.getHost(),
                            newURI.getPort(),
                            newURI.getPath(),
                            newURI.getQuery(),
                            newURI.getFragment()).toString());
                } catch (URISyntaxException e) {
                    logger.error(e.getMessage(), e);
                }
            });
        }
        if (result != null) {
            return result.getOpenAPI();
        } else {
            return null;
        }
    }

    private NewSwaggerApiEntity mapSwagger12ToNewApi(final Swagger swagger, final boolean isWithPolicyMocks) {
        if (swagger == null || swagger.getInfo() == null) {
            return null;
        }
        final NewSwaggerApiEntity apiEntity = new NewSwaggerApiEntity();
        apiEntity.setName(swagger.getInfo().getTitle());

        if (swagger.getBasePath() != null && !swagger.getBasePath().isEmpty()) {
            apiEntity.setContextPath(swagger.getBasePath());
        } else {
            apiEntity.setContextPath(apiEntity.getName().replaceAll("\\s+", "").toLowerCase());
        }

        apiEntity.setDescription(swagger.getInfo().getDescription() == null ? "Description of " + apiEntity.getName() :
                swagger.getInfo().getDescription());
        apiEntity.setVersion(swagger.getInfo().getVersion());

        String scheme = (swagger.getSchemes() == null || swagger.getSchemes().isEmpty()) ? defaultScheme :
                swagger.getSchemes().iterator().next().toValue();

        apiEntity.setEndpoint(Arrays.asList(scheme + "://" + swagger.getHost() + swagger.getBasePath()));

        apiEntity.setPaths(swagger.getPaths().entrySet().stream()
                .map(entry -> {
                    final Path swaggerPath = new Path();
                    swaggerPath.setPath(entry.getKey().replaceAll("\\{(.[^/]*)\\}", ":$1"));
                    if (isWithPolicyMocks) {
                        entry.getValue().getOperationMap().forEach((key, operation) -> {
                            Optional<List<Rule>> rules = swaggerPolicyVisitor.visit(swagger, operation, HttpMethod.valueOf(key.name()));
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        });
                    }
                    return swaggerPath;
                })
                .collect(toCollection(ArrayList::new)));

        return apiEntity;
    }

    private Map<String, Object> getResponseFromSimpleRef(Swagger swagger, String simpleRef) {
        final Map<String, Property> properties = swagger.getDefinitions().get(simpleRef).getProperties();
        if (properties == null) {
            return emptyMap();
        }
        return getResponseProperties(swagger, properties);
    }

    private Map<String, Object> getResponseProperties(Swagger swagger, Map<String, Property> properties) {
        return properties.entrySet()
                .stream()
                .collect(toMap(Map.Entry::getKey, e -> {
                    final Property property = e.getValue();
                    if (property instanceof RefProperty) {
                        return this.getResponseFromSimpleRef(swagger, ((RefProperty) property).getSimpleRef());
                    }
                    return property.getType();
                }));
    }

    private NewSwaggerApiEntity mapOpenApiToNewApi(final SwaggerParseResult swagger, final boolean isWithPolicyMocks) {
        if (swagger == null || swagger.getOpenAPI() == null || swagger.getOpenAPI().getInfo() == null) {
            return null;
        }
        final NewSwaggerApiEntity apiEntity = new NewSwaggerApiEntity();
        apiEntity.setName(swagger.getOpenAPI().getInfo().getTitle());

        if (!swagger.getOpenAPI().getServers().isEmpty()) {
            apiEntity.setEndpoint(mapServersToEndpoint(swagger.getOpenAPI().getServers()));
        }

        String contextPath = null;
        if (!swagger.getOpenAPI().getServers().isEmpty()) {
            List<String> evaluatedServerUrl = mapServersToEndpoint(swagger.getOpenAPI().getServers());
            apiEntity.setEndpoint(evaluatedServerUrl);
            contextPath = evaluatedServerUrl.get(0);
            contextPath = URI.create(contextPath).getPath();
        }

        if (contextPath == null || contextPath.equals("/")) {
            contextPath = apiEntity.getName().replaceAll("\\s+", "").toLowerCase();
        }

        apiEntity.setContextPath(contextPath);

        apiEntity.setDescription(swagger.getOpenAPI().getInfo().getDescription() == null ? "Description of " + apiEntity.getName() :
                swagger.getOpenAPI().getInfo().getDescription());
        apiEntity.setVersion(swagger.getOpenAPI().getInfo().getVersion());

        apiEntity.setPaths(swagger.getOpenAPI().getPaths().entrySet().stream()
                .map(entry -> {
                    final Path swaggerPath = new Path();
                    swaggerPath.setPath(entry.getKey().replaceAll("\\{(.[^/]*)\\}", ":$1"));
                    if (isWithPolicyMocks) {
                        final PathItem pathItem = entry.getValue();
                        if (pathItem.getGet() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getGet(), HttpMethod.GET);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                        if (pathItem.getPut() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getPut(), HttpMethod.PUT);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                        if (pathItem.getPost() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getPost(), HttpMethod.POST);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                        if (pathItem.getDelete() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getDelete(), HttpMethod.DELETE);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                        if (pathItem.getPatch() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getPatch(), HttpMethod.PATCH);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                        if (pathItem.getHead() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getHead(), HttpMethod.HEAD);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                        if (pathItem.getOptions() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getOptions(), HttpMethod.OPTIONS);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                        if (pathItem.getTrace() != null) {
                            Optional<List<Rule>> rules = oaiPolicyVisitor.visit(swagger, pathItem.getTrace(), HttpMethod.TRACE);
                            rules.ifPresent(ruleList -> swaggerPath.getRules().addAll(ruleList));
                        }
                    }
                    return swaggerPath;
                })
                .collect(toCollection(ArrayList::new)));
        return apiEntity;
    }

    private List<String> mapServersToEndpoint(List<Server> servers) {
        List<String> endpoints = new ArrayList<>();
        for (Server server : servers) {
            ServerVariables serverVariables = server.getVariables();
            String serverUrl = server.getUrl();
            if (CollectionUtils.isEmpty(serverVariables)) {
                endpoints.add(serverUrl);
            } else {
                List<String> evaluatedUrls = Arrays.asList(serverUrl);
                for (Entry<String, ServerVariable> serverVar : serverVariables.entrySet()) {
                    evaluatedUrls = evaluateServerUrlsForOneVar(serverVar.getKey(), serverVar.getValue(),
                            evaluatedUrls);
                }
                endpoints.addAll(evaluatedUrls);
            }
        }
        return endpoints;
    }
    
    private List<String> evaluateServerUrlsForOneVar(String varName, ServerVariable serverVar,
            List<String> templateUrls) {
        List<String> evaluatedUrls = new ArrayList<>();
        for (String templateUrl : templateUrls) {
            Matcher matcher = Pattern.compile("\\{" + varName + "\\}").matcher(templateUrl);
            if (matcher.find()) {
                if (CollectionUtils.isEmpty(serverVar.getEnum()) && serverVar.getDefault() != null) {
                    evaluatedUrls.add(templateUrl.replace(matcher.group(0), serverVar.getDefault()));
                } else {
                    for (String enumValue : serverVar.getEnum()) {
                        evaluatedUrls.add(templateUrl.replace(matcher.group(0), enumValue));
                    }
                }
            }
        }
        return evaluatedUrls;
    }

    @Override
    public String replaceServerList(String payload, List<String> graviteeUrls) {
        OpenAPI openApi = new OpenAPIV3Parser().readContents(payload).getOpenAPI();
        if (openApi != null) {
            List<Server> graviteeServers = graviteeUrls.stream()
                    .map(url -> new Server().url(url))
                    .collect(Collectors.toList());
            openApi.setServers(graviteeServers);
            return Yaml.pretty(openApi);
        }
        return payload;
    }

    @Override
    public UpdateSwaggerApiEntity prepareForUpdate(ImportSwaggerDescriptorEntity swaggerDescriptor) {
        NewSwaggerApiEntity newSwaggerApiEntity = prepare(swaggerDescriptor);
        UpdateSwaggerApiEntity result = new UpdateSwaggerApiEntity();
        result.setContextPath(newSwaggerApiEntity.getContextPath());
        result.setDescription(newSwaggerApiEntity.getDescription());
        result.setEndpoint(newSwaggerApiEntity.getEndpoint());
        result.setGroups(newSwaggerApiEntity.getGroups());
        result.setName(newSwaggerApiEntity.getName());
        result.setPaths(newSwaggerApiEntity.getPaths());
        result.setVersion(newSwaggerApiEntity.getVersion());
        
        return result;
    }
}
