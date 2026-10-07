/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source tree.
 */
package org.dspace.xoai.tests.stylesheets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** Regression coverage for dataset facts in the public, unqualified DC export. */
public class DatasetXslTest extends AbstractXSLTest {
    @Test
    public void preservesDatasetFactsWithoutChangingFileRights() throws Exception {
        String input = """
            <metadata xmlns="http://www.lyncode.com/xoai">
              <element name="dc">
                <element name="type"><element name="none"><field name="value">Dataset</field></element></element>
                <element name="rights"><element name="none"><field name="value">Permission required for reuse.</field></element></element>
                <element name="identifier"><element name="uri"><element name="none"><field name="value">https://hdl.handle.net/20.500.15029/31</field></element></element></element>
                <element name="relation"><element name="references"><element name="none"><field name="value">https://example.org/source</field></element></element></element>
                <element name="description"><element name="provenance"><element name="none"><field name="value">private depositor email</field></element></element></element>
              </element>
              <element name="local">
                <element name="dataset">
                  <element name="version"><element name="none"><field name="value">1.0-test</field></element></element>
                  <element name="methods"><element name="en"><field name="value">Compile published aggregates.</field></element></element>
                </element>
                <element name="creator"><element name="affiliation"><element name="en"><field name="value">Compiler — UIST</field></element></element></element>
              </element>
              <element name="dcterms"><element name="accessRights"><element name="en"><field name="value">Embargo until 2026-11-05.</field></element></element></element>
            </metadata>
            """;
        String result = transform(input);
        assertTrue(result.contains("Dataset version: 1.0-test"));
        assertTrue(result.contains("Methods: Compile published aggregates."));
        assertTrue(result.contains("Creator affiliation: Compiler — UIST"));
        assertTrue(result.contains("Embargo until 2026-11-05."));
        assertTrue(result.contains("Permission required for reuse."));
        assertTrue(result.contains("https://example.org/source"));
        assertTrue(result.contains("https://hdl.handle.net/20.500.15029/31"));
        assertFalse(result.contains("CC0"));
        assertFalse(result.contains("private depositor email"));
    }

    @Test
    public void doesNotInventMissingDatasetFacts() throws Exception {
        String result = transform("<metadata xmlns=\"http://www.lyncode.com/xoai\"/>");
        assertFalse(result.contains("Dataset version:"));
        assertFalse(result.contains("Methods:"));
        assertFalse(result.contains("Creator affiliation:"));
        assertFalse(result.contains("CC0"));
    }

    private String transform(String xml) throws Exception {
        return apply("oai_dc.xsl").to(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
