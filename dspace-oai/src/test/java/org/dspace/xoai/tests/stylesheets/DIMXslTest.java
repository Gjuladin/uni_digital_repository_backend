/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source tree.
 */
package org.dspace.xoai.tests.stylesheets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.xpath.XPathFactory;

import org.dspace.services.ConfigurationService;
import org.dspace.xoai.services.impl.resources.DSpaceResourceResolver;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class DIMXslTest {
    private static final String DIM_NAMESPACE = "http://www.dspace.org/xmlns/dspace/dim";
    private static final String SCHEMA_URL = "https://repository.uist.edu.mk/server/oai/static/dim.xsd";

    @Test
    public void preservesQualifiedFieldsAndValidatesAgainstHostedSchema() throws Exception {
        String output = transform(getClass().getClassLoader().getResourceAsStream("xoai-real-165.xml"));
        Document document = assertDimOutput(output);
        assertEquals("2025", field(document, "date", "issued"));
        assertEquals("2026-05-21T12:08:27Z", field(document, "date", "accessioned"));
        assertFalse(output.contains("dateAccepted"));
    }

    @Test
    public void preservesExistingRepresentativeXoaiMetadata() throws Exception {
        assertDimOutput(transform(getClass().getClassLoader().getResourceAsStream("xoai-test1.xml")));
    }

    @Test
    public void preservesRightsQualifiersAndTextWithoutExportingInternalSections() throws Exception {
        String input = """
            <metadata xmlns="http://www.lyncode.com/xoai">
              <element name="dc"><element name="rights">
                <element name="license"><element name="en"><field name="value">CC BY &amp; attribution</field>
                <field name="authority">license-key</field><field name="confidence">600</field></element></element>
                <element name="uri"><element name="none"><field name="value">https://creativecommons.org/licenses/by/4.0/</field></element></element>
              </element></element>
              <element name="bundles"><element name="bitstream"><field name="value">internal-file</field></element></element>
              <element name="repository"><field name="value">internal-mail</field></element>
              <element name="license"><field name="value">deposit-license</field></element>
              <element name="others"><field name="value">internal-id</field></element>
            </metadata>
            """;
        Document document = assertDimOutput(transform(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))));
        assertEquals(2, document.getElementsByTagNameNS(DIM_NAMESPACE, "field").getLength());
        assertEquals("CC BY & attribution", field(document, "rights", "license"));
        assertEquals("https://creativecommons.org/licenses/by/4.0/", field(document, "rights", "uri"));
        Element license = (Element) document.getElementsByTagNameNS(DIM_NAMESPACE, "field").item(0);
        assertEquals("en", license.getAttribute("lang"));
        assertEquals("license-key", license.getAttribute("authority"));
        assertEquals("600", license.getAttribute("confidence"));
    }

    private String transform(InputStream input) throws Exception {
        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getProperty("oai.config.dir")).thenReturn("../dspace/config/crosswalks/oai");
        when(config.getProperty("oai.dim.schema")).thenReturn(SCHEMA_URL);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (input) {
            new DSpaceResourceResolver(config).getTemplates("metadataFormats/dim.xsl").newTransformer()
                    .transform(new StreamSource(input), new StreamResult(output));
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private Document assertDimOutput(String output) throws Exception {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        SchemaFactory schemas = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemas.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        schemas.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        schemas.newSchema(getClass().getClassLoader().getResource("static/dim.xsd"))
                .newValidator().validate(new StreamSource(new ByteArrayInputStream(bytes)));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        Element root = document.getDocumentElement();
        assertEquals("dim", root.getLocalName());
        assertEquals(DIM_NAMESPACE, root.getNamespaceURI());
        assertEquals(DIM_NAMESPACE + " " + SCHEMA_URL,
                     root.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation"));
        assertFalse(document.getElementsByTagNameNS(DIM_NAMESPACE, "field").getLength() == 0);
        return document;
    }

    private String field(Document document, String element, String qualifier) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(
                "/*/*[@mdschema='dc' and @element='" + element + "' and @qualifier='" + qualifier + "']",
                document);
    }
}
