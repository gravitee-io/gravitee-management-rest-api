package io.gravitee.rest.api.model;

import static io.gravitee.rest.api.model.PageType.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class PageTypeTest {

    @Test
    public void fromPageExtensionAndContent_should_return_swagger_called_with_valid_swagger_json_content() {
        String pageExtension = "json";
        String pageContent = "{\"swagger\":\"2.0\",\"info\":";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertSame(SWAGGER, type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_null_called_with_invalid_swagger_json_content() {
        String pageExtension = "json";
        String pageContent = "{\"swaXXXger\":\"2.0\",\"info\":";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertNull(type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_swagger_called_with_valid_swagger_yml_content() {
        String pageExtension = "yml";
        String pageContent = "swagger: 2.0\ninfo:";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertSame(SWAGGER, type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_null_called_with_invalid_swagger_yml_content() {
        String pageExtension = "yml";
        String pageContent = "swagXXXger: 2.0\ninfo:";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertNull(type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_asyncapi_called_with_valid_asyncapi_json_content() {
        String pageExtension = "json";
        String pageContent = "{\"asyncapi\":\"2.0\",\"info\":";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertSame(ASYNCAPI, type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_null_called_with_invalid_asyncapi_json_content() {
        String pageExtension = "json";
        String pageContent = "{\"asyXXapi\":\"2.0\",\"info\":";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertNull(type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_asyncapi_called_with_valid_asyncapi_yml_content() {
        String pageExtension = "yml";
        String pageContent = "asyncapi: 2.0\ninfo:";
        pageContent = "asyncapi: 2.0\ninfo";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertSame(ASYNCAPI, type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_null_called_with_invalid_asyncapi_yml_content() {
        String pageExtension = "yml";
        String pageContent = "asyXXXpi: 2.0\ninfo:";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertNull(type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_asciidoc_called_with_asciidoc_extension() {
        String pageExtension = "adoc";
        String pageContent = "This is an asciidoc document";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertSame(ASCIIDOC, type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_markdown_called_with_markdown_extension() {
        String pageExtension = "md";
        String pageContent = "This is a markdown document";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertSame(MARKDOWN, type);
    }

    @Test
    public void fromPageExtensionAndContent_should_return_null_called_with_unknown_extension() {
        String pageExtension = "tzr";
        String pageContent = "This is an unknown format document";

        PageType type = PageType.fromPageExtensionAndContent(pageExtension, pageContent);

        assertNull(type);
    }
}
