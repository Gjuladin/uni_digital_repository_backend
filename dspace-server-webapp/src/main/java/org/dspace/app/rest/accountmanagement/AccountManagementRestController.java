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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.PasswordNotValidException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.ValidatePasswordService;
import org.dspace.content.Collection;
import org.dspace.content.DSpaceObject;
import org.dspace.content.service.CollectionService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.discovery.SearchServiceException;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site-administrator-only account provisioning API.  It deliberately uses
 * DSpace EPerson and Group services, so resource policies and nested group
 * memberships remain the authoritative authorization model.
 */
@RestController
@RequestMapping("/api/eperson/account-management")
public class AccountManagementRestController {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,64}$");
    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private EPersonService ePersonService;

    @Autowired
    private GroupService groupService;

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private ValidatePasswordService validatePasswordService;

    @Autowired
    private ContentManagerPermissionService contentManagerPermissionService;

    @Autowired
    @Qualifier("sessionFactory")
    private SessionFactory sessionFactory;

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('ADMIN')")
    public UserPage getUsers(HttpServletRequest request,
                             @RequestParam(required = false) String query,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) throws SQLException {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new DSpaceBadRequestException("page must be non-negative and size must be between 1 and 100");
        }
        Context context = ContextUtil.obtainContext(request);
        int offset = Math.multiplyExact(page, size);
        List<EPerson> people = ePersonService.search(context, StringUtils.defaultString(query), offset, size);
        int total = ePersonService.searchResultCount(context, StringUtils.defaultString(query));

        List<UserResponse> result = new ArrayList<>();
        for (EPerson person : people) {
            result.add(toUserResponse(context, person));
        }
        return new UserPage(result, page, size, total);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public UserResponse getUser(HttpServletRequest request, @PathVariable UUID id) throws SQLException {
        Context context = ContextUtil.obtainContext(request);
        return toUserResponse(context, requireUser(context, id));
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<?> createUsers(HttpServletRequest request, @RequestBody CreateUsersRequest body)
        throws SQLException, AuthorizeException {
        Context context = ContextUtil.obtainContext(request);
        List<ValidationError> errors = validateCreate(context, body);
        if (!errors.isEmpty()) {
            return validationFailure(errors);
        }

        List<Group> roles = resolveRoles(context, body.roleIds());
        Group administrator = administratorGroup(context);
        boolean requirePasswordChange = !Boolean.FALSE.equals(body.requirePasswordChange());
        try {
            List<UserResponse> created = new ArrayList<>();
            for (UserInput input : body.users()) {
                EPerson person = ePersonService.create(context);
                applyUserFields(context, person, input, true);
                person.setCanLogIn(input.canLogIn() == null || input.canLogIn());
                ePersonService.setPassword(person, body.password());
                person.setPasswordChangeRequired(requirePasswordChange);
                ePersonService.update(context, person);
                addRoles(context, person, roles, administrator, Boolean.TRUE.equals(body.repositoryAdministrator()));
                contentManagerPermissionService.apply(context, person, body.contentManager(), body.permissionAssignments());
                created.add(toUserResponse(context, person));
            }
            context.complete();
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (SQLException | AuthorizeException e) {
            context.abort();
            throw e;
        } catch (RuntimeException e) {
            context.abort();
            throw e;
        }
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<?> updateUser(HttpServletRequest request, @PathVariable UUID id,
                                        @RequestBody UpdateUserRequest body)
        throws SQLException, AuthorizeException {
        Context context = ContextUtil.obtainContext(request);
        if (body == null) {
            return validationFailure(List.of(new ValidationError(null, "body", "Request body is required")));
        }
        EPerson person = requireUser(context, id);
        List<ValidationError> errors = validateUser(context, new UserInput(body.username(), body.firstName(),
            body.lastName(), body.email(), body.canLogIn()), -1, person.getID());
        validateRoles(context, body.roleIds(), errors);
        addPermissionValidation(errors, contentManagerPermissionService.validate(context, body.permissionAssignments()));
        if (!errors.isEmpty()) {
            return validationFailure(errors);
        }
        List<Group> roles = resolveRoles(context, body.roleIds());
        Group administrator = administratorGroup(context);
        boolean currentUserIsTarget = context.getCurrentUser() != null && context.getCurrentUser().getID().equals(id);
        if (currentUserIsTarget && Boolean.FALSE.equals(body.repositoryAdministrator())
            && groupService.isDirectMember(administrator, person)) {
            return validationFailure(List.of(new ValidationError(null, "repositoryAdministrator",
                "You cannot remove your own repository administrator membership")));
        }
        try {
            applyUserFields(context, person, new UserInput(body.username(), body.firstName(), body.lastName(),
                body.email(), body.canLogIn()), false);
            if (Boolean.FALSE.equals(body.canLogIn())) {
                // Disabling an account must also invalidate tokens issued while
                // it was enabled; JWT validation compares this session salt.
                person.setSessionSalt("");
            }
            ePersonService.update(context, person);
            replaceRoles(context, person, roles, administrator, Boolean.TRUE.equals(body.repositoryAdministrator()));
            contentManagerPermissionService.apply(context, person, body.contentManager(), body.permissionAssignments());
            UserResponse response = toUserResponse(context, person);
            completeAndEvictEPerson(context, person);
            return ResponseEntity.ok(response);
        } catch (SQLException | AuthorizeException e) {
            context.abort();
            throw e;
        } catch (RuntimeException e) {
            context.abort();
            throw e;
        }
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<Void> deleteUser(HttpServletRequest request, @PathVariable UUID id)
        throws SQLException, AuthorizeException {
        Context context = ContextUtil.obtainContext(request);
        EPerson person = requireUser(context, id);
        if (context.getCurrentUser() != null && context.getCurrentUser().getID().equals(id)) {
            return ResponseEntity.unprocessableEntity().build();
        }
        try {
            // Remove feature-owned policies and nested full-control groups before
            // deleting the EPerson, so those account-management artifacts do not
            // remain after the user has gone.
            contentManagerPermissionService.apply(context, person, false, List.of());
            ePersonService.delete(context, person);
            context.complete();
            sessionFactory.getCache().evictEntityData(EPerson.class, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            context.abort();
            throw new UnprocessableEntityException(e.getMessage(), e);
        } catch (SQLException | AuthorizeException e) {
            context.abort();
            throw e;
        } catch (IOException e) {
            context.abort();
            throw new RuntimeException("Unable to delete user", e);
        } catch (RuntimeException e) {
            context.abort();
            throw e;
        }
    }

    @PostMapping("/users/{id}/password")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<Void> resetPassword(HttpServletRequest request, @PathVariable UUID id,
                                               @RequestBody PasswordResetRequest body)
        throws SQLException, AuthorizeException {
        Context context = ContextUtil.obtainContext(request);
        if (body == null) {
            return ResponseEntity.unprocessableEntity().build();
        }
        validatePassword(body.password());
        EPerson person = requireUser(context, id);
        try {
            ePersonService.setPassword(person, body.password());
            person.setPasswordChangeRequired(!Boolean.FALSE.equals(body.requirePasswordChange()));
            // JWT/session tokens contain this value; changing it invalidates them.
            person.setSessionSalt("");
            ePersonService.update(context, person);
            completeAndEvictEPerson(context, person);
            return ResponseEntity.noContent().build();
        } catch (SQLException | AuthorizeException e) {
            context.abort();
            throw e;
        } catch (RuntimeException e) {
            context.abort();
            throw e;
        }
    }

    @PostMapping("/me/password")
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public ResponseEntity<Void> changeOwnPassword(HttpServletRequest request, @RequestBody OwnPasswordRequest body)
        throws SQLException, AuthorizeException {
        Context context = ContextUtil.obtainContext(request);
        if (body == null) {
            return ResponseEntity.unprocessableEntity().build();
        }
        EPerson person = context.getCurrentUser();
        if (person == null) {
            return ResponseEntity.unprocessableEntity().build();
        }
        if (!ePersonService.checkPassword(context, person, body.currentPassword())) {
            // A browser may safely retry after the password mutation committed
            // but its follow-up identity refresh failed. Treat that exact retry
            // as successful instead of reporting that the old temporary
            // password is wrong; no additional state is changed here.
            if (!person.isPasswordChangeRequired()
                && ePersonService.checkPassword(context, person, body.newPassword())) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.unprocessableEntity().build();
        }
        validatePassword(body.newPassword());
        try {
            ePersonService.setPassword(person, body.newPassword());
            person.setPasswordChangeRequired(false);
            ePersonService.update(context, person);
            completeAndEvictEPerson(context, person);
            return ResponseEntity.noContent().build();
        } catch (SQLException | AuthorizeException e) {
            context.abort();
            throw e;
        } catch (RuntimeException e) {
            context.abort();
            throw e;
        }
    }

    /**
     * Commit an account mutation and immediately evict its non-strict Hibernate
     * cache entry. Without the explicit eviction, the next authenticated
     * request can briefly reload the pre-commit password-change flag and the
     * forced-change filter incorrectly rejects agreement acceptance.
     */
    private void completeAndEvictEPerson(Context context, EPerson person) throws SQLException {
        UUID personId = person.getID();
        context.complete();
        sessionFactory.getCache().evictEntityData(EPerson.class, personId);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ADMIN')")
    public List<RoleResponse> getRoles(HttpServletRequest request,
                                       @RequestParam(required = false) String query) throws SQLException {
        Context context = ContextUtil.obtainContext(request);
        List<Group> groups = StringUtils.isBlank(query)
            ? groupService.findAll(context, GroupService.NAME)
            : groupService.search(context, query);
        List<RoleResponse> roles = new ArrayList<>();
        for (Group group : groups) {
            if (!isImplicitOrAdministrator(group)) {
                roles.add(toRoleResponse(context, group));
            }
        }
        roles.sort(Comparator.comparing(RoleResponse::category).thenComparing(RoleResponse::label,
            String.CASE_INSENSITIVE_ORDER));
        return roles;
    }

    @GetMapping("/permission-scopes")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ContentManagerPermissionService.PermissionScopePage permissionScopes(HttpServletRequest request,
        @RequestParam(required = false) String query, @RequestParam(required = false) String resourceType,
        @RequestParam(required = false) UUID parent, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size) throws SQLException, SearchServiceException {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new DSpaceBadRequestException("page must be non-negative and size must be between 1 and 100");
        }
        return contentManagerPermissionService.searchScopes(ContextUtil.obtainContext(request), query, resourceType,
            parent, page, size);
    }

    private List<ValidationError> validateCreate(Context context, CreateUsersRequest body) throws SQLException {
        List<ValidationError> errors = new ArrayList<>();
        if (body == null || body.users() == null || body.users().isEmpty()) {
            errors.add(new ValidationError(null, "users", "At least one user is required"));
            return errors;
        }
        validatePasswordCollect(body.password(), errors);
        Set<String> usernames = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (int index = 0; index < body.users().size(); index++) {
            UserInput input = body.users().get(index);
            errors.addAll(validateUser(context, input, index, null));
            if (input != null && StringUtils.isNotBlank(input.username())) {
                String username = input.username().trim().toLowerCase(Locale.ROOT);
                if (!usernames.add(username)) {
                    errors.add(new ValidationError(index, "username", "Username occurs more than once in this batch"));
                }
            }
            if (input != null && StringUtils.isNotBlank(input.email())) {
                String email = input.email().trim().toLowerCase(Locale.ROOT);
                if (!emails.add(email)) {
                    errors.add(new ValidationError(index, "email", "Email occurs more than once in this batch"));
                }
            }
        }
        validateRoles(context, body.roleIds(), errors);
        addPermissionValidation(errors, contentManagerPermissionService.validate(context, body.permissionAssignments()));
        return errors;
    }

    private List<ValidationError> validateUser(Context context, UserInput input, Integer index, UUID currentId)
        throws SQLException {
        List<ValidationError> errors = new ArrayList<>();
        if (input == null) {
            errors.add(new ValidationError(index, "user", "User is required"));
            return errors;
        }
        String username = StringUtils.trimToNull(input.username());
        if (username == null || !USERNAME.matcher(username.toLowerCase(Locale.ROOT)).matches()) {
            errors.add(new ValidationError(index, "username", "Use 3-64 lowercase letters, numbers, ., _, or -"));
        } else {
            EPerson existing = ePersonService.findByUsername(context, username.toLowerCase(Locale.ROOT));
            if (existing != null && !existing.getID().equals(currentId)) {
                errors.add(new ValidationError(index, "username", "Username is already in use"));
            }
        }
        if (StringUtils.isBlank(input.firstName())) {
            errors.add(new ValidationError(index, "firstName", "First name is required"));
        }
        if (StringUtils.isBlank(input.lastName())) {
            errors.add(new ValidationError(index, "lastName", "Last name is required"));
        }
        String email = StringUtils.trimToNull(input.email());
        if (email != null) {
            if (!EmailValidator.getInstance(false, false).isValid(email)) {
                errors.add(new ValidationError(index, "email", "Email is not valid"));
            } else {
                EPerson existing = ePersonService.findByEmail(context, email);
                if (existing != null && !existing.getID().equals(currentId)) {
                    errors.add(new ValidationError(index, "email", "Email is already in use"));
                }
            }
        }
        return errors;
    }

    private void validateRoles(Context context, List<UUID> ids, List<ValidationError> errors) throws SQLException {
        if (ids == null) {
            return;
        }
        for (UUID id : ids) {
            Group group = id == null ? null : groupService.find(context, id);
            if (group == null || isImplicitOrAdministrator(group)) {
                errors.add(new ValidationError(null, "roleIds", "One or more roles are not selectable"));
                return;
            }
        }
    }

    private void addPermissionValidation(List<ValidationError> errors,
                                         List<ContentManagerPermissionService.ValidationIssue> issues) {
        for (ContentManagerPermissionService.ValidationIssue issue : issues) {
            errors.add(new ValidationError(null, issue.field(), issue.message()));
        }
    }

    private List<Group> resolveRoles(Context context, List<UUID> ids) throws SQLException {
        List<Group> roles = new ArrayList<>();
        if (ids == null) {
            return roles;
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID id : ids) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            Group group = groupService.find(context, id);
            if (group == null || isImplicitOrAdministrator(group)) {
                throw new DSpaceBadRequestException("One or more roles are not selectable");
            }
            roles.add(group);
        }
        return roles;
    }

    private void applyUserFields(Context context, EPerson person, UserInput input, boolean creating) throws SQLException {
        person.setUsername(input.username().trim().toLowerCase(Locale.ROOT));
        person.setFirstName(context, input.firstName().trim());
        person.setLastName(context, input.lastName().trim());
        person.setEmail(StringUtils.trimToNull(input.email()));
        if (!creating && input.canLogIn() != null) {
            person.setCanLogIn(input.canLogIn());
        }
    }

    private void addRoles(Context context, EPerson person, List<Group> roles, Group administrator, boolean repositoryAdmin) {
        for (Group role : roles) {
            groupService.addMember(context, role, person);
        }
        if (repositoryAdmin) {
            groupService.addMember(context, administrator, person);
        }
    }

    private void replaceRoles(Context context, EPerson person, List<Group> desired, Group administrator,
                              boolean repositoryAdmin) throws SQLException {
        Set<UUID> desiredIds = new HashSet<>();
        for (Group group : desired) {
            desiredIds.add(group.getID());
        }
        // getGroups() is the direct Hibernate relationship; allMemberGroups()
        // would include inherited memberships and must never be edited here.
        List<Group> directGroups = new ArrayList<>(person.getGroups());
        for (Group group : directGroups) {
            if (group.getID().equals(administrator.getID())) {
                if (!repositoryAdmin) {
                    groupService.removeMember(context, group, person);
                }
            } else if (!isImplicitOrAdministrator(group) && !desiredIds.contains(group.getID())) {
                groupService.removeMember(context, group, person);
            }
        }
        addRoles(context, person, desired, administrator, repositoryAdmin);
    }

    private UserResponse toUserResponse(Context context, EPerson person) throws SQLException {
        Group administrator = administratorGroup(context);
        List<RoleResponse> direct = new ArrayList<>();
        for (Group group : person.getGroups()) {
            if (!isImplicitOrAdministrator(group)) {
                direct.add(toRoleResponse(context, group));
            }
        }
        List<RoleResponse> inherited = new ArrayList<>();
        for (Group group : groupService.allMemberGroups(context, person)) {
            if (!isImplicitOrAdministrator(group) && !groupService.isDirectMember(group, person)) {
                inherited.add(toRoleResponse(context, group));
            }
        }
        List<UUID> roleIds = direct.stream().map(RoleResponse::id).toList();
        boolean contentManager = contentManagerPermissionService.isContentManager(context, person);
        List<ContentManagerPermissionService.PermissionAssignment> permissionAssignments =
            contentManagerPermissionService.assignments(context, person);
        List<ContentManagerPermissionService.PermissionAssignment> inheritedPermissionAssignments =
            contentManagerPermissionService.inheritedAssignments(context, person);
        List<ContentManagerPermissionService.PermissionAssignment> unmanagedPermissionAssignments =
            contentManagerPermissionService.unmanagedAssignments(context, person);
        return new UserResponse(person.getID(), person.getUsername(), person.getFirstName(), person.getLastName(),
            person.getEmail(), person.canLogIn(), person.isPasswordChangeRequired(),
            groupService.isDirectMember(administrator, person), contentManager, permissionAssignments,
            inheritedPermissionAssignments, unmanagedPermissionAssignments,
            roleIds, direct, inherited);
    }

    private RoleResponse toRoleResponse(Context context, Group group) throws SQLException {
        String name = group.getName();
        String category = "custom";
        String roleType = "member";
        Map<String, String> scope = new LinkedHashMap<>();
        DSpaceObject parent = groupService.getParentObject(context, group);
        Collection collection = collectionService.findByGroup(context, group);
        if (parent != null && parent.getType() == Constants.COMMUNITY) {
            category = "community";
            roleType = roleName(name);
            addScope(scope, "community", parent);
        } else if (parent != null && parent.getType() == Constants.COLLECTION) {
            category = name.contains("WORKFLOW") ? "workflow" : "collection";
            roleType = roleName(name);
            addScope(scope, "collection", parent);
        } else if (collection != null) {
            category = name.contains("WORKFLOW") ? "workflow" : "collection";
            roleType = roleName(name);
            addScope(scope, "collection", collection);
        } else if (name != null && name.startsWith("COMMUNITY_")) {
            category = "community";
            roleType = roleName(name);
        } else if (name != null && (name.startsWith("COLLECTION_") || name.contains("WORKFLOW"))) {
            category = name.contains("WORKFLOW") ? "workflow" : "collection";
            roleType = roleName(name);
        } else if (name != null && name.startsWith("SITE_")) {
            category = "site-wide";
            roleType = roleName(name);
        }
        return new RoleResponse(group.getID(), friendlyLabel(name), category, roleType, scope);
    }

    private void addScope(Map<String, String> scope, String type, DSpaceObject object) {
        scope.put("type", type);
        scope.put("id", object.getID().toString());
        scope.put("name", object.getName());
    }

    private String roleName(String name) {
        if (StringUtils.isBlank(name)) {
            return "member";
        }
        int underscore = name.lastIndexOf('_');
        return underscore < 0 ? name.toLowerCase(Locale.ROOT) : name.substring(underscore + 1).toLowerCase(Locale.ROOT);
    }

    private String friendlyLabel(String name) {
        return StringUtils.defaultIfBlank(name, "Unnamed group").replace('_', ' ');
    }

    private boolean isImplicitOrAdministrator(Group group) {
        String name = group.getName();
        return Group.ANONYMOUS.equals(name) || "Authenticated".equals(name) || Group.ADMIN.equals(name)
            || Group.CONTENT_MANAGER.equals(name)
            || StringUtils.startsWith(name, ContentManagerPermissionService.FULL_CONTROL_GROUP_PREFIX);
    }

    private Group administratorGroup(Context context) throws SQLException {
        Group group = groupService.findByName(context, Group.ADMIN);
        if (group == null) {
            throw new IllegalStateException("Repository Administrator group is missing");
        }
        return group;
    }

    private EPerson requireUser(Context context, UUID id) throws SQLException {
        EPerson person = ePersonService.find(context, id);
        if (person == null) {
            throw new ResourceNotFoundException("No user exists for UUID: " + id);
        }
        return person;
    }

    private void validatePassword(String password) {
        if (StringUtils.isBlank(password) || !validatePasswordService.isPasswordValid(password)) {
            throw new PasswordNotValidException();
        }
    }

    private void validatePasswordCollect(String password, List<ValidationError> errors) {
        if (StringUtils.isBlank(password) || !validatePasswordService.isPasswordValid(password)) {
            errors.add(new ValidationError(null, "password", "Password does not satisfy the configured policy"));
        }
    }

    private ResponseEntity<ValidationErrors> validationFailure(List<ValidationError> errors) {
        return ResponseEntity.unprocessableEntity().body(new ValidationErrors(errors));
    }

    public record UserInput(String username, String firstName, String lastName, String email, Boolean canLogIn) { }
    public record CreateUsersRequest(List<UserInput> users, String password, Boolean requirePasswordChange,
                                     List<UUID> roleIds,
                                     Boolean repositoryAdministrator, Boolean contentManager,
                                     List<ContentManagerPermissionService.PermissionAssignment> permissionAssignments) { }
    public record UpdateUserRequest(String username, String firstName, String lastName, String email, Boolean canLogIn,
                                    List<UUID> roleIds, Boolean repositoryAdministrator, Boolean contentManager,
                                    List<ContentManagerPermissionService.PermissionAssignment> permissionAssignments) { }
    public record PasswordResetRequest(String password, Boolean requirePasswordChange) { }
    public record OwnPasswordRequest(String currentPassword, String newPassword) { }
    public record ValidationError(Integer index, String field, String message) { }
    public record ValidationErrors(List<ValidationError> errors) { }
    public record RoleResponse(UUID id, String label, String category, String roleType, Map<String, String> scope) { }
    public record UserResponse(UUID id, String username, String firstName, String lastName, String email,
                               boolean canLogIn, boolean passwordChangeRequired, boolean repositoryAdministrator,
                               boolean contentManager,
                               List<ContentManagerPermissionService.PermissionAssignment> permissionAssignments,
                               List<ContentManagerPermissionService.PermissionAssignment> inheritedPermissionAssignments,
                               List<ContentManagerPermissionService.PermissionAssignment> unmanagedPermissionAssignments,
                               List<UUID> roleIds,
                               List<RoleResponse> directRoles, List<RoleResponse> inheritedRoles) { }
    public record UserPage(List<UserResponse> users, int page, int size, int totalElements) { }
}
