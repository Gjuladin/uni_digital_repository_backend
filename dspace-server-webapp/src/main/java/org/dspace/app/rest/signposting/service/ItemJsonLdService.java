/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.signposting.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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

        put(resource, "datePublished", firstNonBlank(
                first(item, "date", "issued"),
                first(item, "date", "copyright"),
                first(item, "date", "available"),
                first(item, "date", "accessioned")));
        put(resource, "inLanguage", firstNonBlank(
                first(item, "language", "iso"),
                first(item, "language", null)));
        put(resource, "keywords", values(item, "subject", Item.ANY));

        String publisher = first(item, "publisher", null);
        if (StringUtils.isNotBlank(publisher)) {
            resource.put("publisher", Map.of("@type", "Organization", "name", publisher));
        }
        resource.put("provider", Map.of("@id", uiUrl + "/#repository"));

        List<String> identifiers = new ArrayList<>();
        for (String qualifier : Arrays.asList("doi", "handle", "uri", "isbn", "issn")) {
            identifiers.addAll(values(item, "identifier", qualifier));
        }
        if (StringUtils.isNotBlank(item.getHandle())) {
            identifiers.add(item.getHandle());
        }
        put(resource, "identifier", identifiers.stream()
                .map(this::normaliseIdentifier)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList()));
        put(resource, "sameAs", values(item, "identifier", "doi").stream()
                .map(this::normaliseDoi)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList()));

        String rights = firstNonBlank(first(item, "rights", "uri"), first(item, "rights", null));
        if (isAbsoluteHttpUrl(rights)) {
            resource.put("license", rights);
        } else {
            put(resource, "copyrightNotice", rights);
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

    private String normaliseIdentifier(String value) {
        String doi = normaliseDoi(value);
        return doi != null ? doi : StringUtils.trimToNull(value);
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
