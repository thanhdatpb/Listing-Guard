package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties.EditScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Who may do what in the workspace (ADR-059). Every MEMBER of a client reads the whole workspace of the client and changes only the
 * listings they are responsible for (with {@code member-edit-scope=all}: every listing); an ADMIN does everything, and only an ADMIN
 * manages accounts and templates, changes the responsible person and deletes. The role is read from the database at each request
 * ({@link ConsoleAccountRoles}), never from the session, so a change of role takes effect at once. An account that no longer belongs to the
 * client of its session has no access at all.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class WorkspaceAccess {

    /** The signed-in console account in the capacity it acts in. */
    public record Actor(UUID accountId, UUID clientId, String email, boolean admin) {
    }

    private final ConsoleAccountRoles roles;
    private final WorkspaceProperties properties;

    public WorkspaceAccess(ConsoleAccountRoles roles, WorkspaceProperties properties) {
        this.roles = roles;
        this.properties = properties;
    }

    public Optional<Actor> actor(ConsoleUser user) {
        return roles.account(user).map(WorkspaceAccess::actorOf);
    }

    private static Actor actorOf(ConsoleAccount account) {
        return new Actor(account.getId(), account.getClientId(), account.getEmail(), account.getRole() == ConsoleRole.ADMIN);
    }

    /** Whether the actor may change a listing whose responsible person is {@code owner} (null: nobody). */
    public boolean canEdit(Actor actor, UUID owner) {
        if (actor.admin() || properties.memberEditScope() == EditScope.ALL) {
            return true;
        }
        return owner != null && owner.equals(actor.accountId());
    }

    /** Accounts, templates, the responsible person and deletion are ADMIN's. */
    public boolean canManage(Actor actor) {
        return actor.admin();
    }
}
