/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.tests.unit.services.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.dspace.xoai.services.api.EarliestDateResolver;
import org.dspace.xoai.services.api.solr.SolrServerResolver;
import org.dspace.xoai.services.impl.DSpaceEarliestDateResolver;
import org.dspace.xoai.services.impl.xoai.DSpaceRepositoryConfiguration;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class DSpaceEarliestDateResolverTest {
    private final SolrClient client = mock(SolrClient.class);

    private DSpaceEarliestDateResolver resolver() throws Exception {
        SolrServerResolver server = mock(SolrServerResolver.class);
        when(server.getServer()).thenReturn(client);
        DSpaceEarliestDateResolver resolver = new DSpaceEarliestDateResolver();
        ReflectionTestUtils.setField(resolver, "solrServerResolver", server);
        return resolver;
    }

    @Test
    public void usesIndexedRecordDatestampAndAscendingSort() throws Exception {
        Instant earliest = Instant.parse("2026-09-02T12:10:02Z");
        SolrDocument document = new SolrDocument();
        document.setField("item.lastmodified", Date.from(earliest));
        SolrDocumentList documents = new SolrDocumentList();
        documents.add(document);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(documents);
        when(client.query(any(SolrQuery.class))).thenAnswer(invocation -> {
            SolrQuery query = invocation.getArgument(0);
            assertEquals(Integer.valueOf(1), query.getRows());
            assertTrue(query.getSortField().startsWith("item.lastmodified asc"));
            return response;
        });
        assertEquals(earliest, resolver().getEarliestDate(null));
    }

    @Test(expected = SQLException.class)
    public void doesNotReturnCurrentTimeWhenIndexIsUnavailable() throws Exception {
        when(client.query(any(SolrQuery.class))).thenThrow(new SolrServerException("unavailable"));
        resolver().getEarliestDate(null);
    }

    @Test(expected = IllegalStateException.class)
    public void identifyDoesNotSwallowResolverFailureAndAdvertiseCurrentTime() throws Exception {
        EarliestDateResolver dateResolver = mock(EarliestDateResolver.class);
        when(dateResolver.getEarliestDate(null)).thenThrow(new SQLException("unavailable"));
        new DSpaceRepositoryConfiguration(dateResolver, null, null).getEarliestDate();
    }
}
