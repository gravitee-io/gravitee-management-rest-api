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

import io.gravitee.rest.api.spec.converter.wsdl.WsdlMapper;
import io.gravitee.rest.api.model.ImportWsdlDescriptorEntity;
import io.gravitee.rest.api.model.api.SwaggerApiEntity;
import io.gravitee.rest.api.service.WsdlService;
import io.gravitee.rest.api.service.exceptions.SwaggerDescriptorException;
import io.gravitee.rest.api.service.exceptions.WsdlDescriptorException;
import io.gravitee.rest.api.service.impl.swagger.converter.api.OAIToAPIConverter;
import io.gravitee.rest.api.service.impl.swagger.policy.PolicyOperationVisitorManager;
import io.gravitee.rest.api.service.impl.swagger.visitor.v3.OAIOperationVisitor;
import io.gravitee.rest.api.service.sanitizer.UrlSanitizerUtils;
import io.gravitee.rest.api.service.spring.ImportConfiguration;
import io.gravitee.rest.api.service.swagger.OAIDescriptor;
import io.gravitee.rest.api.service.swagger.SwaggerDescriptor;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class WsdlServiceImpl implements WsdlService {

    private final Logger logger = LoggerFactory.getLogger(WsdlServiceImpl.class);

    @Value("${swagger.scheme:https}")
    private String defaultScheme;

    @Autowired
    private PolicyOperationVisitorManager policyOperationVisitorManager;

    @Autowired
    private ImportConfiguration importConfiguration;

    @Override
    public SwaggerApiEntity createAPI(ImportWsdlDescriptorEntity wsdDescriptor) {
        SwaggerDescriptor descriptor = parse(wsdDescriptor.getPayload());
        if (descriptor != null) {
            if (descriptor.getVersion() == SwaggerDescriptor.Version.OAI_V3) {
                List<OAIOperationVisitor> visitors = policyOperationVisitorManager.getPolicyVisitors().stream()
                        .filter(operationVisitor -> wsdDescriptor.getWithPolicies() != null
                                && wsdDescriptor.getWithPolicies().contains(operationVisitor.getId()))
                        .map(operationVisitor -> policyOperationVisitorManager.getOAIOperationVisitor(operationVisitor.getId()))
                        .collect(Collectors.toList());

                return new OAIToAPIConverter(visitors)
                        .convert((OAIDescriptor) descriptor);
            }
        }

        throw new SwaggerDescriptorException();
    }

    @Override
    public SwaggerDescriptor parse(String content) {
        // try to read wsdl
        logger.debug("Trying to load a WSDL descriptor");

        if(isUrl(content)) {
            UrlSanitizerUtils.checkAllowed(content, importConfiguration.getImportWhitelist(), importConfiguration.isAllowImportFromPrivate());
        }

        OpenAPI descriptor = new WsdlMapper().toOpenAPI(content);

        if (descriptor != null) {
            return new OAIDescriptor(descriptor);
        }

        throw new WsdlDescriptorException();
    }

    private boolean isUrl(String content) {
        try {
            new URL(content);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }
}
