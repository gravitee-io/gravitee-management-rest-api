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
package io.gravitee.rest.api.management.rest.resource.organization;

import io.gravitee.rest.api.management.rest.resource.AbstractResource;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Context;

/**
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
@Path("/organizations")
@Api
public class OrganizationsResource extends AbstractResource {

    @Context
    private ResourceContext resourceContext;

    @Path("/{orgId}")
    public OrganizationResource getOrganizationResource(
            @PathParam("orgId") @ApiParam(name = "orgId", required = true, defaultValue = "DEFAULT", value = "The ID of the Organization") String orgId
    ) {
        return resourceContext.getResource(OrganizationResource.class);
    }
}
