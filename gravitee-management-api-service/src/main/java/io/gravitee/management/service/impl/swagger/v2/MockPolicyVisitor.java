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
package io.gravitee.management.service.impl.swagger.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.MediaType;
import io.gravitee.definition.model.Policy;
import io.swagger.models.*;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.util.*;

import static java.util.Collections.*;
import static java.util.stream.Collectors.toMap;

public class MockPolicyVisitor extends AbstractPolicyVisitor {

    @Override
    public Optional<Policy> visit(Swagger swagger, Operation operation) {
        Configuration configuration = new Configuration();

        final Map.Entry<String, Response> responseEntry = operation.getResponses().entrySet().iterator().next();

        // Set response status
        configuration.setStatus(responseEntry.getKey());

        // Set default headers
        configuration.setHeaders(Collections.singletonList(new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)));

        final Model responseSchema = responseEntry.getValue().getResponseSchema();
        if (responseSchema != null) {
            if (responseSchema instanceof ArrayModel) {
                final ArrayModel arrayModel = (ArrayModel) responseSchema;
                configuration.setArray(true);
                if (arrayModel.getItems() instanceof RefProperty) {
                    final String simpleRef = ((RefProperty) arrayModel.getItems()).getSimpleRef();
                    configuration.setResponse(getResponseFromSimpleRef(swagger, simpleRef));
                } else if (arrayModel.getItems() instanceof ObjectProperty) {
                    configuration.setResponse(getResponseProperties(swagger, ((ObjectProperty) arrayModel.getItems()).getProperties()));
                }
            } else if (responseSchema instanceof RefModel) {
                final String simpleRef = ((RefModel) responseSchema).getSimpleRef();
                configuration.setResponse(getResponseFromSimpleRef(swagger, simpleRef));
            } else if (responseSchema instanceof ModelImpl) {
                final ModelImpl model = (ModelImpl) responseSchema;
                configuration.setArray("array".equals(model.getType()));
                if ("object".equals(model.getType())) {
                    if (model.getAdditionalProperties() != null) {
                        configuration.setResponse(Collections.singletonMap("additionalProperty", model.getAdditionalProperties().getType()));
                    }
                }
            }
        }

        try {
            Policy policy = new Policy();
            policy.setName("mock");
            configuration.setContent(mapper.writeValueAsString(configuration.isArray()?
                    singletonList(configuration.getResponse()): configuration.getResponse()));
            policy.setConfiguration(mapper.writeValueAsString(configuration));
            return Optional.of(policy);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        return Optional.empty();
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

    private class Configuration {
        private String status;
        private List<Header> headers;

        @JsonIgnore
        private Object response;

        @JsonIgnore
        private boolean array;

        private String content;

        Configuration() {
        }

        public String getStatus() {
            return status;
        }

        public List<Header> getHeaders() {
            return headers;
        }

        public Object getResponse() {
            return response;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setHeaders(List<Header> headers) {
            this.headers = headers;
        }

        public void setResponse(Object response) {
            this.response = response;
        }

        public boolean isArray() {
            return array;
        }

        public void setArray(boolean array) {
            this.array = array;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    private class Header {
        private final String name;
        private final String value;

        public Header(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }
    }
}
