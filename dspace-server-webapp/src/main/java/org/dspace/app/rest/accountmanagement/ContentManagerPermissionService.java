/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.accountmanagement;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.utils.DSpaceObjectUtils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
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
import org.dspace.discovery.SearchServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Manages the account-management feature's content permissions. Every policy
 * changed here is explicitly marked, so submission, workflow, inherited, and
 * manually-created policies are never replaced by this feature.
 */
@Component
public class ContentManagerPermissionService {

    private static final String POLICY_PREFIX = "acct-mgmt:";
    private static final String PERMISSION_POLICY_NAME = POLICY_PREFIX + "permission";
    private static final String DELETE_POLICY_NAME = POLICY_PREFIX + "delete";
    private static final String DELETE_REMOVE_POLICY_NAME = POLICY_PREFIX + "delete-remove";
    private static final String SITE_ADD_POLICY_NAME = POLICY_PREFIX + "content-manager";
    private static final String COMMUNITY_DESCENDANT_POLICY_PREFIX = POLICY_PREFIX + "community-descendant:";
    static final String FULL_CONTROL_GROUP_PREFIX = POLICY_PREFIX + "full-control:";
    private static final Map<String, Integer> ACTIONS = Map.of(
        "READ", Constants.READ,
        "WRITE", Constants.WRITE,
        "DELETE", Constants.DELETE,
        "ADD", Constants.ADD
    );

    @Autowired
    private GroupService groupService;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private ResourcePolicyService resourcePolicyService;

    @Autowired
    private SiteService siteService;

    @Autowired
    private CommunityService communityService;

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private DSpaceObjectUtils dspaceObjectUtils;

