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
package io.gravitee.rest.api.model.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.gravitee.rest.api.model.annotations.ParameterKey;
import io.gravitee.rest.api.model.parameters.Key;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Scheduler {
    @JsonProperty("tasks")
    @ParameterKey(Key.SCHEDULER_TASKS)
    private Integer tasksInSeconds;

    @JsonProperty("notifications")
    @ParameterKey(Key.SCHEDULER_NOTIFICATIONS)
    private Integer notificationsInSeconds;

    public Integer getTasksInSeconds() {
        return tasksInSeconds;
    }

    public void setTasksInSeconds(Integer tasksInSeconds) {
        this.tasksInSeconds = tasksInSeconds;
    }

    public Integer getNotificationsInSeconds() {
        return notificationsInSeconds;
    }

    public void setNotificationsInSeconds(Integer notificationsInSeconds) {
        this.notificationsInSeconds = notificationsInSeconds;
    }
}
