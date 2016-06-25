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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.token.AccessTokenConverter;
import org.springframework.security.oauth2.provider.token.RemoteTokenServices;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at gravitee.io)
 * @author GraviteeSource Team
 */
@Configuration
@Profile("oauth2")
@EnableWebSecurity
@EnableResourceServer
public class OAuth2SecurityConfigurerAdapter extends ResourceServerConfigurerAdapter {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void configure(ResourceServerSecurityConfigurer resources) throws Exception {
        resources
            .tokenServices(remoteTokenServices())
            .resourceId(null)
            .eventPublisher(new DefaultAuthenticationEventPublisher(applicationEventPublisher));
    }

    @Override
    public void configure(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .antMatchers(HttpMethod.OPTIONS, "**").permitAll()
                .antMatchers(HttpMethod.GET, "/user/**").permitAll()
                // API requests
                .antMatchers(HttpMethod.GET, "/apis/**").permitAll()
                .antMatchers(HttpMethod.POST, "/apis/**").hasAnyAuthority("ADMIN", "API_PUBLISHER")
                .antMatchers(HttpMethod.PUT, "/apis/**").hasAnyAuthority("ADMIN", "API_PUBLISHER")
                .antMatchers(HttpMethod.DELETE, "/apis/**").hasAnyAuthority("ADMIN", "API_PUBLISHER")
                // Application requests
                .antMatchers(HttpMethod.POST, "/applications/**").hasAnyAuthority("ADMIN", "API_CONSUMER")
                .antMatchers(HttpMethod.PUT, "/applications/**").hasAnyAuthority("ADMIN", "API_CONSUMER")
                .antMatchers(HttpMethod.DELETE, "/applications/**").hasAnyAuthority("ADMIN", "API_CONSUMER")
                // Instance requests
                .antMatchers(HttpMethod.GET, "/instances/**").hasAuthority("ADMIN")
                .anyRequest().authenticated()
            .and()
                .httpBasic()
                    .disable()
                .csrf()
                    .disable();
    }

    @Bean
    public AccessTokenConverter accessTokenConverter() {
        return new OAuth2AccessTokenConverter();
    }

    @Bean
    public RemoteTokenServices remoteTokenServices() {
        RemoteTokenServices s = new RemoteTokenServices();
        s.setAccessTokenConverter(accessTokenConverter());
        s.setCheckTokenEndpointUrl(environment.getProperty("security.oauth.endpoint.check_token"));
        s.setClientId(environment.getProperty("security.oauth.client.id"));
        s.setClientSecret(environment.getProperty("security.oauth.client.secret"));
        return s;
    }
}
