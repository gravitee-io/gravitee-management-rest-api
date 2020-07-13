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
package io.gravitee.rest.api.spec.converter;

import io.gravitee.rest.api.spec.converter.wsdl.WsdlMapper;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;

import java.io.FileInputStream;
import java.net.URI;
import java.nio.file.Paths;

/**
 * @author GraviteeSource Team
 */
public class SpecConverter {

    public static void main(String[] args) throws Exception {

/*        if (args.length != 1) {
            System.err.println("expected one argument : <wsdl uri>");
            System.exit(1);
        }
        String wsdl = args[0];*/
        String wsdl = "/home/eric/workspace/gravitee-management-rest-api/gravitee-rest-api-spec-converter/src/test/resources/example.wsdl";
        OpenAPI openAPI = null;
        try {
            URI uri = URI.create(wsdl);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                openAPI = new WsdlMapper().toOpenAPI(new FileInputStream(Paths.get(uri).toFile()));
            } else {
                openAPI = new WsdlMapper().toOpenAPI(uri.toURL().openStream());
            }
        } catch (IllegalArgumentException e) {
            openAPI = new WsdlMapper().toOpenAPI(new FileInputStream(Paths.get(wsdl).toFile()));
        }

        System.out.println(Yaml.pretty().writeValueAsString(openAPI));
    }
}
