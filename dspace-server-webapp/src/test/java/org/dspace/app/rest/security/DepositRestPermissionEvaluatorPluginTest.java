/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.dspace.app.rest.model.WorkflowItemRest;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Collection;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.services.RequestService;
import org.dspace.services.model.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.security.core.Authentication;

@RunWith(MockitoJUnitRunner.class)
public class DepositRestPermissionEvaluatorPluginTest {

    private static final int WORKSPACE_ITEM_ID = 280;

    @InjectMocks
    private DepositRestPermissionEvaluatorPlugin evaluator;

    @Mock
    private RequestService requestService;

    @Mock
    private WorkspaceItemService workspaceItemService;

    @Mock
    private AuthorizeService authorizeService;

    @Mock
    private Request request;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private Context context;

    @Mock
    private Authentication authentication;

    @Mock
    private EPerson currentUser;

    @Mock
    private EPerson submitter;

    @Mock
    private WorkspaceItem workspaceItem;

    @Mock
    private Collection collection;

    private MockedStatic<ContextUtil> contextUtil;

    @Before
    public void setUp() throws Exception {
        contextUtil = mockStatic(ContextUtil.class);
        when(requestService.getCurrentRequest()).thenReturn(request);
        when(request.getHttpServletRequest()).thenReturn(httpRequest);
        contextUtil.when(() -> ContextUtil.obtainContext(httpRequest)).thenReturn(context);
        when(context.getCurrentUser()).thenReturn(currentUser);
    }

    @After
    public void tearDown() {
        contextUtil.close();
    }

    @Test
    public void allowsOriginalSubmitterResolvedFromRequestContext() throws Exception {
        when(workspaceItemService.find(context, WORKSPACE_ITEM_ID)).thenReturn(workspaceItem);
        when(workspaceItem.getSubmitter()).thenReturn(currentUser);

        assertTrue(canDeposit());
    }

    @Test
    public void allowsCollectionAddPermissionForAnotherSubmitter() throws Exception {
        when(workspaceItemService.find(context, WORKSPACE_ITEM_ID)).thenReturn(workspaceItem);
        when(workspaceItem.getSubmitter()).thenReturn(submitter);
        when(workspaceItem.getCollection()).thenReturn(collection);
        when(authorizeService.authorizeActionBoolean(context, collection, Constants.ADD)).thenReturn(true);

        assertTrue(canDeposit());
    }

    private boolean canDeposit() {
        return evaluator.hasDSpacePermission(authentication, WORKSPACE_ITEM_ID, WorkflowItemRest.NAME,
            DSpaceRestPermission.WRITE);
    }
}
