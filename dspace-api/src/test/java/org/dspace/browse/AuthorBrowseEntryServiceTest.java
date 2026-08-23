/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.browse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.dspace.content.Item;
import org.dspace.discovery.DiscoverResult;
import org.dspace.discovery.SearchUtils;
import org.dspace.discovery.SolrServiceSearchPlugin;
import org.dspace.services.ConfigurationService;
import org.junit.Test;

/** Tests the dedicated Author browse-entry result adapter. */
public class AuthorBrowseEntryServiceTest {

    @Test
    public void browsePublishesEntriesUnderTheDaoFacetField() throws Exception {
        ConfigurationService configurationService = mock(ConfigurationService.class);
        when(configurationService.getProperty("discovery.solr.facets.split.char", SearchUtils.FILTER_SEPARATOR))
            .thenReturn(SearchUtils.FILTER_SEPARATOR);

        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.query(org.mockito.ArgumentMatchers.any())).thenReturn(authorFacetResponse());

        AuthorBrowseEntryService service = new AuthorBrowseEntryService() {
            @Override
            protected SolrClient newClient() {
                return solrClient;
            }

            @Override
            protected List<SolrServiceSearchPlugin> getSearchPlugins() {
                return List.of((context, discoveryQuery, query) -> {
                    assertEquals("default", discoveryQuery.getDiscoveryConfigurationName());
                    query.addFilterQuery("read:(ganonymous) OR admin:(ganonymous)");
                });
            }
        };
        service.configurationService = configurationService;

        DiscoverResult result = service.browse(null, "mith", 0, 10, true, null, List.of(), "default", "bi_17_dis");

        assertEquals(1, result.getTotalEntries());
        assertEquals(1, result.getFacetResult("bi_17_dis").size());
        assertEquals("Smith, John", result.getFacetResult("bi_17_dis").get(0).getDisplayedValue());
        assertTrue(result.getFacetResult("author").isEmpty());

        org.mockito.ArgumentCaptor<SolrQuery> queryCaptor =
            org.mockito.ArgumentCaptor.forClass(SolrQuery.class);
        org.mockito.Mockito.verify(solrClient).query(queryCaptor.capture());
        assertTrue(List.of(queryCaptor.getValue().getFilterQueries())
            .contains("read:(ganonymous) OR admin:(ganonymous)"));
    }

    @Test
    public void entryDocumentsCopyTheAccessFieldsUsedByDiscoverySearchPlugins() {
        ConfigurationService configurationService = mock(ConfigurationService.class);
        when(configurationService.getProperty("discovery.solr.facets.split.char", SearchUtils.FILTER_SEPARATOR))
            .thenReturn(SearchUtils.FILTER_SEPARATOR);

        Item item = mock(Item.class);
        when(item.getID()).thenReturn(UUID.randomUUID());
        SolrInputDocument itemDocument = new SolrInputDocument();
        itemDocument.addField("read", "ganonymous");
        itemDocument.addField("admin", "eadministrator");

        AuthorBrowseEntryService service = new AuthorBrowseEntryService();
        service.configurationService = configurationService;
        SolrInputDocument entry = service.entryDocument(item, itemDocument,
            "smith, john" + SearchUtils.FILTER_SEPARATOR + "Smith, John");

        assertEquals(List.of("ganonymous"), List.copyOf(entry.getFieldValues("read")));
        assertEquals(List.of("eadministrator"), List.copyOf(entry.getFieldValues("admin")));
    }

    @Test
    public void restrictedAuthorEntryDoesNotLeakItsNameOrCountToAnUnauthorizedQuery() throws Exception {
        ConfigurationService configurationService = mock(ConfigurationService.class);
        when(configurationService.getProperty("discovery.solr.facets.split.char", SearchUtils.FILTER_SEPARATOR))
            .thenReturn(SearchUtils.FILTER_SEPARATOR);

        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.query(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            SolrQuery query = invocation.getArgument(0);
            // Model the dedicated core's ACL filter: an entry with read:gprivate
            // cannot match an anonymous query whose plugin allows only gpublic.
            return List.of(query.getFilterQueries()).contains("read:(gpublic) OR admin:(gpublic)")
                ? emptyAuthorFacetResponse() : authorFacetResponse();
        });

        AuthorBrowseEntryService service = new AuthorBrowseEntryService() {
            @Override
            protected SolrClient newClient() {
                return solrClient;
            }

            @Override
            protected List<SolrServiceSearchPlugin> getSearchPlugins() {
                return List.of((context, discoveryQuery, query) ->
                    query.addFilterQuery("read:(gpublic) OR admin:(gpublic)"));
            }
        };
        service.configurationService = configurationService;

        Item restrictedItem = mock(Item.class);
        when(restrictedItem.getID()).thenReturn(UUID.randomUUID());
        SolrInputDocument restrictedSource = new SolrInputDocument();
        restrictedSource.addField("read", "gprivate");
        SolrInputDocument restrictedEntry = service.entryDocument(restrictedItem, restrictedSource,
            "smith, jane" + SearchUtils.FILTER_SEPARATOR + "Smith, Jane");

        DiscoverResult result = service.browse(null, "mith", 0, 10, true, null, List.of(), "default", "bi_2_dis");

        assertEquals(List.of("gprivate"), List.copyOf(restrictedEntry.getFieldValues("read")));
        assertEquals(0, result.getTotalEntries());
        assertTrue(result.getFacetResult("bi_2_dis").isEmpty());
    }

    private QueryResponse authorFacetResponse() {
        NamedList<Object> bucket = new NamedList<>();
        bucket.add("val", "smith, john" + SearchUtils.FILTER_SEPARATOR + "Smith, John");
        bucket.add("count", 1L);

        NamedList<Object> entries = new NamedList<>();
        entries.add("numBuckets", 1L);
        entries.add("buckets", List.of(bucket));

        NamedList<Object> facets = new NamedList<>();
        facets.add("entries", entries);

        NamedList<Object> responseValues = new NamedList<>();
        responseValues.add("facets", facets);
        QueryResponse response = new QueryResponse();
        response.setResponse(responseValues);
        return response;
    }

    private QueryResponse emptyAuthorFacetResponse() {
        NamedList<Object> entries = new NamedList<>();
        entries.add("numBuckets", 0L);
        entries.add("buckets", List.of());

        NamedList<Object> facets = new NamedList<>();
        facets.add("entries", entries);

        NamedList<Object> responseValues = new NamedList<>();
        responseValues.add("facets", facets);
        QueryResponse response = new QueryResponse();
        response.setResponse(responseValues);
        return response;
    }
}
