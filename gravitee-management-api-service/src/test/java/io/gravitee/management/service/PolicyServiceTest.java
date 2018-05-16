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
package io.gravitee.management.service;

import io.gravitee.management.model.PolicyEntity;
import io.gravitee.management.service.impl.PolicyServiceImpl;
import io.gravitee.plugin.core.api.PluginManifest;
import io.gravitee.plugin.core.api.PluginType;
import io.gravitee.plugin.policy.PolicyPlugin;
import io.gravitee.plugin.policy.PolicyPluginManager;
import io.gravitee.policy.api.ChainScope;
import io.gravitee.policy.api.annotations.Category;
import io.gravitee.policy.api.annotations.Policy;
import io.gravitee.policy.api.annotations.Scope;
import io.gravitee.repository.exceptions.TechnicalException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Azize Elamrani (azize dot elamrani at gmail dot com)
 */
@RunWith(MockitoJUnitRunner.class)
public class PolicyServiceTest {

    private static final String POLICY_ID = "myPolicy";

    @InjectMocks
    private PolicyService policyService = new PolicyServiceImpl();

    @Mock
    private PolicyPluginManager policyManager;

    @Mock
    private PolicyPlugin policyDefinition;

//    @Mock
//    private Plugin plugin;

    @Mock
    private PluginManifest manifest;

    @Test
    public void shouldFindAll() throws TechnicalException {
        when(policyDefinition.id()).thenReturn(POLICY_ID);
        when(policyManager.findAll()).thenReturn(Collections.singletonList(policyDefinition));
        when(policyDefinition.manifest()).thenReturn(manifest);
        when(policyDefinition.policy()).thenReturn(Object.class);
//        when(plugin.manifest()).thenReturn(manifest);

        final Set<PolicyEntity> policies = policyService.findAll();

        assertNotNull(policies);
        PolicyEntity policyEntity = policies.iterator().next();
        assertEquals(POLICY_ID, policyEntity.getId());
        assertFalse(policyEntity.isDeprecated());
        assertEquals("OTHERS", policyEntity.getCategory());
        assertEquals("API", policyEntity.getScopes()[0]);
    }

    @Test
    public void shouldFindById() throws TechnicalException {
        when(policyManager.get(POLICY_ID)).thenReturn(policyDefinition);
        when(policyDefinition.id()).thenReturn(POLICY_ID);
        when(policyDefinition.manifest()).thenReturn(manifest);
        when(policyDefinition.path()).thenReturn(mock(Path.class));
        when(policyDefinition.type()).thenReturn(PluginType.POLICY);
        when(policyDefinition.policy()).thenReturn(TestPolicy.class);

        final PolicyEntity policyEntity = policyService.findById(POLICY_ID);

        assertNotNull(policyEntity);
        assertEquals(POLICY_ID, policyEntity.getId());
        assertTrue(policyEntity.isDeprecated());
        assertEquals("SECURITY", policyEntity.getCategory());
        assertEquals("SECURITY", policyEntity.getScopes()[0]);
        assertEquals("API", policyEntity.getScopes()[1]);
    }

    @Policy(
            category = @Category(io.gravitee.policy.api.Category.SECURITY),
            scope = @Scope({ChainScope.SECURITY, ChainScope.API})
    )
    @Deprecated
    class TestPolicy {

    }
}
