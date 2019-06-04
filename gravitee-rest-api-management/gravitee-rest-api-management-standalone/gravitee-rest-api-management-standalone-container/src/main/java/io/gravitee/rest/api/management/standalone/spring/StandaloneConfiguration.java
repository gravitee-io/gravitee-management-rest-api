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
package io.gravitee.rest.api.management.standalone.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.gravitee.node.api.Node;
import io.gravitee.node.vertx.spring.VertxConfiguration;
import io.gravitee.rest.api.management.repository.spring.RepositoryConfiguration;
import io.gravitee.rest.api.management.rest.spring.RestManagementConfiguration;
import io.gravitee.rest.api.management.standalone.jetty.JettyConfiguration;
import io.gravitee.rest.api.management.standalone.jetty.JettyEmbeddedContainer;
import io.gravitee.rest.api.management.standalone.jetty.JettyServerFactory;
import io.gravitee.rest.api.management.standalone.node.ManagementNode;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Configuration
@Import({
        VertxConfiguration.class,
        RepositoryConfiguration.class,
        RestManagementConfiguration.class
})
public class StandaloneConfiguration {

    @Bean
    public NodeFactory node() {
        return new NodeFactory(ManagementNode.class);
    }

    @Bean
    public JettyConfiguration jettyConfiguration() {
        return new JettyConfiguration();
    }

    @Bean
    public JettyServerFactory server() {
        return new JettyServerFactory();
    }

    @Bean
    public JettyEmbeddedContainer container() {
        return new JettyEmbeddedContainer();
    }
}
