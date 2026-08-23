/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.browse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.dspace.AbstractDSpaceTest;
import org.dspace.services.ConfigurationService;
import org.dspace.utils.DSpace;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for explicit, logical browse contains capabilities. */
public class BrowseContainsCapabilityTest extends AbstractDSpaceTest {

    private ConfigurationService configurationService;
    private String originalIndexOne;
    private String originalIndexTwo;
    private String originalIndexThree;
    private String originalIndexFour;
    private String originalTitleCapability;
    private String originalAuthorCapability;
    private Boolean originalAuthorEnabled;

    @Before
    public void setUp() {
        configurationService = new DSpace().getConfigurationService();
        originalIndexOne = configurationService.getProperty("webui.browse.index.1");
        originalIndexTwo = configurationService.getProperty("webui.browse.index.2");
        originalIndexThree = configurationService.getProperty("webui.browse.index.3");
        originalIndexFour = configurationService.getProperty("webui.browse.index.4");
        originalTitleCapability = configurationService.getProperty("webui.browse.index.title.contains");
        originalAuthorCapability = configurationService.getProperty("webui.browse.index.author.contains");
        originalAuthorEnabled = configurationService.getBooleanProperty("discovery.browse-author.enabled", false);
    }

    @After
    public void restoreBrowseDefinitions() {
        configurationService.setProperty("webui.browse.index.1", originalIndexOne);
        configurationService.setProperty("webui.browse.index.2", originalIndexTwo);
        configurationService.setProperty("webui.browse.index.3", originalIndexThree);
        configurationService.setProperty("webui.browse.index.4", originalIndexFour);
        configurationService.setProperty("webui.browse.index.title.contains", originalTitleCapability);
        configurationService.setProperty("webui.browse.index.author.contains", originalAuthorCapability);
        configurationService.setProperty("discovery.browse-author.enabled", originalAuthorEnabled.toString());
    }

    @Test
    public void parsesTitleWithoutUsingGeneratedFieldNames() {
        assertEquals(BrowseContainsCapability.TITLE, BrowseContainsCapability.fromConfiguration("title"));
        assertEquals(BrowseContainsCapability.TITLE, BrowseContainsCapability.fromConfiguration("TITLE"));
    }

    @Test
    public void disablesAbsentOrUnknownCapabilities() {
        assertEquals(BrowseContainsCapability.NONE, BrowseContainsCapability.fromConfiguration(null));
        assertEquals(BrowseContainsCapability.AUTHOR, BrowseContainsCapability.fromConfiguration("author"));
        assertEquals(BrowseContainsCapability.NONE, BrowseContainsCapability.fromConfiguration("bi_2_dis"));
    }

    @Test
    public void configuredCapabilityFollowsLogicalNameWhenIndexesAreReordered() throws Exception {
        configurationService.setProperty("webui.browse.index.1", "title:item:title");
        configurationService.setProperty("webui.browse.index.2", "author:metadata:dc.contributor.*\\,dc.creator:text");
        configurationService.setProperty("webui.browse.index.3", "subject:metadata:dc.subject.*:text");
        configurationService.setProperty("webui.browse.index.4", null);
        configurationService.setProperty("webui.browse.index.title.contains", "title");
        configurationService.setProperty("webui.browse.index.author.contains", "author");
        configurationService.setProperty("discovery.browse-author.enabled", "true");

        assertEquals(BrowseContainsCapability.TITLE,
                     BrowseIndex.getBrowseIndex("title").getContainsCapability());
        assertTrue(BrowseIndex.getBrowseIndex("title").isContainsSupported());
        assertEquals(BrowseContainsCapability.AUTHOR,
                     BrowseIndex.getBrowseIndex("author").getContainsCapability());
        assertTrue(BrowseIndex.getBrowseIndex("author").isContainsSupported());
    }

    @Test
    public void authorCapabilityIsNotAdvertisedUntilTheDedicatedCoreIsEnabled() throws Exception {
        configurationService.setProperty("webui.browse.index.author.contains", "author");
        configurationService.setProperty("discovery.browse-author.enabled", "false");

        assertEquals(BrowseContainsCapability.AUTHOR,
            BrowseIndex.getBrowseIndex("author").getContainsCapability());
        assertFalse(BrowseIndex.getBrowseIndex("author").isContainsSupported());
    }

    @Test
    public void titleQueryUsesTheConfiguredFieldAndEscapesTheLiteral() {
        String filter = SolrBrowseDAO.buildContainsFilter(BrowseContainsCapability.TITLE, "sort_17", "Runner*");

        assertTrue(filter.startsWith("bi_sort_17_sort:*Runner"));
        assertTrue(filter.contains("\\*"));
        assertTrue(filter.endsWith("*"));
    }

    @Test
    public void unsupportedOrBlankContainsDoesNotBuildAQuery() {
        assertNull(SolrBrowseDAO.buildContainsFilter(BrowseContainsCapability.NONE, "sort_17", "mith"));
        assertNull(SolrBrowseDAO.buildContainsFilter(BrowseContainsCapability.TITLE, "sort_17", " "));
    }

    @Test
    public void authorEntryContainsIndexesSortAndDisplayAsSeparateValues() {
        assertEquals(java.util.List.of("smith, john", "john smith"),
            AuthorBrowseEntryService.searchableValues("smith, john" + org.dspace.discovery.SearchUtils.FILTER_SEPARATOR
                + "john smith" + org.dspace.discovery.SearchUtils.AUTHORITY_SEPARATOR + "authority-1",
                org.dspace.discovery.SearchUtils.FILTER_SEPARATOR));
    }
}
