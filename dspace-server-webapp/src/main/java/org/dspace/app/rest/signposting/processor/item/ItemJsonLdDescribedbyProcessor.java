/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.signposting.processor.item;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.dspace.app.rest.signposting.model.LinksetNode;
import org.dspace.app.rest.signposting.model.LinksetRelationType;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.util.FrontendUrlService;

/** Advertises the schema.org JSON-LD representation without replacing DataCite XML. */
public class ItemJsonLdDescribedbyProcessor extends ItemSignpostingProcessor {

    private final ConfigurationService configurationService;

    public ItemJsonLdDescribedbyProcessor(
            FrontendUrlService frontendUrlService,
            ConfigurationService configurationService
    ) {
        super(frontendUrlService);
        this.configurationService = configurationService;
        setRelation(LinksetRelationType.DESCRIBED_BY);
    }

    @Override
    public void addLinkSetNodes(
            Context context,
            HttpServletRequest request,
            Item item,
            List<LinksetNode> linksetNodes
    ) {
        String baseUrl = configurationService.getProperty("dspace.ui.url");
        String signpostingPath = configurationService.getProperty("signposting.path");
        String itemUrl = buildAnchor(context, item);
        String jsonLdUrl = baseUrl + "/" + signpostingPath + "/describedby-jsonld/" + item.getID();
        linksetNodes.add(new LinksetNode(
                jsonLdUrl,
                getRelation(),
                "application/ld+json",
                "https://schema.org",
                itemUrl
        ));
    }
}
