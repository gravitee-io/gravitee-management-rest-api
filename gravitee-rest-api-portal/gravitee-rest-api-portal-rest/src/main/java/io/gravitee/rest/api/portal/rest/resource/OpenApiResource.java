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
package io.gravitee.rest.api.portal.rest.resource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import io.gravitee.common.http.MediaType;

/**
 * @author Florent CHAMFROY (florent.chamfroy at graviteesource.com)
 * @author GraviteeSource Team
 */
@Path("/openapi")
public class OpenApiResource extends AbstractResource {
    
    @Context
    private ResourceContext resourceContext;
    
    @GET
    @Produces({MediaType.TEXT_PLAIN})
    public Response getOpenApiDefinition() {
        final URL openApiURL = this.getClass().getClassLoader().getResource("openapi.yaml");
        try {
            byte[] openApiBytes = Files.readAllBytes(Paths.get(openApiURL.getFile()));
            ByteArrayOutputStream baos = new ByteArrayOutputStream(openApiBytes.length);
            baos.write(openApiBytes);
            return Response
                    .ok(baos)
                    .build();
        } catch (IOException e) {
            throw new InternalServerErrorException("Problem while reading openapi specification", e);
        }
        
    }
    
}
