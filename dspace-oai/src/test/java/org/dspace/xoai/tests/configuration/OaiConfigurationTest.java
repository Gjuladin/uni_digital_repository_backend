/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source tree.
 */
package org.dspace.xoai.tests.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.text.StringSubstitutor;
import org.dspace.services.ConfigurationService;
import org.dspace.xoai.services.impl.resources.DSpaceResourceResolver;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class OaiConfigurationTest {
    private static final Path CONFIG_ROOT = Path.of("../dspace/config");

    @Test
    public void schemaUrlUsesDeploymentConfigurationAndEscapesXml() throws Exception {
        String schemaUrl = "https://other.example/api/custom-oai/static/dim.xsd?version=1&revision=2";
        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getProperty("oai.config.dir")).thenReturn("../dspace/config/crosswalks/oai");
        when(config.getProperty("oai.dim.schema")).thenReturn(schemaUrl);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (java.io.InputStream input = new DSpaceResourceResolver(config).getResource("xoai.xml")) {
            Document document = factory.newDocumentBuilder().parse(input);
            org.w3c.dom.Element dim = findFormat(document.getElementsByTagNameNS(
                    "http://www.lyncode.com/XOAIConfiguration", "Format"), "dim");
            assertEquals(schemaUrl, text(dim, "http://www.lyncode.com/XOAIConfiguration", "SchemaLocation"));
        }
    }

    @Test
    public void descriptionUsesConfigurableVerifiedSampleIdentifier() throws Exception {
        String modules = Files.readString(CONFIG_ROOT.resolve("modules/oai.cfg"), StandardCharsets.UTF_8);
        assertTrue(modules.contains("oai.identifier.sample = ${handle.prefix}/93"));

        String description = Files.readString(CONFIG_ROOT.resolve("crosswalks/oai/description.xml"),
                                              StandardCharsets.UTF_8);
        String expanded = new StringSubstitutor(Map.of(
                "oai.identifier.prefix", "repository.uist.edu.mk",
                "oai.identifier.sample", "${handle.prefix}/93",
                "handle.prefix", "20.500.15029"))
                .replace(description);
        assertTrue(expanded.contains("<sampleIdentifier>oai:repository.uist.edu.mk:20.500.15029/93"
                                     + "</sampleIdentifier>"));
        assertFalse(expanded.contains("/1234"));
    }

    @Test
    public void defaultContextRetainsOaiDcAndPublishesDimWithDefaultFilter() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(CONFIG_ROOT.resolve("crosswalks/oai/xoai.xml").toFile());
        String namespace = "http://www.lyncode.com/XOAIConfiguration";
        org.w3c.dom.Element context = (org.w3c.dom.Element) document.getElementsByTagNameNS(namespace, "Context")
                .item(0);
        assertEquals("request", context.getAttribute("baseurl"));
        assertEquals("defaultFilter", ((org.w3c.dom.Element) context.getElementsByTagNameNS(namespace, "Filter")
                .item(0)).getAttribute("ref"));
        NodeList formats = context.getElementsByTagNameNS(namespace, "Format");
        assertTrue(hasFormat(formats, "oaidc"));
        assertTrue(hasFormat(formats, "dim"));

        NodeList dimFormats = document.getElementsByTagNameNS(namespace, "Format");
        org.w3c.dom.Element dim = findFormat(dimFormats, "dim");
        assertEquals("dim", text(dim, namespace, "Prefix"));
        assertEquals("http://www.dspace.org/xmlns/dspace/dim", text(dim, namespace, "Namespace"));
        assertEquals("${oai.dim.schema}", text(dim, namespace, "SchemaLocation"));
    }

    private boolean hasFormat(NodeList formats, String ref) {
        for (int i = 0; i < formats.getLength(); i++) {
            if (ref.equals(((org.w3c.dom.Element) formats.item(i)).getAttribute("ref"))) {
                return true;
            }
        }
        return false;
    }

    private org.w3c.dom.Element findFormat(NodeList formats, String id) {
        for (int i = 0; i < formats.getLength(); i++) {
            org.w3c.dom.Element format = (org.w3c.dom.Element) formats.item(i);
            if (id.equals(format.getAttribute("id"))) {
                return format;
            }
        }
        throw new AssertionError("Missing format " + id);
    }

    private String text(org.w3c.dom.Element parent, String namespace, String name) {
        return parent.getElementsByTagNameNS(namespace, name).item(0).getTextContent().trim();
    }
}
