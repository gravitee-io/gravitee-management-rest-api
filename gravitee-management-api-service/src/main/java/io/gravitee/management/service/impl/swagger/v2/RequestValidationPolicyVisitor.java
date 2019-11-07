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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.definition.model.Policy;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import io.swagger.models.parameters.Parameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class RequestValidationPolicyVisitor extends AbstractPolicyVisitor {

    @Override
    public Optional<Policy> visit(Swagger swagger, Operation operation) {
        List<Parameter> parameters = operation.getParameters();

        List<Rule> rules = new ArrayList<>();

        if (parameters != null && !parameters.isEmpty()) {
            parameters.forEach(new Consumer<Parameter>() {
                @Override
                public void accept(Parameter parameter) {
                    String in = parameter.getIn();
                    switch (in) {
                        case "query":
                            rules.add(new Rule("{#request.params['" + parameter.getName() + "']}",
                                    new NotNullConstraint(parameter.getName() + " query parameter is required")));
                            break;
                        case "header":
                            rules.add(new Rule("{#request.headers['" + parameter.getName() + "'][0]}",
                                    new NotNullConstraint(parameter.getName() + " header is required")));
                            break;
                    }
                }
            });
        }

        if (! rules.isEmpty()) {
            try {
                Policy policy = new Policy();
                policy.setName("policy-request-validation");
                policy.setConfiguration(mapper.writeValueAsString(new Configuration("REQUEST", Integer.toString(HttpStatusCode.BAD_REQUEST_400), rules)));
                return Optional.of(policy);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }

        return Optional.empty();
    }

    private class Configuration {
        private final String scope;
        private final String status;
        private final List<Rule> rules;

        Configuration(String scope, String status, List<Rule> rules) {
            this.scope = scope;
            this.status = status;
            this.rules = rules;
        }

        public String getScope() {
            return scope;
        }

        public String getStatus() {
            return status;
        }

        public List<Rule> getRules() {
            return rules;
        }
    }

    private class Rule {
        private final String input;
        private final Constraint constraint;

        Rule(String input, Constraint constraint) {
            this.input = input;
            this.constraint = constraint;
        }

        public String getInput() {
            return input;
        }

        public Constraint getConstraint() {
            return constraint;
        }
    }

    private class Constraint {
        private final String type;
        private List<String> parameters;
        private String message;

        Constraint(String type) {
            this.type = type;
        }

        Constraint(String type, String message) {
            this.type = type;
            this.message = message;
        }

        public String getType() {
            return type;
        }

        public List<String> getParameters() {
            return parameters;
        }

        public void setParameters(List<String> parameters) {
            this.parameters = parameters;
        }

        public String getMessage() {
            return message;
        }
    }

    public class NotNullConstraint extends Constraint {

        NotNullConstraint(String message) {
            // We support only the NOT_NULL constraint for now
            super("NOT_NULL", message);
        }
    }
}
