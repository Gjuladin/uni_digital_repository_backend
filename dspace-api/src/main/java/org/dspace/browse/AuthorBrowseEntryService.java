/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.browse;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.json.BucketBasedJsonFacet;
import org.apache.solr.client.solrj.response.json.BucketJsonFacet;
import org.apache.solr.common.SolrInputDocument;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.discovery.DiscoverQuery;
import org.dspace.discovery.DiscoverResult;
import org.dspace.discovery.DiscoverResult.FacetResult;
import org.dspace.discovery.SearchServiceException;
import org.dspace.discovery.SearchUtils;
import org.dspace.discovery.SolrServiceSearchPlugin;
import org.dspace.discovery.configuration.DiscoveryConfigurationParameters;
import org.dspace.service.impl.HttpConnectionPoolService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Maintains one document per Author browse entry and Item in the dedicated
 * {@code browse-author} core.  The normal Discovery document remains the
 * source of truth for ordinary browse and item display.
 */
public class AuthorBrowseEntryService {

    static final String ENTRY_TYPE_FIELD = "browse_author_type_filter";
    static final String ENTRY_TYPE = "author";
    static final String ENTRY_ITEM_FIELD = "browse_author_item_filter";
    static final String ENTRY_FACET_FIELD = "browse_author_facet_filter";
    static final String ENTRY_CONTAINS_FIELD = "browse_author_contains_ngram";

    @Autowired
    protected ConfigurationService configurationService;
    @Autowired
    protected HttpConnectionPoolService httpConnectionPoolService;

    /** Rebuild this Item's Author-entry documents from its already-built Discovery document. */
    public void synchronize(Context context, Item item, SolrInputDocument itemDocument)
        throws IOException, SolrServerException, BrowseException {
        if (!isIndexingEnabled()) {
            return;
        }
        BrowseIndex author = BrowseIndex.getBrowseIndex("author");
        if (author == null) {
            return;
        }
        Collection<Object> entries = itemDocument.getFieldValues(author.getDistinctTableName() + "_filter");
        try (SolrClient solr = newClient()) {
            solr.deleteByQuery(ENTRY_ITEM_FIELD + ":" + item.getID(), commitWithin());
            if (entries != null && !entries.isEmpty()) {
                List<SolrInputDocument> documents = entries.stream()
                    .map(Object::toString)
                    .map(entry -> entryDocument(item, itemDocument, entry))
                    .collect(Collectors.toList());
                solr.add(documents, commitWithin());
            }
        }
    }

    /** Remove all Author-entry documents for an Item when the Item is unindexed. */
    public void delete(Item item) throws IOException, SolrServerException {
        if (!isIndexingEnabled()) {
            return;
        }
        try (SolrClient solr = newClient()) {
            solr.deleteByQuery(ENTRY_ITEM_FIELD + ":" + item.getID(), commitWithin());
        }
    }

    /** Execute a bounded Author-entry contains browse in the dedicated core. */
    public DiscoverResult browse(Context context, String contains, int offset, int limit, boolean ascending,
                                 DSpaceObject container, List<String> defaultFilterQueries,
                                 String discoveryConfigurationName, String facetField)
        throws IOException, SolrServerException, SearchServiceException {
        SolrQuery query = new SolrQuery("*:*");
        query.setRows(0);
        query.addFilterQuery(ENTRY_TYPE_FIELD + ":" + ENTRY_TYPE);
        query.addFilterQuery(ENTRY_CONTAINS_FIELD + ":" + escape(contains));
        if (container != null) {
            String field = container.getType() == org.dspace.core.Constants.COLLECTION ? "location.coll"
                : container.getType() == org.dspace.core.Constants.COMMUNITY ? "location.comm" : null;
            if (field != null) {
                query.addFilterQuery(field + ":" + container.getID());
            }
        }
        if (defaultFilterQueries != null) {
            defaultFilterQueries.forEach(query::addFilterQuery);
        }
        addSearchPluginFilters(context, query, discoveryConfigurationName);

        ObjectNode facets = JsonNodeFactory.instance.objectNode();
        ObjectNode entriesFacet = facets.putObject("entries");
        entriesFacet.put("type", "terms");
        entriesFacet.put("field", ENTRY_FACET_FIELD);
        entriesFacet.put("limit", limit);
        entriesFacet.put("offset", offset);
        entriesFacet.put("numBuckets", true);
        entriesFacet.put("sort", ascending ? "index" : "index desc");
        query.set("json.facet", facets.toString());
        try (SolrClient solr = newClient()) {
            QueryResponse response = solr.query(query);
            DiscoverResult result = new DiscoverResult();
            BucketBasedJsonFacet entries = response.getJsonFacetingResponse().getBucketBasedFacets("entries");
            result.setTotalEntries(entries == null ? 0 : entries.getNumBucketsCount());
            if (entries != null) {
                for (BucketJsonFacet bucket : entries.getBuckets()) {
                    // SolrBrowseDAO reads facet results using its configured distinct
                    // browse field (for example, bi_2_dis), not the logical "author"
                    // browse name. Keep that contract when the generated index number
                    // changes.
                    result.addFacetResult(facetField, toFacetResult(bucket));
                }
            }
            return result;
        }
    }

