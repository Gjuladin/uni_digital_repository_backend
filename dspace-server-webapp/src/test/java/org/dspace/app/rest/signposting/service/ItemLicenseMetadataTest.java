/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.signposting.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class ItemLicenseMetadataTest {

    @Test
    public void rejectsNarrativeAndMalformedLicenseLinkTargets() {
        for (String value : new String[] { null, "", "Copyright the authors; CC BY requires verification.",
                "https://creativecommons.org/licenses/by/4.0/ subject to verification",
                "javascript:alert(1)", "https:///missing-host", "https://user:pass@example.org/license" }) {
            assertNull(ItemLicenseMetadata.httpUrl(value));
        }
        assertEquals("https://creativecommons.org/licenses/by/4.0/",
                ItemLicenseMetadata.httpUrl(" https://creativecommons.org/licenses/by/4.0/ "));
    }

    @Test
    public void preservesMalformedUriTextAndSeparateRightsStatementWithoutGuessingLicense() {
        Map<String, Object> resource = new LinkedHashMap<>();
        ItemLicenseMetadata.applyTo(resource, "Source PDF and journal page give different reuse conditions.",
                null, "Copyright the authors.");
        assertFalse(resource.containsKey("license"));
        assertEquals("Copyright the authors.\nSource PDF and journal page give different reuse conditions.",
                resource.get("copyrightNotice"));
    }

    @Test
    public void preservesTextualLicensesAndIndependentNotices() {
        Map<String, Object> resource = new LinkedHashMap<>();
        ItemLicenseMetadata.applyTo(resource, null, "cc-by-nc", "Copyright the authors.");
        assertEquals("cc-by-nc", resource.get("license"));
        assertEquals("Copyright the authors.", resource.get("copyrightNotice"));
    }

    @Test
    public void prefersValidUriAndKeepsOneCopyOfRepeatedNotice() {
        Map<String, Object> resource = new LinkedHashMap<>();
        ItemLicenseMetadata.applyTo(resource, "https://creativecommons.org/licenses/by/4.0/", "cc-by",
                "Copyright the authors.");
        assertEquals("https://creativecommons.org/licenses/by/4.0/", resource.get("license"));
        assertEquals("Copyright the authors.", resource.get("copyrightNotice"));

        resource.clear();
        ItemLicenseMetadata.applyTo(resource, "Review needed.", null, "Review needed.");
        assertEquals("Review needed.", resource.get("copyrightNotice"));
    }
}
