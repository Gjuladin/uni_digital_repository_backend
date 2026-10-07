/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.services.impl;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocumentList;
import org.dspace.core.Context;
import org.dspace.xoai.exceptions.InvalidMetadataFieldException;
import org.dspace.xoai.services.api.EarliestDateResolver;
import org.dspace.xoai.services.api.solr.SolrServerResolver;
import org.dspace.xoai.solr.DSpaceSolrSearch;
import org.dspace.xoai.solr.exceptions.DSpaceSolrException;
import org.springframework.beans.factory.annotation.Autowired;

public class DSpaceEarliestDateResolver implements EarliestDateResolver {
    @Autowired
    private SolrServerResolver solrServerResolver;

    @Override
    public Instant getEarliestDate(Context context) throws InvalidMetadataFieldException, SQLException {
        // Identify must use the same datestamps as ListRecords/ListIdentifiers,
        // not dc.date.available (which may be absent or changed by an embargo).
        SolrQuery query = new SolrQuery("item.lastmodified:[* TO *]")
            .addField("item.lastmodified")
            .setRows(1)
            .addSort("item.lastmodified", SolrQuery.ORDER.asc);
        try {
            SolrDocumentList documents = DSpaceSolrSearch.query(solrServerResolver.getServer(), query);
            if (!documents.isEmpty()) {
                return ((Date) documents.get(0).getFieldValue("item.lastmodified")).toInstant();
            }
            // A genuinely empty OAI index has no earlier record to advertise.
            return Instant.now();
        } catch (IOException | SolrServerException | DSpaceSolrException e) {
            // Do not silently advertise "now" when the index is unavailable.
            throw new SQLException("Unable to determine earliest OAI datestamp", e);
        }
    }
}