    /** Validate assignments without mutating the current Context. */
    public List<ValidationIssue> validate(Context context, List<PermissionAssignment> assignments) throws SQLException {
        List<ValidationIssue> issues = new ArrayList<>();
        if (assignments == null) {
            return issues;
        }
        Set<UUID> narrowedCommunities = selectedCollectionParentIds(context, assignments);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < assignments.size(); index++) {
            PermissionAssignment assignment = assignments.get(index);
            if (assignment == null) {
                issues.add(new ValidationIssue("permissionAssignments[" + index + "]", "Assignment is required"));
                continue;
            }
            DSpaceObject dso = findScope(context, assignment.resourceType(), assignment.resourceId());
            if (dso == null) {
                issues.add(new ValidationIssue("permissionAssignments[" + index + "].resourceId",
                    "Community, collection, or item scope was not found"));
                continue;
            }
            // A community accompanying one or more direct collection scopes is
            // structural context, not an additional broad grant. The child
            // assignments are authoritative for that branch.
            if (dso instanceof Community && narrowedCommunities.contains(dso.getID())) {
                continue;
            }
            boolean fullControl = Boolean.TRUE.equals(assignment.fullControl());
            if (fullControl && assignment.actions() != null && !assignment.actions().isEmpty()) {
                issues.add(new ValidationIssue("permissionAssignments[" + index + "].actions",
                    "Full control cannot be combined with partial actions"));
            }
            if (!fullControl && (assignment.actions() == null || assignment.actions().isEmpty())) {
                issues.add(new ValidationIssue("permissionAssignments[" + index + "].actions",
                    "Select at least one direct action"));
            }
            if (fullControl) {
                if (!seen.add(dso.getID() + ":ADMIN")) {
                    issues.add(new ValidationIssue("permissionAssignments[" + index + "]", "Duplicate assignment"));
                }
            } else {
                for (String action : assignment.actions()) {
                    Integer actionId = actionId(action);
                    if (actionId == null) {
                        issues.add(new ValidationIssue("permissionAssignments[" + index + "].actions",
                            "Allowed actions are READ, ADD, WRITE, and DELETE"));
                        continue;
                    }
                    if (!seen.add(dso.getID() + ":" + actionId)) {
                        issues.add(new ValidationIssue("permissionAssignments[" + index + "]",
                            "Duplicate resource/action assignment"));
                    }
                }
            }
        }
        return issues;
    }

    /**
     * Applies a fully validated replacement of feature-owned policies. A null
     * assignment list preserves the user's existing scoped assignments.
     */
    public void apply(Context context, EPerson user, Boolean contentManager,
                      List<PermissionAssignment> assignments) throws SQLException, AuthorizeException {
        if (Boolean.TRUE.equals(contentManager)) {
            Group contentManagers = ensureContentManagerGroup(context);
            groupService.addMember(context, contentManagers, user);
        } else if (Boolean.FALSE.equals(contentManager)) {
            Group contentManagers = groupService.findByName(context, Group.CONTENT_MANAGER);
            if (contentManagers != null) {
                groupService.removeMember(context, contentManagers, user);
            }
        }

        if (assignments == null) {
            return;
        }
        for (ResourcePolicy policy : featurePolicies(context, user)) {
            resourcePolicyService.delete(context, policy);
        }
        removeFullControlGroups(context, user);
        Set<UUID> narrowedCommunities = selectedCollectionParentIds(context, assignments);
        for (PermissionAssignment assignment : assignments) {
            DSpaceObject dso = findScope(context, assignment.resourceType(), assignment.resourceId());
            if (dso instanceof Community && narrowedCommunities.contains(dso.getID())) {
                continue;
            }
            if (Boolean.TRUE.equals(assignment.fullControl())) {
                if (dso instanceof Community || dso instanceof Collection) {
                    addNativeFullControl(context, user, dso);
                } else {
                    createPolicy(context, user, dso, Constants.ADMIN, PERMISSION_POLICY_NAME);
                }
            } else {
                for (String action : assignment.actions()) {
                    createActionPolicies(context, user, dso, action);
                    if (dso instanceof Community community) {
                        createCommunityDescendantActionPolicies(context, user, community, action);
                    }
                }
            }
        }
    }

    public boolean isContentManager(Context context, EPerson user) throws SQLException {
        Group group = groupService.findByName(context, Group.CONTENT_MANAGER);
        return group != null && groupService.isDirectMember(group, user);
    }

    public List<PermissionAssignment> assignments(Context context, EPerson user) throws SQLException {
        Map<String, MutableAssignment> byScope = new HashMap<>();
        for (ResourcePolicy policy : featurePolicies(context, user)) {
            if (!isCommunityDescendantPolicy(policy)) {
                addPolicyAssignment(context, byScope, policy, "account-management", true);
            }
        }
        for (Group group : fullControlGroups(context, user)) {
            DSpaceObject dso = fullControlScope(context, group);
            if (dso != null) {
                MutableAssignment assignment = byScope.computeIfAbsent(dso.getID() + "\u0000account-management",
                    ignored -> new MutableAssignment(context, dso, "account-management", true));
                assignment.fullControl = true;
                assignment.actions.clear();
            }
        }
        return toAssignments(byScope);
    }

    /** Existing direct policies which this feature deliberately never changes. */
    public List<PermissionAssignment> unmanagedAssignments(Context context, EPerson user) throws SQLException {
        Map<String, MutableAssignment> byScope = new HashMap<>();
        for (ResourcePolicy policy : resourcePolicyService.find(context, user)) {
            if (!isFeaturePolicy(policy)) {
                addPolicyAssignment(context, byScope, policy, "direct-policy", false);
            }
        }
        return toAssignments(byScope);
    }

    /**
     * Group-backed grants are authoritative but read-only in this workflow.
     * Only policies attached to a group itself are listed: inherited ADMIN
     * access is represented by that parent policy rather than every child it
     * makes effective.
     */
    public List<PermissionAssignment> inheritedAssignments(Context context, EPerson user) throws SQLException {
        Map<String, MutableAssignment> byScope = new HashMap<>();
        Set<UUID> featureAdminGroups = new HashSet<>();
        for (Group featureGroup : fullControlGroups(context, user)) {
            featureGroup.getParentGroups().forEach(parent -> featureAdminGroups.add(parent.getID()));
        }
        for (Group group : groupService.allMemberGroups(context, user)) {
            if (isImplicitGroup(group) || Group.CONTENT_MANAGER.equals(group.getName())
                || isFullControlGroup(group) || featureAdminGroups.contains(group.getID())) {
                continue;
            }
            String source = "group:" + StringUtils.defaultIfBlank(group.getName(), group.getID().toString());
            for (ResourcePolicy policy : resourcePolicyService.find(context, group)) {
                addPolicyAssignment(context, byScope, policy, source, false);
            }
        }
        return toAssignments(byScope);
    }

    /**
     * Searches scopes with stable server-side pagination. Item searches use the
     * existing indexed item autocomplete when an item type is requested.
     */
    public PermissionScopePage searchScopes(Context context, String query, String resourceType, UUID parentId,
                                            int page, int size)
        throws SQLException, SearchServiceException {
        String normalizedQuery = StringUtils.defaultString(query).trim().toLowerCase(Locale.ROOT);
        String requestedType = StringUtils.upperCase(StringUtils.trimToNull(resourceType));
        if (StringUtils.isNotBlank(requestedType) && !Set.of("COMMUNITY", "COLLECTION", "ITEM").contains(requestedType)) {
            throw new IllegalArgumentException("resourceType must be COMMUNITY, COLLECTION, or ITEM");
        }
        DSpaceObject parent = parentId == null ? null : dspaceObjectUtils.findDSpaceObject(context, parentId);
        if (parentId != null && parent == null) {
            throw new IllegalArgumentException("parent scope was not found");
        }
        int offset = Math.multiplyExact(page, size);

        // DSpace's indexed autocomplete is both searchable and authorization
        // aware. It is used for direct item searches, avoiding an unbounded
        // database scan for the normal item-selector path.
        if ("ITEM".equals(requestedType) && parent == null) {
            int total = itemService.countItemsWithEdit(context, normalizedQuery);
            List<PermissionScope> scopes = itemService.findItemsWithEdit(context, normalizedQuery, offset, size).stream()
                .map(item -> toScopeUnchecked(context, item)).toList();
            return new PermissionScopePage(scopes, page, size, total);
        }

        List<PermissionScope> scopes = new ArrayList<>();
        if (StringUtils.isBlank(requestedType) || "COMMUNITY".equals(requestedType)) {
            for (Community community : communityService.findAll(context)) {
                if ((parent == null || hasParent(context, community, parent)) && matches(community, normalizedQuery)) {
                    scopes.add(toScope(context, community));
                }
            }
        }
        if (StringUtils.isBlank(requestedType) || "COLLECTION".equals(requestedType)) {
            for (Collection collection : collectionService.findAll(context)) {
                if ((parent == null || hasParent(context, collection, parent)) && matches(collection, normalizedQuery)) {
                    scopes.add(toScope(context, collection));
                }
            }
        }
        // Do not enumerate the entire item table for the unfiltered selector.
        // Items are intentionally exposed through indexed ITEM search, or when
        // the caller has narrowed the request to a particular collection.
        if ("ITEM".equals(requestedType) || parent instanceof Collection) {
            Iterator<Item> items = parent instanceof Collection collection
                ? itemService.findByCollection(context, collection) : itemService.findAll(context);
            while (items.hasNext()) {
                Item item = items.next();
                if ((parent == null || hasParent(context, item, parent)) && matches(item, normalizedQuery)) {
                    scopes.add(toScope(context, item));
                }
            }
        }
        scopes.sort(Comparator.comparing(PermissionScope::resourcePath, String.CASE_INSENSITIVE_ORDER));
        int total = scopes.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + size, total);
        return new PermissionScopePage(scopes.subList(from, to), page, size, total);
    }

    private Group ensureContentManagerGroup(Context context) throws SQLException, AuthorizeException {
        Group group = groupService.findByName(context, Group.CONTENT_MANAGER);
        if (group == null) {
            group = groupService.create(context);
            groupService.setName(group, Group.CONTENT_MANAGER);
            groupService.setPermanent(group, true);
            groupService.update(context, group);
        } else if (!group.isPermanent()) {
            groupService.setPermanent(group, true);
            groupService.update(context, group);
        }
        DSpaceObject site = siteService.findSite(context);
        Group contentManagerGroup = group;
        boolean hasSiteAdd = authorizeService.getPolicies(context, site).stream()
            .anyMatch(policy -> contentManagerGroup.equals(policy.getGroup()) && policy.getAction() == Constants.ADD
                && SITE_ADD_POLICY_NAME.equals(policy.getRpName()));
        if (!hasSiteAdd) {
            authorizeService.createResourcePolicy(context, site, group, null, Constants.ADD, ResourcePolicy.TYPE_CUSTOM,
                                                   SITE_ADD_POLICY_NAME, "Create top-level communities", null, null);
        }
        ensureTopLevelCommunityPolicies(context, group);
        return group;
    }

    private List<ResourcePolicy> featurePolicies(Context context, EPerson user) throws SQLException {
        return resourcePolicyService.find(context, user).stream().filter(this::isFeaturePolicy).toList();
    }

    private boolean isFeaturePolicy(ResourcePolicy policy) {
        return ResourcePolicy.TYPE_CUSTOM.equals(policy.getRpType())
            && StringUtils.startsWith(policy.getRpName(), POLICY_PREFIX);
    }

    private boolean isCommunityDescendantPolicy(ResourcePolicy policy) {
        return StringUtils.startsWith(policy.getRpName(), COMMUNITY_DESCENDANT_POLICY_PREFIX);
    }

    private void ensureTopLevelCommunityPolicies(Context context, Group group)
        throws SQLException, AuthorizeException {
        for (Community community : communityService.findAll(context)) {
            if (communityService.getParentObject(context, community) != null) {
                continue;
            }
            boolean hasPolicy = authorizeService.getPolicies(context, community).stream()
                .anyMatch(policy -> group.equals(policy.getGroup()) && policy.getAction() == Constants.ADMIN
                    && ResourcePolicy.TYPE_CUSTOM.equals(policy.getRpType())
                    && "acct-mgmt:top-community".equals(policy.getRpName()));
            if (!hasPolicy) {
                authorizeService.createResourcePolicy(context, community, group, null, Constants.ADMIN,
                    ResourcePolicy.TYPE_CUSTOM, "acct-mgmt:top-community", "Content manager administration", null,
                    null);
            }
        }
    }

    private void createPolicy(Context context, EPerson user, DSpaceObject dso, int action, String policyName)
        throws SQLException, AuthorizeException {
        authorizeService.createResourcePolicy(context, dso, null, user, action, ResourcePolicy.TYPE_CUSTOM,
                                               policyName, "Account management content permission", null,
                                               null);
    }

    private void createActionPolicies(Context context, EPerson user, DSpaceObject dso, String requestedAction)
        throws SQLException, AuthorizeException {
        createActionPolicies(context, user, dso, requestedAction, PERMISSION_POLICY_NAME,
            DELETE_POLICY_NAME, DELETE_REMOVE_POLICY_NAME);
    }

    private void createActionPolicies(Context context, EPerson user, DSpaceObject dso, String requestedAction,
                                      String permissionPolicyName, String deletePolicyName,
                                      String deleteRemovePolicyName)
        throws SQLException, AuthorizeException {
        String action = requestedAction.trim().toUpperCase(Locale.ROOT);
        if ("DELETE".equals(action)) {
            createPolicy(context, user, dso, Constants.DELETE, deletePolicyName);
            // Removing an Item from its owning collection is a distinct DSpace
            // authorization check. It is part of the UI's single DELETE grant,
            // but has its own marked policy so it can be safely round-tripped.
            if (dso.getType() == Constants.ITEM) {
                createPolicy(context, user, dso, Constants.REMOVE, deleteRemovePolicyName);
            }
            return;
        }
        createPolicy(context, user, dso, actionId(action), permissionPolicyName);
    }

    private void createCommunityDescendantActionPolicies(Context context, EPerson user, Community community,
                                                         String requestedAction)
        throws SQLException, AuthorizeException {
        String policyPrefix = COMMUNITY_DESCENDANT_POLICY_PREFIX + community.getID() + ":";
        for (Collection collection : collectionService.findAll(context)) {
            if (isWithinCommunity(context, collection, community)) {
                createActionPolicies(context, user, collection, requestedAction,
                    policyPrefix + "permission", policyPrefix + "delete", policyPrefix + "delete-remove");
            }
        }
    }

    private boolean isWithinCommunity(Context context, Collection collection, Community expectedAncestor)
        throws SQLException {
        DSpaceObject parent = directParent(context, collection);
        while (parent instanceof Community community) {
            if (expectedAncestor.getID().equals(community.getID())) {
                return true;
            }
            parent = directParent(context, community);
        }
        return false;
    }

    private Set<UUID> selectedCollectionParentIds(Context context, List<PermissionAssignment> assignments)
        throws SQLException {
        Set<UUID> parentIds = new HashSet<>();
        if (assignments == null) {
            return parentIds;
        }
        for (PermissionAssignment assignment : assignments) {
            if (assignment == null) {
                continue;
            }
            DSpaceObject dso = findScope(context, assignment.resourceType(), assignment.resourceId());
            if (dso instanceof Collection) {
                DSpaceObject parent = directParent(context, dso);
                if (parent instanceof Community) {
                    parentIds.add(parent.getID());
                }
            }
        }
        return parentIds;
    }

    /**
     * Full control for containers is attached to DSpace's native administrator
     * group. A feature-owned nested group lets account management remove only
     * its own grant without touching a manually assigned administrator.
     */
    private void addNativeFullControl(Context context, EPerson user, DSpaceObject dso)
        throws SQLException, AuthorizeException {
        Group administrators;
        if (dso instanceof Community community) {
            administrators = community.getAdministrators();
            if (administrators == null) {
                administrators = communityService.createAdministrators(context, community);
            }
        } else if (dso instanceof Collection collection) {
            administrators = collection.getAdministrators();
            if (administrators == null) {
                administrators = collectionService.createAdministrators(context, collection);
            }
        } else {
            throw new IllegalArgumentException("Native full control is only valid for containers");
        }

        Group featureGroup = groupService.create(context);
        groupService.setName(featureGroup, fullControlGroupName(user, dso));
        groupService.update(context, featureGroup);
        groupService.addMember(context, featureGroup, user);
        groupService.addMember(context, administrators, featureGroup);
        groupService.update(context, featureGroup);
        groupService.update(context, administrators);
    }

    private void removeFullControlGroups(Context context, EPerson user) throws SQLException, AuthorizeException {
        for (Group featureGroup : fullControlGroups(context, user)) {
            try {
                groupService.delete(context, featureGroup);
            } catch (IOException e) {
                throw new SQLException("Unable to remove feature-owned full-control group", e);
            }
        }
    }

    private List<Group> fullControlGroups(Context context, EPerson user) throws SQLException {
        String userPrefix = FULL_CONTROL_GROUP_PREFIX + user.getID() + ":";
        return groupService.search(context, userPrefix).stream()
            .filter(group -> StringUtils.startsWith(group.getName(), userPrefix))
            .filter(group -> groupService.isDirectMember(group, user))
            .toList();
    }

    private boolean isFullControlGroup(Group group) {
        return StringUtils.startsWith(group.getName(), FULL_CONTROL_GROUP_PREFIX);
    }

    private String fullControlGroupName(EPerson user, DSpaceObject dso) {
        return FULL_CONTROL_GROUP_PREFIX + user.getID() + ":" + resourceType(dso) + ":" + dso.getID();
    }

    private DSpaceObject fullControlScope(Context context, Group group) throws SQLException {
        String[] parts = StringUtils.splitPreserveAllTokens(group.getName(), ':');
        if (parts.length != 5) {
            return null;
        }
        try {
            return findScope(context, parts[3], UUID.fromString(parts[4]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<PermissionAssignment> toAssignments(Map<String, MutableAssignment> assignments) {
        return assignments.values().stream().map(MutableAssignment::toResponse)
            .sorted(Comparator.comparing(PermissionAssignment::resourcePath, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PermissionAssignment::source, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private void addPolicyAssignment(Context context, Map<String, MutableAssignment> assignments, ResourcePolicy policy,
                                     String source, boolean editable) {
        if (DELETE_REMOVE_POLICY_NAME.equals(policy.getRpName())) {
            return;
        }
        DSpaceObject dso = policy.getdSpaceObject();
        MutableAssignment assignment = assignments.computeIfAbsent(dso.getID() + "\u0000" + source,
            ignored -> new MutableAssignment(context, dso, source, editable));
        if (policy.getAction() == Constants.ADMIN) {
            assignment.fullControl = true;
            assignment.actions.clear();
        } else if (!assignment.fullControl && DELETE_POLICY_NAME.equals(policy.getRpName())) {
            assignment.actions.add("DELETE");
        } else if (!assignment.fullControl) {
            assignment.actions.add(Constants.actionText[policy.getAction()]);
        }
    }

    private DSpaceObject findScope(Context context, String resourceType, UUID resourceId) throws SQLException {
        if (resourceId == null || StringUtils.isBlank(resourceType)) {
            return null;
        }
        DSpaceObject dso = dspaceObjectUtils.findDSpaceObject(context, resourceId);
        if (dso == null || !resourceType.equalsIgnoreCase(resourceType(dso))) {
            return null;
        }
        return switch (dso.getType()) {
            case Constants.COMMUNITY, Constants.COLLECTION, Constants.ITEM -> dso;
            default -> null;
        };
    }

    private Integer actionId(String action) {
        return action == null ? null : ACTIONS.get(action.trim().toUpperCase(Locale.ROOT));
    }

    private boolean matches(DSpaceObject dso, String query) {
        return StringUtils.isBlank(query) || StringUtils.defaultString(dso.getName()).toLowerCase(Locale.ROOT).contains(query)
            || dso.getID().toString().contains(query);
    }

    private boolean hasParent(Context context, DSpaceObject dso, DSpaceObject expectedParent) throws SQLException {
        DSpaceObject actualParent = directParent(context, dso);
        return expectedParent == null ? actualParent == null
            : actualParent != null && expectedParent.getID().equals(actualParent.getID());
    }

    private DSpaceObject directParent(Context context, DSpaceObject dso) throws SQLException {
        return switch (dso.getType()) {
            case Constants.COMMUNITY -> communityService.getParentObject(context, (Community) dso);
            case Constants.COLLECTION -> collectionService.getParentObject(context, (Collection) dso);
            case Constants.ITEM -> itemService.getParentObject(context, (Item) dso);
            default -> null;
        };
    }

    private PermissionScope toScope(Context context, DSpaceObject dso) throws SQLException {
        DSpaceObject parent = directParent(context, dso);
        return new PermissionScope(resourceType(dso), dso.getID(), dso.getName(), path(context, dso),
            parent == null ? null : resourceType(parent), parent == null ? null : parent.getID(),
            parent == null ? null : parent.getName(), parent == null ? null : path(context, parent));
    }

    private PermissionScope toScopeUnchecked(Context context, DSpaceObject dso) {
        try {
            return toScope(context, dso);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to resolve scope", e);
        }
    }

    private String resourceType(DSpaceObject dso) {
        return switch (dso.getType()) {
            case Constants.COMMUNITY -> "COMMUNITY";
            case Constants.COLLECTION -> "COLLECTION";
            case Constants.ITEM -> "ITEM";
            default -> "UNKNOWN";
        };
    }

    private boolean isImplicitGroup(Group group) {
        return Group.ANONYMOUS.equals(group.getName()) || "Authenticated".equals(group.getName());
    }

    private String path(Context context, DSpaceObject dso) throws SQLException {
        if (dso instanceof Community community) {
            DSpaceObject parent = communityService.getParentObject(context, community);
            return parent == null ? community.getName() : path(context, parent) + " / " + community.getName();
        }
        if (dso instanceof Collection collection) {
            DSpaceObject parent = collectionService.getParentObject(context, collection);
            return parent == null ? collection.getName() : path(context, parent) + " / " + collection.getName();
        }
        if (dso instanceof Item item) {
            DSpaceObject parent = itemService.getParentObject(context, item);
            return parent == null ? item.getName() : path(context, parent) + " / " + item.getName();
        }
        return dso.getName();
    }

    private final class MutableAssignment {
        private final Context context;
        private final DSpaceObject dso;
        private final String source;
        private final boolean editable;
        private boolean fullControl;
        private final Set<String> actions = new HashSet<>();

        private MutableAssignment(Context context, DSpaceObject dso, String source, boolean editable) {
            this.context = context;
            this.dso = dso;
            this.source = source;
            this.editable = editable;
        }

        private PermissionAssignment toResponse() {
            try {
                List<String> sortedActions = actions.stream().sorted().toList();
                DSpaceObject parent = directParent(context, dso);
                return new PermissionAssignment(resourceType(dso), dso.getID(), dso.getName(), path(context, dso),
                    fullControl, sortedActions, source, editable,
                    parent == null ? null : resourceType(parent), parent == null ? null : parent.getID(),
                    parent == null ? null : parent.getName(), parent == null ? null : path(context, parent));
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to resolve scope path", e);
            }
        }
    }

    public record PermissionAssignment(String resourceType, UUID resourceId, String resourceName, String resourcePath,
                                       Boolean fullControl, List<String> actions, String source, boolean editable,
                                       String parentResourceType, UUID parentResourceId, String parentResourceName,
                                       String parentResourcePath) { }
    public record PermissionScope(String resourceType, UUID resourceId, String resourceName, String resourcePath,
                                  String parentResourceType, UUID parentResourceId, String parentResourceName,
                                  String parentResourcePath) { }
    public record PermissionScopePage(List<PermissionScope> scopes, int page, int size, int totalElements) { }
    public record ValidationIssue(String field, String message) { }
}
