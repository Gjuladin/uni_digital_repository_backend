/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.util;

/** Removes code points that cannot be represented in XML 1.0 text. */
public final class Xml10TextSanitizer {

    private Xml10TextSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints()
                .filter(Xml10TextSanitizer::isValidXml10CodePoint)
                .forEach(sanitized::appendCodePoint);
        return sanitized.toString();
    }

    private static boolean isValidXml10CodePoint(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
