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
package io.gravitee.management.rest.resource.auth;

import io.gravitee.management.model.GroupEntity;
import io.gravitee.management.model.configuration.identity.GroupMappingEntity;
import io.gravitee.management.service.GroupService;
import org.apache.commons.io.IOUtils;
import org.assertj.core.util.Lists;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class Oauth2AuthenticationResourcegGroupsToAddUserTest {


    @InjectMocks
    OAuth2AuthenticationResource am = new OAuth2AuthenticationResource();

    @Mock
    GroupService groupService;

    @Test
    public void shouldDecodeGroupeNameIfJsonPathInValue() throws IOException {
        final ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        
       
        when(groupService.findById(argument.capture())).thenAnswer((Answer<GroupEntity>) aInvocation -> {
            GroupEntity gEntity =  new GroupEntity();

            gEntity.setName(aInvocation.getArguments()[0].toString());
            return gEntity;
        });


        String userInfo = IOUtils.toString(this.getClass().getResourceAsStream("/oauth2/json/user_info_response_body.json"), Charset.defaultCharset());

        GroupMappingEntity condition1 = new GroupMappingEntity();
        condition1.setCondition("true");
        condition1.setGroups(Arrays.asList("Example group", "soft user", "{#jsonPath(#profile, '$.job_id')}"));

        List<GroupMappingEntity> mappings = Collections.singletonList(condition1);

        Set<GroupEntity> groupsToAdd = am.getGroupsToAddUser("",mappings,userInfo);

        assertThat(groupsToAdd, notNullValue());

    }
    @Test
    public void shouldDecodeGroupeNameIfJsonPathInValueAndWithArray() throws IOException {
        final ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);


        when(groupService.findById(argument.capture())).thenAnswer((Answer<GroupEntity>) aInvocation -> {
            GroupEntity gEntity =  new GroupEntity();

            gEntity.setName(aInvocation.getArguments()[0].toString());
            gEntity.setId(gEntity.getName());
            return gEntity;
        });


        String userInfo = IOUtils.toString(this.getClass().getResourceAsStream("/oauth2/json/user_info_response_body_with_array.json"), Charset.defaultCharset());

        GroupMappingEntity condition1 = new GroupMappingEntity();
        condition1.setCondition("true");
        condition1.setGroups(Arrays.asList("Example group", "soft user", "{#jsonPath(#profile, '$.groups')}"));

        List<GroupMappingEntity> mappings = Collections.singletonList(condition1);

        Set<GroupEntity> groupsToAdd = am.getGroupsToAddUser("",mappings,userInfo);

        List<String> listName = groupsToAdd.stream()
                .map(GroupEntity::getName)
                .sorted()
                .collect(Collectors.toList());
        assertThat(listName, is(equalTo(Lists.newArrayList("Example group","admin","dev", "soft user","user"))));

    }

    @Test
    public void shouldDecodeGroupeNameIfJsonPathWithbadTemplateInGroup() throws IOException {
        final ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);


        when(groupService.findById(argument.capture())).thenAnswer((Answer<GroupEntity>) aInvocation -> {
            GroupEntity gEntity =  new GroupEntity();

            gEntity.setName(aInvocation.getArguments()[0].toString());
            gEntity.setId(gEntity.getName());

            return gEntity;
        });


        String userInfo = IOUtils.toString(this.getClass().getResourceAsStream("/oauth2/json/user_info_response_body_with_array.json"), Charset.defaultCharset());

        GroupMappingEntity condition1 = new GroupMappingEntity();
        condition1.setCondition("true");
        condition1.setGroups(Arrays.asList("Example group", "soft user", "{#jsonPath(#profile, '$.plop')}"));

        List<GroupMappingEntity> mappings = Collections.singletonList(condition1);

        Set<GroupEntity> groupsToAdd = am.getGroupsToAddUser("",mappings,userInfo);

        List<String> listName = groupsToAdd.stream()
                .map(GroupEntity::getName)
                .sorted()
                .collect(Collectors.toList());
        assertThat(listName, is(equalTo(Lists.newArrayList("Example group", "soft user"))));

    }
}
