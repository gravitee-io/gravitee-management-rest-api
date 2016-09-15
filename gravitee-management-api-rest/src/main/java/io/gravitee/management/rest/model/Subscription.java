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
package io.gravitee.management.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.gravitee.management.model.SubscriptionStatus;

import java.util.Date;
import java.util.List;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class Subscription {

    private String id;

    private Plan plan;

    private Application application;

    private SubscriptionStatus status;

    @JsonProperty("processed_at")
    private Date processedAt;

    @JsonProperty("processed_by")
    private String processedBy;

    @JsonProperty("subscribed_by")
    private String subscribedBy;

    private String reason;

    @JsonProperty("starting_at")
    private Date startingAt;

    @JsonProperty("ending_at")
    private Date endingAt;

    /**
     * Subscription creation date
     */
    @JsonProperty("created_at")
    private Date createdAt;

    /**
     * Subscription last update date
     */
    @JsonProperty("updated_at")
    private Date updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public Application getApplication() {
        return application;
    }

    public void setApplication(Application application) {
        this.application = application;
    }

    public Date getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Date processedAt) {
        this.processedAt = processedAt;
    }

    public String getProcessedBy() {
        return processedBy;
    }

    public void setProcessedBy(String processedBy) {
        this.processedBy = processedBy;
    }

    public String getSubscribedBy() {
        return subscribedBy;
    }

    public void setSubscribedBy(String subscribedBy) {
        this.subscribedBy = subscribedBy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Date getStartingAt() {
        return startingAt;
    }

    public void setStartingAt(Date startingAt) {
        this.startingAt = startingAt;
    }

    public Date getEndingAt() {
        return endingAt;
    }

    public void setEndingAt(Date endingAt) {
        this.endingAt = endingAt;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Subscription that = (Subscription) o;

        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public static class Plan {
        private final String id;
        private final String name;
        private List<Api> apis;

        public Plan(final String id, final String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Api> getApis() {
            return apis;
        }

        public void setApis(List<Api> apis) {
            this.apis = apis;
        }
    }

    public static class Application {
        private final String id;
        private final String name;
        private Owner owner;

        public Application(final String id, final String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Owner getOwner() {
            return owner;
        }

        public void setOwner(Owner owner) {
            this.owner = owner;
        }
    }

    public static class Api {
        private final String id;
        private final String name;
        private Owner owner;

        public Api(final String id, final String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Owner getOwner() {
            return owner;
        }

        public void setOwner(Owner owner) {
            this.owner = owner;
        }
    }

    public static class Owner {
        private final String username;
        private String firstname;
        private String lastname;

        public Owner(final String username) {
            this.username = username;
        }

        public String getUsername() {
            return username;
        }

        public String getFirstname() {
            return firstname;
        }

        public String getLastname() {
            return lastname;
        }

        public void setFirstname(String firstname) {
            this.firstname = firstname;
        }

        public void setLastname(String lastname) {
            this.lastname = lastname;
        }
    }
}
