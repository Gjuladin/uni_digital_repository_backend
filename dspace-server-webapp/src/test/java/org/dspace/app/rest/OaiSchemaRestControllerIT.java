/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source tree.
 */
package org.dspace.app.rest;

import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.junit.Test;

public class OaiSchemaRestControllerIT extends AbstractControllerIntegrationTest {
    @Test
    public void dimSchemaIsPubliclyAvailableWithoutAuthentication() throws Exception {
        byte[] body = getClient().perform(get("/oai/static/dim.xsd"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(body)).getDocumentElement();
        assertEquals(XMLConstants.W3C_XML_SCHEMA_NS_URI, root.getNamespaceURI());
        assertEquals("http://www.dspace.org/xmlns/dspace/dim", root.getAttribute("targetNamespace"));
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new StreamSource(new ByteArrayInputStream(body)));
    }
}
