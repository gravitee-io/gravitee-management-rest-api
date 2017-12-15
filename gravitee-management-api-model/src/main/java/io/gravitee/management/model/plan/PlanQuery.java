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
package io.gravitee.management.model.plan;

import io.gravitee.management.model.PlanType;

/**
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com) 
 * @author GraviteeSource Team
 */
public class PlanQuery {

    private String api;
    private String name;
    private PlanType type;

    private PlanQuery(Builder builder) {
        api = builder.api;
        name = builder.name;
        type = builder.type;
    }

    public String getApi() {
        return api;
    }

    public String getName() {
        return name;
    }

    public PlanType getType() {
        return type;
    }

    public static class Builder {
        private String api;
        private String name;
        private PlanType type;

        public PlanQuery build() {
            return new PlanQuery(this);
        }

        public Builder api(String api) {
            this.api = api;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(PlanType type) {
            this.type = type;
            return this;
        }
    }
}
