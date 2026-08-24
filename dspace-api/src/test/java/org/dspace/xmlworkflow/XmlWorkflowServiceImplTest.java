/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xmlworkflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.eperson.EPerson;
import org.junit.Test;
import org.mockito.MockedStatic;

public class XmlWorkflowServiceImplTest {

    @Test
    public void notifyOfArchiveSkipsSubmitterWithoutEmail() throws Exception {
        XmlWorkflowServiceImpl service = new XmlWorkflowServiceImpl();
        Context context = mock(Context.class);
        Item item = mock(Item.class);
        Collection collection = mock(Collection.class);
        EPerson submitter = mock(EPerson.class);
        when(item.getSubmitter()).thenReturn(submitter);
        when(submitter.getEmail()).thenReturn(null);

        try (MockedStatic<Email> email = mockStatic(Email.class)) {
            service.notifyOfArchive(context, item, collection);

            email.verifyNoInteractions();
        }
    }
}
