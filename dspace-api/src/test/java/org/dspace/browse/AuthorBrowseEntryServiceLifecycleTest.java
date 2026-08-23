/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.browse;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.dspace.AbstractDSpaceTest;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.discovery.SearchUtils;
import org.dspace.services.ConfigurationService;
import org.dspace.utils.DSpace;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests the independent Author core maintenance lifecycle. */
public class AuthorBrowseEntryServiceLifecycleTest extends AbstractDSpaceTest {

    private ConfigurationService configurationService;
    private String originalIndexingEnabled;
    private String originalPublicEnabled;

    @Before
    public void setUp() {
        configurationService = new DSpace().getConfigurationService();
        originalIndexingEnabled = configurationService.getProperty("discovery.browse-author.indexing-enabled");
        originalPublicEnabled = configurationService.getProperty("discovery.browse-author.enabled");
        configurationService.setProperty("discovery.browse-author.indexing-enabled", "true");
        configurationService.setProperty("discovery.browse-author.enabled", "false");
    }

    @After
    public void restoreConfiguration() {
        configurationService.setProperty("discovery.browse-author.indexing-enabled", originalIndexingEnabled);
        configurationService.setProperty("discovery.browse-author.enabled", originalPublicEnabled);
    }

    @Test
    public void synchronizePopulatesTheCoreBeforePublicAuthorContainsIsEnabled() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        AuthorBrowseEntryService service = serviceUsing(solrClient);
        Item item = item();
        SolrInputDocument itemDocument = authorItemDocument();

        assertFalse(configurationService.getBooleanProperty("discovery.browse-author.enabled", true));
        try (Context authorContext = new Context()) {
            service.synchronize(authorContext, item, itemDocument);
        }

        verify(solrClient).deleteByQuery(eq(AuthorBrowseEntryService.ENTRY_ITEM_FIELD + ":" + item.getID()),
                                         eq(10000));
        verify(solrClient).add(any(java.util.Collection.class), eq(10000));
    }

    @Test
    public void deleteMaintainsTheCoreWhilePublicAuthorContainsIsDisabled() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        AuthorBrowseEntryService service = serviceUsing(solrClient);
        Item item = item();

        assertFalse(configurationService.getBooleanProperty("discovery.browse-author.enabled", true));
        service.delete(item);

        verify(solrClient).deleteByQuery(eq(AuthorBrowseEntryService.ENTRY_ITEM_FIELD + ":" + item.getID()),
                                         eq(10000));
    }

    private AuthorBrowseEntryService serviceUsing(SolrClient solrClient) {
        AuthorBrowseEntryService service = new AuthorBrowseEntryService() {
            @Override
            protected SolrClient newClient() {
                return solrClient;
            }
        };
        service.configurationService = configurationService;
        return service;
    }

    private Item item() {
        Item item = mock(Item.class);
        org.mockito.Mockito.when(item.getID()).thenReturn(UUID.randomUUID());
        return item;
    }

    private SolrInputDocument authorItemDocument() throws BrowseException {
        BrowseIndex author = BrowseIndex.getBrowseIndex("author");
        SolrInputDocument document = new SolrInputDocument();
        document.addField(author.getDistinctTableName() + "_filter",
                          "smith, john" + SearchUtils.FILTER_SEPARATOR + "Smith, John");
        return document;
    }
}
