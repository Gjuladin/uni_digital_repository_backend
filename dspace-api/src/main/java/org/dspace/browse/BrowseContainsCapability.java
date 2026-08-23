/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.browse;

/**
 * Explicit capabilities for a configured browse definition's {@code contains}
 * parameter.  This deliberately describes the logical browse definition, not
 * a generated Solr browse or sort field name.
 */
public enum BrowseContainsCapability {
    NONE,
    TITLE,
    AUTHOR;

    /**
     * Parse the capability configured for a logical browse definition.
     * Unknown values are deliberately disabled so a configuration typo cannot
     * silently broaden an expensive public query.
     *
     * @param value configured capability
     * @return supported capability, or {@link #NONE}
     */
    public static BrowseContainsCapability fromConfiguration(String value) {
        if (TITLE.name().equalsIgnoreCase(value)) {
            return TITLE;
        }
        if (AUTHOR.name().equalsIgnoreCase(value)) {
            return AUTHOR;
        }
        return NONE;
    }
}
