/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.accountmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.dspace.app.rest.utils.DSpaceObjectUtils;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.SiteService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.GroupService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for the feature-owned scoped permission contract.
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentManagerPermissionServiceTest {

    private static final UUID ITEM_ID = UUID.fromString("d13c2e40-bdd8-4f04-9a6a-3c877eaf3284");
    private static final UUID COMMUNITY_ID = UUID.fromString("7dc8b962-b7c8-49be-8231-25a4fef18d17");
    private static final UUID COLLECTION_ID = UUID.fromString("b7fbc58c-054d-4b95-b2f5-cad14363037f");

    @InjectMocks
    private ContentManagerPermissionService service;

    @Mock
    private GroupService groupService;
    @Mock
    private AuthorizeService authorizeService;
    @Mock
    private ResourcePolicyService resourcePolicyService;
    @Mock
    private SiteService siteService;
    @Mock
    private CommunityService communityService;
    @Mock
    private CollectionService collectionService;
    @Mock
    private ItemService itemService;
    @Mock
    private DSpaceObjectUtils dspaceObjectUtils;
    @Mock
    private Context context;
    @Mock
    private EPerson user;
    @Mock
    private Item item;
    @Mock
    private Community community;
    @Mock
    private Collection collection;
    @Mock
    private Group administrators;
    @Mock
    private Group featureGroup;

    @Test
    public void validateAcceptsOnlyThePublicMatrixActions() throws Exception {
        prepareItemScope();

        ContentManagerPermissionService.PermissionAssignment allowed = assignment(
            List.of("READ", "ADD", "WRITE", "DELETE"));
        assertTrue(service.validate(context, List.of(allowed)).isEmpty());

        ContentManagerPermissionService.PermissionAssignment internalAction = assignment(List.of("REMOVE"));
        List<ContentManagerPermissionService.ValidationIssue> issues =
            service.validate(context, List.of(internalAction));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).message().contains("READ, ADD, WRITE, and DELETE"));
    }

    @Test
    public void itemDeleteCreatesTheTargetDeleteAndInternalRemovePolicies() throws Exception {
        prepareItemScope();
        when(groupService.findByName(context, org.dspace.eperson.Group.CONTENT_MANAGER)).thenReturn(null);
        when(resourcePolicyService.find(context, user)).thenReturn(List.of());

        service.apply(context, user, false, List.of(assignment(List.of("DELETE"))));

        ArgumentCaptor<Integer> action = ArgumentCaptor.forClass(Integer.class);
        verify(authorizeService, times(2)).createResourcePolicy(
            eq(context), eq(item), isNull(), eq(user), action.capture(), eq(ResourcePolicy.TYPE_CUSTOM),
            anyString(), anyString(), isNull(), isNull());
        assertTrue(action.getAllValues().contains(Constants.DELETE));
        assertTrue(action.getAllValues().contains(Constants.REMOVE));
    }

    @Test
    public void communityFullControlUsesANestedNativeAdministratorGroup() throws Exception {
        UUID userId = UUID.fromString("78d39d10-aa93-43c7-af0f-36367c440c16");
        when(user.getID()).thenReturn(userId);
        when(community.getID()).thenReturn(COMMUNITY_ID);
        when(community.getType()).thenReturn(Constants.COMMUNITY);
        when(community.getAdministrators()).thenReturn(administrators);
        when(dspaceObjectUtils.findDSpaceObject(context, COMMUNITY_ID)).thenReturn(community);
        when(groupService.search(context, ContentManagerPermissionService.FULL_CONTROL_GROUP_PREFIX + userId + ":"))
            .thenReturn(List.of());
        when(groupService.create(context)).thenReturn(featureGroup);

        ContentManagerPermissionService.PermissionAssignment fullControl =
            new ContentManagerPermissionService.PermissionAssignment("COMMUNITY", COMMUNITY_ID, "Community",
                "Community", true, List.of(), null, true, null, null, null, null);
        service.apply(context, user, null, List.of(fullControl));

        verify(groupService).setName(featureGroup, ContentManagerPermissionService.FULL_CONTROL_GROUP_PREFIX
            + userId + ":COMMUNITY:" + COMMUNITY_ID);
        verify(groupService).addMember(context, featureGroup, user);
        verify(groupService).addMember(context, administrators, featureGroup);
    }

    @Test
    public void selectedCollectionNarrowsAnAccompanyingCommunityAssignment() throws Exception {
        prepareCommunityAndCollectionScopes();
        when(resourcePolicyService.find(context, user)).thenReturn(List.of());

        ContentManagerPermissionService.PermissionAssignment broadCommunity =
            new ContentManagerPermissionService.PermissionAssignment("COMMUNITY", COMMUNITY_ID, "Community",
                "Community", true, List.of(), null, true, null, null, null, null);
        ContentManagerPermissionService.PermissionAssignment selectedCollection =
            new ContentManagerPermissionService.PermissionAssignment("COLLECTION", COLLECTION_ID, "Collection",
                "Community / Collection", false, List.of("READ"), null, true,
                "COMMUNITY", COMMUNITY_ID, "Community", "Community");

        service.apply(context, user, null, List.of(broadCommunity, selectedCollection));

        verify(authorizeService).createResourcePolicy(eq(context), eq(collection), isNull(), eq(user),
            eq(Constants.READ), eq(ResourcePolicy.TYPE_CUSTOM), eq("acct-mgmt:permission"), anyString(),
            isNull(), isNull());
        verify(groupService, never()).create(context);
    }

    @Test
    public void communityPartialPermissionIncludesItsExistingCollections() throws Exception {
        prepareCommunityAndCollectionScopes();
        when(resourcePolicyService.find(context, user)).thenReturn(List.of());
        when(collectionService.findAll(context)).thenReturn(List.of(collection));

        ContentManagerPermissionService.PermissionAssignment broadCommunity =
            new ContentManagerPermissionService.PermissionAssignment("COMMUNITY", COMMUNITY_ID, "Community",
                "Community", false, List.of("READ"), null, true, null, null, null, null);

        service.apply(context, user, null, List.of(broadCommunity));

        verify(authorizeService).createResourcePolicy(eq(context), eq(community), isNull(), eq(user),
            eq(Constants.READ), eq(ResourcePolicy.TYPE_CUSTOM), eq("acct-mgmt:permission"), anyString(),
            isNull(), isNull());
        verify(authorizeService).createResourcePolicy(eq(context), eq(collection), isNull(), eq(user),
            eq(Constants.READ), eq(ResourcePolicy.TYPE_CUSTOM),
            eq("acct-mgmt:community-descendant:" + COMMUNITY_ID + ":permission"), anyString(),
            isNull(), isNull());
    }

    @Test
    public void unfilteredScopeSearchUsesUnpaginatedQueriesAndDoesNotScanItems() throws Exception {
        when(communityService.findAll(context)).thenReturn(List.of());
        when(collectionService.findAll(context)).thenReturn(List.of());

        ContentManagerPermissionService.PermissionScopePage result =
            service.searchScopes(context, "", null, null, 0, 10);

        assertTrue(result.scopes().isEmpty());
        assertEquals(0, result.totalElements());
        verify(communityService).findAll(context);
        verify(collectionService).findAll(context);
        verify(itemService, never()).findAll(context);
    }

    private void prepareItemScope() throws Exception {
        when(item.getID()).thenReturn(ITEM_ID);
        when(item.getType()).thenReturn(Constants.ITEM);
        when(dspaceObjectUtils.findDSpaceObject(context, ITEM_ID)).thenReturn(item);
    }

    private void prepareCommunityAndCollectionScopes() throws Exception {
        when(community.getID()).thenReturn(COMMUNITY_ID);
        when(community.getType()).thenReturn(Constants.COMMUNITY);
        when(collection.getType()).thenReturn(Constants.COLLECTION);
        when(collectionService.getParentObject(context, collection)).thenReturn(community);
        when(dspaceObjectUtils.findDSpaceObject(context, COMMUNITY_ID)).thenReturn(community);
        when(dspaceObjectUtils.findDSpaceObject(context, COLLECTION_ID)).thenReturn(collection);
    }

    private ContentManagerPermissionService.PermissionAssignment assignment(List<String> actions) {
        return new ContentManagerPermissionService.PermissionAssignment(
            "ITEM", ITEM_ID, "Test item", "Community / Collection / Test item", false, actions, null, true,
            null, null, null, null);
    }
}
