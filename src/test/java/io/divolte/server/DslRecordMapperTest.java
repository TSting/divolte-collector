/*
 * Copyright 2014 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server;

import static io.divolte.server.BaseEventHandler.*;
import static org.junit.Assert.*;
import io.divolte.server.ServerTestUtils.EventPayload;
import io.divolte.server.ServerTestUtils.TestServer;
import io.divolte.server.recordmapping.SchemaMappingException;
import io.undertow.server.HttpServerExchange;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.junit.After;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.common.io.Resources;

public class DslRecordMapperTest {
    private static final String DIVOLTE_URL_STRING = "http://localhost:%d/csc-event";
    private static final String DIVOLTE_URL_QUERY_STRING = "?"
            + "p=0%3Ai0rjfnxc%3AJLOvH9Nda2c1uV8M~vmdhPGFEC3WxVNq&"
            + "s=0%3Ai0rjfnxc%3AFPpXFMdcEORvvaP_HbpDgABG3Iu5__4d&"
            + "v=0%3AOxVC1WJ4PZNEGIUuzdXPsy_bztnKMuoH&"
            + "e=0%3AOxVC1WJ4PZNEGIUuzdXPsy_bztnKMuoH0&"
            + "c=i0rjfnxd&"
            + "n=t&"
            + "f=t&"
            + "i=sg&"
            + "j=sg&"
            + "k=2&"
            + "w=sa&"
            + "h=sa&"
            + "t=pageView";

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/38.0.2125.122 Safari/537.36";

    public TestServer server;
    private File mappingFile;
    private File avroFile;

    @Test
    public void shouldPopulateFlatFields() throws InterruptedException, IOException {
        setupServer("flat-mapping.groovy");

        EventPayload event = request("https://example.com/", "http://example.com/");
        final GenericRecord record = event.record;
        final HttpServerExchange exchange = event.exchange;

        assertEquals(true, record.get("sessionStart"));
        assertEquals(true, record.get("unreliable"));
        assertEquals(false, record.get("dupe"));
        assertEquals(exchange.getAttachment(REQUEST_START_TIME_KEY), record.get("ts"));
        assertEquals("https://example.com/", record.get("location"));
        assertEquals("http://example.com/", record.get("referer"));

        assertEquals(USER_AGENT, record.get("userAgentString"));
        assertEquals("Chrome", record.get("userAgentName"));
        assertEquals("Chrome", record.get("userAgentFamily"));
        assertEquals("Google Inc.", record.get("userAgentVendor"));
        assertEquals("Browser", record.get("userAgentType"));
        assertEquals("38.0.2125.122", record.get("userAgentVersion"));
        assertEquals("Personal computer", record.get("userAgentDeviceCategory"));
        assertEquals("OS X", record.get("userAgentOsFamily"));
        assertEquals("10.10.1", record.get("userAgentOsVersion"));
        assertEquals("Apple Computer, Inc.", record.get("userAgentOsVendor"));

        assertEquals(exchange.getAttachment(PARTY_COOKIE_KEY).value, record.get("client"));
        assertEquals(exchange.getAttachment(SESSION_COOKIE_KEY).value, record.get("session"));
        assertEquals(exchange.getAttachment(PAGE_VIEW_ID_KEY), record.get("pageview"));
        assertEquals(exchange.getAttachment(EVENT_ID_KEY), record.get("event"));
        assertEquals(1018, record.get("viewportWidth"));
        assertEquals(1018, record.get("viewportHeight"));
        assertEquals(1024, record.get("screenWidth"));
        assertEquals(1024, record.get("screenHeight"));
        assertEquals(2, record.get("pixelRatio"));
        assertEquals("pageView", record.get("eventType"));

        Stream.of(
                "sessionStart",
                "unreliable",
                "dupe",
                "ts",
                "location",
                "referer",
                "userAgentString",
                "userAgentName",
                "userAgentFamily",
                "userAgentVendor",
                "userAgentType",
                "userAgentVersion",
                "userAgentDeviceCategory",
                "userAgentOsFamily",
                "userAgentOsVersion",
                "userAgentOsVendor",
                "client",
                "session",
                "pageview",
                "event",
                "viewportWidth",
                "viewportHeight",
                "screenWidth",
                "screenHeight",
                "pixelRatio",
                "eventType")
              .forEach((v) -> assertNotNull(record.get(v)));
    }

    @Test(expected=SchemaMappingException.class)
    public void shouldFailOnStartupIfMappingMissingField() throws IOException {
        setupServer("missing-field-mapping.groovy");
    }

    @Test
    public void shouldSetCustomCookieValue() throws InterruptedException, IOException {
        setupServer("custom-cookie-mapping.groovy");
        EventPayload event = request("http://example.com");
        assertEquals("custom_cookie_value", event.record.get("customCookie"));
    }

    @Test
    public void shouldApplyActionsInClosureWhenEqualToConditionHolds() throws IOException, InterruptedException {
        setupServer("when-mapping.groovy");
        EventPayload event = request("http://www.example.com/", "http://www.example.com/somepage.html");

        assertEquals("locationmatch", event.record.get("eventType"));
        assertEquals("referermatch", event.record.get("client"));
        assertEquals(new Utf8("not set"), event.record.get("queryparam"));
    }

    @Test
    public void shouldChainValueProducersWithIntermediateNull() throws IOException, InterruptedException {
        setupServer("chained-na-mapping.groovy");
        EventPayload event = request("http://www.exmaple.com/");
        assertEquals(new Utf8("not set"), event.record.get("queryparam"));
    }

    @Test
    public void shouldMatchRegexAndExtractGroups() throws IOException, InterruptedException {
        setupServer("regex-mapping.groovy");
        EventPayload event = request("http://www.example.com/path/with/42/about.html", "http://www.example.com/path/with/13/contact.html");
        assertEquals(true, event.record.get("pathBoolean"));
        assertEquals("42", event.record.get("client"));
        assertEquals("about", event.record.get("pageview"));
    }

    @Test
    public void shouldParseUriComponents() throws IOException, InterruptedException {
        setupServer("uri-mapping.groovy");
        EventPayload event = request(
                "https://www.example.com:8080/path/to/resource/page.html?q=multiple+words+%24%23%25%26&p=10&p=20",
                "http://example.com/path/to/resource/page.html?q=divolte&p=42#/client/side/path?x=value&y=42");

        assertEquals("https", event.record.get("uriScheme"));
        assertEquals("/path/to/resource/page.html", event.record.get("uriPath"));
        assertEquals("www.example.com", event.record.get("uriHost"));
        assertEquals(8080, event.record.get("uriPort"));

        assertEquals("/client/side/path?x=value&y=42", event.record.get("uriFragment"));

        assertEquals("q=multiple+words+$#%&&p=10&p=20", event.record.get("uriQueryString"));
        assertEquals("multiple words $#%&", event.record.get("uriQueryStringValue"));
        assertEquals(Arrays.asList("10", "20"), event.record.get("uriQueryStringValues"));
        assertEquals(
                ImmutableMap.of("p", Arrays.asList("10","20"), "q", Arrays.asList("multiple words $#%&")),
                event.record.get("uriQuery"));
    }

    @Test
    public void shouldParseUriComponentsRaw() throws IOException, InterruptedException {
        setupServer("uri-mapping-raw.groovy");
        EventPayload event = request(
                "http://example.com/path/to/resource%20and%20such/page.html?q=multiple+words+%24%23%25%26&p=42#/client/side/path?x=value&y=42&q=multiple+words+%24%23%25%26");
        assertEquals("/path/to/resource%20and%20such/page.html", event.record.get("uriPath"));
        assertEquals("q=multiple+words+%24%23%25%26&p=42", event.record.get("uriQueryString"));
        assertEquals("/client/side/path?x=value&y=42&q=multiple+words+%24%23%25%26", event.record.get("uriFragment"));
    }

    @Test
    public void shouldParseMinimalUri() throws IOException, InterruptedException {
        /*
         * Test that URI parsing works on URIs that consist of only a path and possibly a query string.
         * This is typical for Angular style applications, where the fragment component of the location
         * is the internal location used within the angular app. In the mapping it should be possible
         * to parse the fragment of the location to a URI again and do path matching and such against
         * it.
         */
        setupServer("uri-mapping-fragment.groovy");
        EventPayload event = request(
                "http://example.com/path/?q=divolte#/client/side/path?x=value&y=42&q=multiple+words+%24%23%25%26");
        assertEquals("/client/side/path", event.record.get("uriPath"));
        assertEquals("x=value&y=42&q=multiple+words+%24%23%25%26", event.record.get("uriQueryString"));
        assertEquals("multiple words $#%&", event.record.get("uriQueryStringValue"));
    }

    @Test
    public void shouldSetCustomHeaders() throws IOException, InterruptedException {
        setupServer("header-mapping.groovy");
        EventPayload event = request("http://www.example.com/");
        assertEquals(Arrays.asList("first", "second", "last"), event.record.get("headerList"));
        assertEquals("first", event.record.get("header"));
        assertEquals("first,second,last", event.record.get("headers"));
    }




    private EventPayload request(String location) throws IOException, InterruptedException {
        return request(location, null);
    }

    private EventPayload request(String location, String referer) throws IOException, InterruptedException {
        final URL url = referer == null ?
                new URL(
                        String.format(DIVOLTE_URL_STRING, server.port) +
                        DIVOLTE_URL_QUERY_STRING +
                        "&l=" + URLEncoder.encode(location, StandardCharsets.UTF_8.name()))
                :
                new URL(
                        String.format(DIVOLTE_URL_STRING, server.port) +
                        DIVOLTE_URL_QUERY_STRING +
                        "&l=" + URLEncoder.encode(location, StandardCharsets.UTF_8.name()) +
                        "&r=" + URLEncoder.encode(referer, StandardCharsets.UTF_8.name()));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.addRequestProperty("User-Agent", USER_AGENT);
        conn.addRequestProperty("Cookie", "custom_cookie=custom_cookie_value;");
        conn.addRequestProperty("X-Divolte-Test", "first");
        conn.addRequestProperty("X-Divolte-Test", "second");
        conn.addRequestProperty("X-Divolte-Test", "last");
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());

        return server.waitForEvent();
    }

    private void setupServer(final String mapping) throws IOException {
        mappingFile = File.createTempFile("test-mapping", ".groovy");
        Files.write(Resources.toByteArray(Resources.getResource(mapping)), mappingFile);

        avroFile = File.createTempFile("TestSchema-", ".avsc");
        Files.write(Resources.toByteArray(Resources.getResource("TestRecord.avsc")), avroFile);

        ImmutableMap<String, Object> mappingConfig = ImmutableMap.of(
                "divolte.tracking.schema_mapping.mapping_script_file", mappingFile.getAbsolutePath(),
                "divolte.tracking.schema_file", avroFile.getAbsolutePath()
                );

        server = new TestServer("dsl-mapping-test.conf", mappingConfig);
        server.server.run();
    }

    @After
    public void shutdown() {
        if (server != null) server.server.shutdown();
        if (mappingFile != null) mappingFile.delete();
        if (avroFile != null) avroFile.delete();
    }
}
