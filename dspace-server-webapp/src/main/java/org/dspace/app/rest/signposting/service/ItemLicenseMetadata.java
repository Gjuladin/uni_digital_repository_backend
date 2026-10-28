/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.signposting.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/** Keeps machine-readable licence links separate from narrative rights metadata. */
public final class ItemLicenseMetadata {

    private ItemLicenseMetadata() {
    }

    /** Return a complete HTTP(S) licence URI, or null for malformed values and prose. */
    public static String httpUrl(String value) {
        String candidate = StringUtils.trimToNull(value);
        if (candidate == null) {
            return null;
        }
        try {
            URI uri = new URI(candidate);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null ? candidate : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Preserve textual licences and notices without inferring a licence URI from prose. */
    public static void applyTo(Map<String, Object> resource, String rightsUri, String licenseText, String rightsText) {
        String uri = StringUtils.trimToNull(rightsUri);
        String license = StringUtils.trimToNull(licenseText);
        String rights = StringUtils.trimToNull(rightsText);
        String licenseValue = StringUtils.firstNonBlank(httpUrl(uri), httpUrl(license), httpUrl(rights), license);
        if (licenseValue != null) {
            resource.put("license", licenseValue);
        }
        Set<String> notices = new LinkedHashSet<>();
        for (String value : new String[] { rights, uri }) {
            if (value != null && httpUrl(value) == null && !value.equals(licenseValue)) {
                notices.add(value);
            }
        }
        if (!notices.isEmpty()) {
            resource.put("copyrightNotice", String.join("\n", notices));
        }
    }
}