    /**
     * Whether the dedicated core is maintained by normal item-indexing events.
     * This is intentionally independent from public query availability: operators
     * must enable maintenance before a full reindex, and keep it enabled while
     * temporarily disabling the public Author contains endpoint.
     */
    boolean isIndexingEnabled() {
        return configurationService.getBooleanProperty("discovery.browse-author.indexing-enabled", false);
    }

    protected SolrClient newClient() {
        return new HttpSolrClient.Builder(configurationService.getProperty("discovery.browse-author.server"))
            .withHttpClient(httpConnectionPoolService.getClient()).build();
    }

    private int commitWithin() {
        return configurationService.getIntProperty("discovery.browse-author.commit-within-ms", 10000);
    }

    SolrInputDocument entryDocument(Item item, SolrInputDocument itemDocument, String entry) {
        SolrInputDocument document = new SolrInputDocument();
        document.addField(SearchUtils.RESOURCE_UNIQUE_ID,
            "browse-author-" + item.getID() + "-" + DigestUtils.sha256Hex(entry));
        document.addField(SearchUtils.RESOURCE_TYPE_FIELD, "Item");
        document.addField(SearchUtils.RESOURCE_ID_FIELD, item.getID().toString());
        document.addField(ENTRY_TYPE_FIELD, ENTRY_TYPE);
        document.addField(ENTRY_ITEM_FIELD, item.getID().toString());
        document.addField(ENTRY_FACET_FIELD, entry);
        for (String searchableValue : searchableValues(entry, facetSeparator())) {
            document.addField(ENTRY_CONTAINS_FIELD, searchableValue);
        }

        copy(itemDocument, document, "location.coll");
        copy(itemDocument, document, "location.comm");
        copy(itemDocument, document, "latestVersion");
        copy(itemDocument, document, "withdrawn");
        copy(itemDocument, document, "discoverable");
        // The standard Discovery search plugins enforce item visibility with
        // these fields. Copy them into this entry-level core before running the
        // same plugin pipeline for every Author contains request.
        copy(itemDocument, document, "read");
        copy(itemDocument, document, "admin");
        return document;
    }

    private void addSearchPluginFilters(Context context, SolrQuery query, String discoveryConfigurationName)
        throws SearchServiceException {
        DiscoverQuery discoveryQuery = new DiscoverQuery();
        discoveryQuery.setDiscoveryConfigurationName(discoveryConfigurationName);
        for (SolrServiceSearchPlugin searchPlugin : getSearchPlugins()) {
            searchPlugin.additionalSearchParameters(context, discoveryQuery, query);
        }
    }

    /**
     * Provide the exact search-plugin pipeline used by the Discovery core.
     * Kept overridable for focused regression tests.
     */
    protected List<SolrServiceSearchPlugin> getSearchPlugins() {
        return DSpaceServicesFactory.getInstance().getServiceManager().getServicesByType(SolrServiceSearchPlugin.class);
    }

    private void copy(SolrInputDocument source, SolrInputDocument target, String field) {
        Collection<Object> values = source.getFieldValues(field);
        if (values != null) {
            values.forEach(value -> target.addField(field, value));
        }
    }

    static List<String> searchableValues(String entry, String separator) {
        int separatorIndex = entry.indexOf(separator);
        String sort = separatorIndex < 0 ? entry : entry.substring(0, separatorIndex);
        String displayWithAuthority = separatorIndex < 0 ? entry : entry.substring(separatorIndex + separator.length());
        int authorityIndex = displayWithAuthority.indexOf(SearchUtils.AUTHORITY_SEPARATOR);
        String display = authorityIndex < 0 ? displayWithAuthority : displayWithAuthority.substring(0, authorityIndex);
        return List.of(sort.toLowerCase(Locale.ROOT), display.toLowerCase(Locale.ROOT));
    }

    private FacetResult toFacetResult(BucketJsonFacet bucket) {
        String entry = bucket.getVal().toString();
        String separator = facetSeparator();
        String[] parts = entry.split(Pattern.quote(separator));
        int start = parts.length / 2;
        String sort = String.join("", java.util.Arrays.copyOfRange(parts, 0, start));
        String displayAndAuthority = String.join("", java.util.Arrays.copyOfRange(parts, start, parts.length));
        String[] displayParts = displayAndAuthority.split(Pattern.quote(SearchUtils.AUTHORITY_SEPARATOR), 2);
        String display = displayParts[0];
        String authority = displayParts.length == 2 && StringUtils.isNotBlank(displayParts[1]) ? displayParts[1] : null;
        return new FacetResult(authority == null ? display : authority, display, authority, sort, bucket.getCount(),
            DiscoveryConfigurationParameters.TYPE_TEXT);
    }

    private String escape(String value) {
        return org.apache.solr.client.solrj.util.ClientUtils.escapeQueryChars(value.toLowerCase(Locale.ROOT));
    }

    private String facetSeparator() {
        return configurationService.getProperty("discovery.solr.facets.split.char", SearchUtils.FILTER_SEPARATOR);
    }
}
