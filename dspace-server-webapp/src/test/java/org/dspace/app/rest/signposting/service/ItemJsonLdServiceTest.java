/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.signposting.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ItemJsonLdServiceTest {

    private final ItemJsonLdService service = new ItemJsonLdService();

    @Test
    public void mapsConfiguredSubmissionTypesToSchemaOrg() {
        assertEquals("Dataset", service.schemaType("Dataset"));
        assertEquals("ScholarlyArticle", service.schemaType("Journal Article"));
        assertEquals("ScholarlyArticle", service.schemaType("Preprint"));
        assertEquals("Book", service.schemaType("Book"));
        assertEquals("Chapter", service.schemaType("Book Chapter"));
        assertEquals("Report", service.schemaType("Technical Report"));
        assertEquals("SoftwareSourceCode", service.schemaType("Software"));
        assertEquals("Thesis", service.schemaType("Doctoral Thesis"));
        assertEquals("Thesis", service.schemaType("Academic work"));
        assertEquals("CreativeWork", service.schemaType("Learning Object"));
        assertEquals("CreativeWork", service.schemaType(null));
    }

    @Test
    public void usesEntityTypeOnlyWhenDcTypeIsAbsent() {
        assertEquals("Book", service.schemaType("Book", "Publication"));
        assertEquals("Dataset", service.schemaType(null, "Dataset"));
        assertEquals("ScholarlyArticle", service.schemaType(null, "Publication"));
    }
}
