/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.util;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.dspace.AbstractDSpaceTest;
import org.dspace.services.ConfigurationService;
import org.junit.Test;

public class SyndicationFeedDescriptionTest extends AbstractDSpaceTest {
    @Test
    public void sitewideFeedUsesConfiguredDescription() {
        ConfigurationService config = kernelImpl.getConfigurationService();
        Object previous = config.getProperty("webui.feed.description");
        try {
            config.setProperty("webui.feed.description", "Public research outputs from our repository");
            SyndicationFeed feed = new SyndicationFeed();
            feed.populate(null, null, null, List.of());
            assertEquals("Public research outputs from our repository", feed.feed.getDescription());
        } finally {
            config.setProperty("webui.feed.description", previous);
        }
    }
}
