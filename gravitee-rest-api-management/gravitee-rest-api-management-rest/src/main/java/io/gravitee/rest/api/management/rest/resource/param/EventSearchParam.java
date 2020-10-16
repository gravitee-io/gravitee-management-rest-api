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
package io.gravitee.rest.api.management.rest.resource.param;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;
/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public class EventSearchParam extends AbstractSearchParam {

    @QueryParam("type")
    @DefaultValue("all")
    private EventTypeListParam eventTypeListParam;

    @QueryParam("api_ids")
    private ApiIdsParam apiIdsParam;

    public EventTypeListParam getEventTypeListParam() {
        return eventTypeListParam;
    }

    public void setEventTypeListParam(EventTypeListParam eventTypeListParam) {
        this.eventTypeListParam = eventTypeListParam;
    }

    public ApiIdsParam getApiIdsParam() {
        return apiIdsParam;
    }

    public void setApiIdsParam(ApiIdsParam apiIdsParam) {
        this.apiIdsParam = apiIdsParam;
    }
}
