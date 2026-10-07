/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.signposting.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.util.FrontendUrlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Builds the public schema.org JSON-LD representation advertised by Signposting. */
@Service
public class ItemJsonLdService {

    private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://.+", Pattern.CASE_INSENSITIVE);

    @Autowired
    private ItemService itemService;

    @Autowired
    private FrontendUrlService frontendUrlService;

    @Autowired
    private ConfigurationService configurationService;

    public Map<String, Object> build(Context context, Item item) {
        String itemUrl = frontendUrlService.generateUrl(context, item);
        String uiUrl = StringUtils.removeEnd(configurationService.getProperty("dspace.ui.url"), "/");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("@context", "https://schema.org");
        resource.put("@type", schemaType(
                first(item, "type", null),
                first(item, "dspace", "entity", "type")));
        resource.put("@id", itemUrl);
        put(resource, "name", first(item, "title", null));
        put(resource, "headline", first(item, "title", null));
        put(resource, "description", firstNonBlank(
                first(item, "description", "abstract"),
                first(item, "description", null)));
        resource.put("url", itemUrl);

        List<String> creatorNames = new ArrayList<>();
        creatorNames.addAll(values(item, "author", null));
        creatorNames.addAll(values(item, "contributor", "author"));
        creatorNames.addAll(values(item, "creator", null));
        List<Map<String, String>> creators = creatorNames.stream()
                .distinct()
                .map(name -> Map.of("@type", "Person", "name", name))
                .collect(Collectors.toList());
        put(resource, "author", creators);

        put(resource, "version", first(item, "local", "dataset", "version"));
        put(resource, "measurementTechnique", first(item, "local", "dataset", "methods"));
        put(resource, "subjectOf", values(item, "relation", "isreferencedby"));
        put(resource, "isBasedOn", values(item, "relation", "references"));
        put(resource, "datePublished", normaliseIssuedDate(first(item, "date", "issued")));
        put(resource, "dateCreated", first(item, "date", "created"));
        put(resource, "dateModified", firstNonBlank(
                first(item, "date", "updated"),
                first(item, "date", "modified")));
        put(resource, "inLanguage", normaliseLanguage(firstNonBlank(
                first(item, "language", "iso"),
                first(item, "language", null))));
        put(resource, "keywords", values(item, "subject", Item.ANY));

        String publisher = first(item, "publisher", null);
        if (StringUtils.isNotBlank(publisher)) {
            resource.put("publisher", Map.of("@type", "Organization", "name", publisher));
        }
        resource.put("provider", Map.of("@id", uiUrl + "/#repository"));

        List<String> identifiers = new ArrayList<>();
        for (String qualifier : Arrays.asList("doi", "handle", "uri", "isbn", "issn", "openalex")) {
            identifiers.addAll(values(item, "identifier", qualifier));
        }
        // Some useful identifiers (for example OpenAlex IDs) are stored without
        // a qualifier. Include only known identity forms from that bucket so
        // citation strings do not leak into schema.org's identifier property.
        identifiers.addAll(values(item, "identifier", null).stream()
                .filter(this::isMachineReadableIdentifier)
                .collect(Collectors.toList()));
        if (StringUtils.isNotBlank(item.getHandle())) {
            // Item#getHandle is a repository handle even when a test or legacy
            // installation uses a numeric prefix without dots.
            identifiers.add("https://hdl.handle.net/" + item.getHandle());
        }
        put(resource, "identifier", identifiers.stream()
                .map(this::normaliseIdentifier)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList()));
        List<String> externalIdentities = new ArrayList<>();
        for (String qualifier : Arrays.asList("doi", "handle", "openalex")) {
            externalIdentities.addAll(values(item, "identifier", qualifier));
        }
        // Preserve only known identity forms from unqualified metadata. An
        // arbitrary citation or landing-page URL is not automatically a sameAs
        // identity merely because it is an absolute URL.
        externalIdentities.addAll(values(item, "identifier", null).stream()
                .filter(this::isMachineReadableIdentifier)
                .collect(Collectors.toList()));
        put(resource, "sameAs", externalIdentities.stream()
                .filter(value -> !isRepositorySelfIdentifier(value, item))
                .map(this::normaliseExternalIdentity)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList()));

        String rightsText = first(item, "rights", null);
        String rightsLicense = firstNonBlank(
                first(item, "rights", "uri"),
                first(item, "rights", "license"));
        if (StringUtils.isBlank(rightsLicense) && isAbsoluteHttpUrl(rightsText)) {
            // A few records use the unqualified rights field for a licence URL.
            rightsLicense = rightsText;
        }
        if (StringUtils.isNotBlank(rightsLicense)) {
            // rights.license is often a controlled text value (for example
            // "cc-by"). Preserve it as license metadata instead of silently
            // relabelling it as a copyright notice.
            resource.put("license", rightsLicense);
        }
        if (StringUtils.isNotBlank(rightsText) && !rightsText.equals(rightsLicense)) {
            // Keep a separate copyright notice when a record provides both a
            // licence URI/code and an unqualified rights statement.
            resource.put("copyrightNotice", rightsText);
        }
        put(resource, "conditionsOfAccess", firstNonBlank(
                first(item, "dcterms", "accessRights", null),
                first(item, "rights", "accessRights")));

        Collection collection = item.getOwningCollection();
        if (collection != null) {
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("@type", "DataCatalog");
            parent.put("@id", uiUrl + "/collections/" + collection.getID());
            put(parent, "name", collection.getName());
            resource.put("isPartOf", parent);
        }

        return resource;
    }

    private List<String> values(Item item, String element, String qualifier) {
        return itemService.getMetadata(item, "dc", element, qualifier, Item.ANY).stream()
                .map(MetadataValue::getValue)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    private String first(Item item, String element, String qualifier) {
        return values(item, element, qualifier).stream().findFirst().orElse(null);
    }

    private String first(Item item, String schema, String element, String qualifier) {
        return itemService.getMetadata(item, schema, element, qualifier, Item.ANY).stream()
                .map(MetadataValue::getValue)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private String firstNonBlank(String... candidates) {
        return Arrays.stream(candidates).filter(StringUtils::isNotBlank).findFirst().orElse(null);
    }

    private void put(Map<String, Object> target, String key, Object value) {
        Predicate<Object> nonEmptyCollection = candidate ->
                !(candidate instanceof java.util.Collection) || !((java.util.Collection<?>) candidate).isEmpty();
        if (value != null && (!(value instanceof String) || StringUtils.isNotBlank((String) value))
                && nonEmptyCollection.test(value)) {
            target.put(key, value);
        }
    }

    private boolean isAbsoluteHttpUrl(String value) {
        return StringUtils.isNotBlank(value) && ABSOLUTE_HTTP_URL.matcher(value).matches();
    }

    String normaliseIdentifier(String value) {
        String doi = normaliseDoi(value);
        if (doi != null) {
            return doi;
        }
        String handle = normaliseHandle(value);
        return handle != null ? handle : StringUtils.trimToNull(value);
    }

    String normaliseExternalIdentity(String value) {
        String doi = normaliseDoi(value);
        if (doi != null) {
            return doi;
        }
        String handle = normaliseHandle(value);
        if (handle != null) {
            return handle;
        }
        return isOpenAlexIdentifier(value) ? StringUtils.trimToNull(value) : null;
    }

    private String normaliseHandle(String value) {
        String identifier = StringUtils.trimToNull(value);
        if (identifier == null) {
            return null;
        }
        if (identifier.matches("^\\d+(?:\\.\\d+)+/.+$")) {
            return "https://hdl.handle.net/" + identifier;
        }
        String httpPrefix = "http://hdl.handle.net/";
        if (identifier.regionMatches(true, 0, httpPrefix, 0, httpPrefix.length())) {
            return "https://hdl.handle.net/" + identifier.substring(httpPrefix.length());
        }
        String httpsPrefix = "https://hdl.handle.net/";
        if (identifier.regionMatches(true, 0, httpsPrefix, 0, httpsPrefix.length())) {
            return "https://hdl.handle.net/" + identifier.substring(httpsPrefix.length());
        }
        return null;
    }

    private boolean isMachineReadableIdentifier(String value) {
        return normaliseDoi(value) != null || normaliseHandle(value) != null || isOpenAlexIdentifier(value);
    }

    private boolean isOpenAlexIdentifier(String value) {
        String identifier = StringUtils.trimToNull(value);
        return identifier != null
                && (identifier.regionMatches(true, 0, "https://openalex.org/", 0,
                        "https://openalex.org/".length())
                || identifier.regionMatches(true, 0, "http://openalex.org/", 0,
                        "http://openalex.org/".length()));
    }

    private boolean isRepositorySelfIdentifier(String value, Item item) {
        String handle = StringUtils.trimToNull(item.getHandle());
        return handle != null && isAbsoluteHttpUrl(value)
                && value.endsWith("/handle/" + handle);
    }

    String normaliseLanguage(String value) {
        String language = StringUtils.trimToNull(value);
        if (language == null) {
            return null;
        }
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "english", "eng" -> "en";
            case "macedonian", "mkd" -> "mk";
            case "albanian", "sqi" -> "sq";
            case "german", "deu" -> "de";
            case "french", "fra" -> "fr";
            case "italian", "ita" -> "it";
            case "spanish", "spa" -> "es";
            default -> language;
        };
    }

    /**
     * Convert the unambiguous slash-separated date forms found in legacy records
     * to the ISO-8601 forms expected by schema.org. Preserve unknown or invalid
     * values so that this presentation layer does not invent publication dates.
     */
    String normaliseIssuedDate(String value) {
        String date = StringUtils.trimToNull(value);
        if (date == null) {
            return null;
        }
        String[] parts = date.split("/", -1);
        try {
            if (parts.length == 2 && parts[0].matches("\\d{4}") && parts[1].matches("\\d{1,2}")) {
                return YearMonth.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])).toString();
            }
            if (parts.length == 3 && parts[0].matches("\\d{4}")
                    && parts[1].matches("\\d{1,2}") && parts[2].matches("\\d{1,2}")) {
                return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2])).toString();
            }
        } catch (DateTimeException | NumberFormatException e) {
            // Keep the source value for invalid calendar dates.
        }
        return date;
    }

    private String normaliseDoi(String value) {
        String identifier = StringUtils.trimToNull(value);
        if (identifier == null) {
            return null;
        }
        if (identifier.regionMatches(true, 0, "doi:", 0, 4)) {
            identifier = identifier.substring(4).trim();
        }
        if (identifier.matches("^10\\.\\d{4,9}/.+$")) {
            return "https://doi.org/" + identifier;
        }
        if (identifier.toLowerCase().startsWith("https://doi.org/")) {
            return identifier;
        }
        return null;
    }

    String schemaType(String value) {
        String type = StringUtils.defaultString(value).toLowerCase();
        if (type.matches(".*(dataset|data set|database).*$")) {
            return "Dataset";
        }
        if (type.matches(".*(book chapter|chapter).*$")) {
            return "Chapter";
        }
        if (type.matches(".*(book|monograph).*$")) {
            return "Book";
        }
        if (type.matches(".*(thesis|dissertation|academic work).*$")) {
            return "Thesis";
        }
        if (type.matches(".*(report|working paper).*$")) {
            return "Report";
        }
        if (type.matches(".*(software|source code|application).*$")) {
            return "SoftwareSourceCode";
        }
        if (type.matches(".*(article|journal|conference|proceedings|paper|publication|preprint).*$")) {
            return "ScholarlyArticle";
        }
        return "CreativeWork";
    }

    String schemaType(String dcType, String entityType) {
        return schemaType(firstNonBlank(dcType, entityType));
    }
}
