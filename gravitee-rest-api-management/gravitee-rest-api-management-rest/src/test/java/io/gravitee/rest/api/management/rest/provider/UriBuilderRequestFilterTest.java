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
package io.gravitee.rest.api.management.rest.provider;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Tests {@link UriBuilderRequestFilter}
 *
 * @author Zdenek Obst
 */
public class UriBuilderRequestFilterTest {

    @InjectMocks
    protected UriBuilderRequestFilter filter;
    @Mock
    protected ContainerRequestContext containerRequestContext;
    @Mock
    protected UriBuilder baseUriBuilder;
    @Mock
    protected UriBuilder requestUriBuilder;

    @Before
    public void setUp() {
        initMocks(this);
        setupBuildersMocks();
    }

    @Test
    public void noForwardedHeadersCausesNoBuilderInvocations() {
        givenHeaders(); // no X-Forwarded-* headers

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeNeverInvoked();
        verifyUriBuildersHostNeverInvoked();
        verifyUriBuildersPortNeverInvoked();
        // no build() was invoked
    }

    @Test
    public void protoHeaderCausesUriBuildersSchemeSet() {
        givenHeaders(
                "X-Forwarded-Proto", "https"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeInvoked("https"); // override with Proto header
        verifyUriBuildersHostNeverInvoked();
        verifyUriBuildersPortNeverInvoked();
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void hostHeaderWithoutPortCausesUriBuildersHostSetAndPortReset() {
        givenHeaders(
                "X-Forwarded-Host", "gravitee.io"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeNeverInvoked();
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(-1); // reset explicit port
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void portHeaderCausesUriBuildersPortSet() {
        givenHeaders(
                "X-Forwarded-Port", "1234"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeNeverInvoked();
        verifyUriBuildersHostNeverInvoked();
        verifyUriBuildersPortInvokedInOrder(1234); // override with Port header
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void hostHeaderWithPortCausesUriBuildersHostSetAndPortSet() {
        givenHeaders(
                "X-Forwarded-Host", "gravitee.io:4321"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeNeverInvoked();
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(4321); // override with port in Host header
        verifyUriBuildersLastInvocationWasBuild();
    }


    @Test
    public void hostHeaderWithoutPortAndPortHeaderCauseUriBuildersHostSetAndPortSet() {
        givenHeaders(
                "X-Forwarded-Host", "gravitee.io",
                "X-Forwarded-Port", "1234"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeNeverInvoked();
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(-1, 1234); // reset explicit port but then override with Port header
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void hostHeaderWithPortAndPortHeaderCauseUriBuildersHostSetAndPortSetFromPortHeader() {
        givenHeaders(
                "X-Forwarded-Host", "gravitee.io:4321",
                "X-Forwarded-Port", "1234"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeNeverInvoked();
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(4321, 1234); // override with Host header but then override with Port header
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void protoHeaderAndHostHeaderWithoutPortCauseUriBuildersSchemeSetHostSetAndPortReset() {
        givenHeaders(
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "gravitee.io"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeInvoked("https");
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(-1); // reset explicit header
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void protoHeaderAndHostHeaderWithPortCauseUriBuildersSchemeSetHostSetAndPortSet() {
        givenHeaders(
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "gravitee.io:4321"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeInvoked("https");
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(4321); // override with Host header
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void protoHeaderAndPortHeaderCauseUriBuildersSchemeSetAndPortSet() {
        givenHeaders(
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Port", "1234"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeInvoked("https");
        verifyUriBuildersHostNeverInvoked();
        verifyUriBuildersPortInvokedInOrder(1234); // override with Host header
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void protoHeaderHostHeaderWithoutPortAndPortHeaderCauseUriBuildersSchemeSetHostSetAndPortSet() {
        givenHeaders(
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "gravitee.io",
                "X-Forwarded-Port", "1234"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeInvoked("https");
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(-1, 1234); // reset explicit port but then override with Port header
        verifyUriBuildersLastInvocationWasBuild();
    }

    @Test
    public void protoHeaderHostHeaderWithPortAndPortHeaderCauseUriBuildersSchemeSetHostSetAndPortSet() {
        givenHeaders(
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "gravitee.io:4321",
                "X-Forwarded-Port", "1234"
        );

        filter.filter(containerRequestContext);

        verifyUriBuildersSchemeInvoked("https");
        verifyUriBuildersHostInvokedInOrder("gravitee.io"); // override with Host header
        verifyUriBuildersPortInvokedInOrder(4321, 1234); // override with Host header but then override with Port header
        verifyUriBuildersLastInvocationWasBuild();
    }

    private void givenHeaders(String... headers) {
        MultivaluedHashMap<String, String> mockHeaders = new MultivaluedHashMap<>();
        for (int i = 0; i < headers.length / 2; i++) {
            String hName = headers[2 * i];
            String hValue = headers[(2 * i) + 1];
            mockHeaders.put(hName, Collections.singletonList(hValue));
        }
        when(containerRequestContext.getHeaders()).thenReturn(mockHeaders);

    }

    private void setupBuildersMocks() {
        when(baseUriBuilder.host(any())).thenReturn(baseUriBuilder); // in case of chaining builder calls
        when(requestUriBuilder.host(any())).thenReturn(requestUriBuilder); // in case of chaining builder calls

        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUriBuilder()).thenReturn(baseUriBuilder);
        when(uriInfo.getRequestUriBuilder()).thenReturn(requestUriBuilder);
        when(containerRequestContext.getUriInfo()).thenReturn(uriInfo);
    }

    private void verifyUriBuildersSchemeNeverInvoked() {
        verify(baseUriBuilder, never()).scheme(any());
        verify(requestUriBuilder, never()).scheme(any());
    }

    private void verifyUriBuildersHostNeverInvoked() {
        verify(baseUriBuilder, never()).host(any());
        verify(requestUriBuilder, never()).host(any());
    }

    private void verifyUriBuildersPortNeverInvoked() {
        verify(baseUriBuilder, never()).port(anyInt());
        verify(requestUriBuilder, never()).port(anyInt());
    }

    private void verifyUriBuildersSchemeInvoked(String scheme) {
        verify(baseUriBuilder).scheme(scheme);
        verify(requestUriBuilder).scheme(scheme);
    }

    private void verifyUriBuildersHostInvokedInOrder(String... hosts) {
        InOrder inOrder = inOrder(baseUriBuilder, requestUriBuilder);
        for (String host : hosts) {
            inOrder.verify(baseUriBuilder).host(host);
            inOrder.verify(requestUriBuilder).host(host);
        }
        assertEquals(hosts.length, invocationsCount(baseUriBuilder, "host"));
        assertEquals(hosts.length, invocationsCount(requestUriBuilder, "host"));
    }

    private void verifyUriBuildersPortInvokedInOrder(int... ports) {
        InOrder inOrder = inOrder(baseUriBuilder, requestUriBuilder);
        for (int port : ports) {
            inOrder.verify(baseUriBuilder).port(port);
            inOrder.verify(requestUriBuilder).port(port);
        }
        assertEquals(ports.length, invocationsCount(baseUriBuilder, "port"));
        assertEquals(ports.length, invocationsCount(requestUriBuilder, "port"));
    }

    private void verifyUriBuildersLastInvocationWasBuild() {
        assertEquals("build", lastInvocationMethodName(baseUriBuilder));
        assertEquals("build", lastInvocationMethodName(requestUriBuilder));
    }

    private int invocationsCount(Object mock, String methodName) {
        List<Invocation> invocations = sortedInvocations(mock);
        int invocationsCount = 0;
        for (Invocation invocation : invocations) {
            if (invocation.getMethod().getName().equals(methodName)) {
                invocationsCount++;
            }
        }
        return invocationsCount;
    }

    private String lastInvocationMethodName(Object mock) {
        List<Invocation> invocations = sortedInvocations(mock);
        return invocations.get(invocations.size() - 1).getMethod().getName();
    }

    private List<Invocation> sortedInvocations(Object mock) {
        // mocking details invocations seem sorted but API returns Collections so we better sort using sequence number
        List<Invocation> invocations = new ArrayList<>(mockingDetails(mock).getInvocations());
        invocations.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        return invocations;
    }
}
