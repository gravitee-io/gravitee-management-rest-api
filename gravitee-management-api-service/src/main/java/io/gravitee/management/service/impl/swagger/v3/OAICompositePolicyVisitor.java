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
package io.gravitee.management.service.impl.swagger.v3;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.definition.model.Policy;
import io.gravitee.definition.model.Rule;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.singleton;

public class OAICompositePolicyVisitor {

    @Autowired
    private MockPolicyVisitor mockPolicyVisitor;

    @Autowired
    private RequestValidationPolicyVisitor requestValidationPolicyVisitor;

    public Optional<List<Rule>> visit(SwaggerParseResult swagger, Operation operation, HttpMethod method) {

        List<Rule> rules = new ArrayList<>();

        requestValidationPolicyVisitor.visit(swagger, operation).ifPresent(policy -> rules.add(compute(operation, policy, method)));
        mockPolicyVisitor.visit(swagger, operation).ifPresent(policy -> rules.add(compute(operation, policy, method)));

        if (! rules.isEmpty()) {
            return Optional.of(rules);
        }

        return Optional.empty();
    }

    private Rule compute(Operation operation, Policy policy, HttpMethod method) {
        Rule rule = new Rule();
        rule.setEnabled(true);
        rule.setDescription(operation.getSummary() == null ?
                (operation.getOperationId() == null ? operation.getDescription() : operation.getOperationId()) :
                operation.getSummary());
        rule.setMethods(singleton(method));
        rule.setPolicy(policy);
        return rule;
    }

    public void setMockPolicyVisitor(MockPolicyVisitor mockPolicyVisitor) {
        this.mockPolicyVisitor = mockPolicyVisitor;
    }

    public void setRequestValidationPolicyVisitor(RequestValidationPolicyVisitor requestValidationPolicyVisitor) {
        this.requestValidationPolicyVisitor = requestValidationPolicyVisitor;
    }
}
