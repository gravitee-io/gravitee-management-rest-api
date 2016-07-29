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
package io.gravitee.management.security.config.oauth2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.DefaultAccessTokenConverter;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at gravitee.io)
 * @author GraviteeSource Team
 */
public class OAuth2AccessTokenConverter extends DefaultAccessTokenConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2AccessTokenConverter.class);

    @Autowired
    private Environment environment;

    @Override
    public OAuth2Authentication extractAuthentication(Map<String, ?> map) {
        // get current user name and his authorities
        String username = (String) map.get("user_name");
        Collection<String> authorities = (Collection) map.get("authorities");

        // user is admin ?
        String administrators = environment.getProperty("security.oauth.administrators");
        if (administrators != null && Arrays.asList(administrators.trim().split(",")).contains(username)) {
            LOGGER.debug("User {} is admin", username);
            authorities.add("ADMIN");
        }

        return super.extractAuthentication(map);
    }
}
