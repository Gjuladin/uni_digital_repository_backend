/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.signposting.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    @Test
    public void normalisesHumanLanguageLabelsToIsoCodes() {
        assertEquals("en", service.normaliseLanguage("English"));
        assertEquals("mk", service.normaliseLanguage("Macedonian"));
        assertEquals("sq", service.normaliseLanguage("sqi"));
        assertEquals("fr-CA", service.normaliseLanguage("fr-CA"));
    }

    @Test
    public void normalisesOnlyValidSlashSeparatedIssuedDates() {
        assertEquals("2024-02-03", service.normaliseIssuedDate("2024/2/3"));
        assertEquals("2024-07", service.normaliseIssuedDate("2024/7"));
        assertEquals("2024/02/31", service.normaliseIssuedDate("2024/02/31"));
        assertEquals("2024", service.normaliseIssuedDate("2024"));
    }

    @Test
    public void normalisesMultiComponentHandlePrefixes() {
        assertEquals("https://hdl.handle.net/20.500.15029/94",
                service.normaliseIdentifier("20.500.15029/94"));
    }

    @Test
    public void onlyKnownPublicIdentityDomainsBecomeSameAsValues() {
        assertEquals("https://doi.org/10.1000/example",
                service.normaliseExternalIdentity("10.1000/example"));
        assertEquals("https://openalex.org/W1234567890",
                service.normaliseExternalIdentity("https://openalex.org/W1234567890"));
        assertNull(service.normaliseExternalIdentity("https://publisher.example/citation"));
        assertNull(service.normaliseExternalIdentity("http://localhost:4000/private-record/3"));
    }
}
