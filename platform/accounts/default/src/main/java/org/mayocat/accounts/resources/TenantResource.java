package org.mayocat.accounts.resources;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.mayocat.accounts.AccountsService;
import org.mayocat.accounts.meta.TenantEntity;
import org.mayocat.accounts.model.Role;
import org.mayocat.accounts.model.Tenant;
import org.mayocat.accounts.model.User;
import org.mayocat.accounts.representations.TenantRepresentation;
import org.mayocat.authorization.Gatekeeper;
import org.mayocat.authorization.annotation.Authorized;
import org.mayocat.configuration.MultitenancySettings;
import org.mayocat.context.Context;
import org.mayocat.context.Execution;
import org.mayocat.rest.Resource;
import org.mayocat.rest.representations.ResultSetRepresentation;
import org.mayocat.store.EntityAlreadyExistsException;
import org.mayocat.store.EntityDoesNotExistException;
import org.mayocat.store.InvalidEntityException;
import org.xwiki.component.annotation.Component;

@Component(TenantResource.PATH)
@Path(TenantResource.PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantResource implements Resource
{
    public static final String PATH = API_ROOT_PATH + TenantEntity.PATH;

    @Inject
    private Execution execution;

    @Inject
    private AccountsService accountsService;

    @Inject
    private Gatekeeper gatekeeper;

    @Inject
    private MultitenancySettings multitenancySettings;

    @GET
    @Path("{slug}")
    @Authorized(roles = Role.ADMIN, requiresGlobalUser = true)
    public TenantRepresentation getTenant(@PathParam("slug") String slug)
    {
        Tenant tenant = accountsService.findTenant(slug);
        if (tenant == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        return new TenantRepresentation(tenant);
    }

    @GET
    @Authorized(roles = Role.ADMIN, requiresGlobalUser = true)
    public ResultSetRepresentation<TenantRepresentation> getAllTenants(@QueryParam("number") @DefaultValue("50") Integer number,
            @DefaultValue("0") @QueryParam("offset") Integer offset)
    {
        List<Tenant> tenants = accountsService.findAllTenants(number, offset);
        if (tenants == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        List<TenantRepresentation> representations = new ArrayList<TenantRepresentation>();
        for (Tenant tenant : tenants) {
            representations.add(new TenantRepresentation(tenant));
        }

        ResultSetRepresentation<TenantRepresentation> result = new ResultSetRepresentation<TenantRepresentation>(
                PATH + "/",
                number,
                offset,
                representations
        );

        return result;
    }

    @GET
    @Path("_current")
    @Authorized
    public UserAndTenant currentTenant()
    {
        UserAndTenant userAndTenant = new UserAndTenant();
        userAndTenant.setTenant(execution.getContext().getTenant());
        userAndTenant.setUser(execution.getContext().getUser());
        return userAndTenant;
    }

    @PUT
    @Authorized
    public Response updateTenant(Tenant updatedTenant)
    {
        try {
            Context context = execution.getContext();
            if (context.getTenant() == null) {
                // Should not happen
                return Response.status(404).build();
            } else {
                updatedTenant.setSlug(context.getTenant().getSlug());
                this.accountsService.updateTenant(updatedTenant);
            }

            return Response.ok().build();
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        } catch (EntityDoesNotExistException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Tenant not found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        }
    }

    @POST
    public Response createTenant(UserAndTenant userAndTenant)
    {
        if (!multitenancySettings.isActivated()) {
            return Response.status(Response.Status.FORBIDDEN).entity("Tenant creation is not allowed on this server\n")
                    .build();
        }

        if (!isTenantCreationAllowed()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        try {
            Tenant tenant = userAndTenant.getTenant();
            accountsService.createTenant(tenant);
            execution.getContext().setTenant(tenant);
            accountsService.createInitialUser(userAndTenant.getUser());
            return Response.ok().build();
        } catch (EntityAlreadyExistsException e) {
            return Response.status(Response.Status.CONFLICT).entity("A tenant with this slug already exists").build();
        } catch (InvalidEntityException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    }

    private boolean isTenantCreationAllowed()
    {
        if (multitenancySettings.getRequiredRoleForTenantCreation() != Role.NONE) {

            User contextUser = execution.getContext().getUser();
            if (contextUser == null || !contextUser.isGlobal()) {
                return false;
            }

            return gatekeeper.userHasRole(execution.getContext().getUser(),
                    multitenancySettings.getRequiredRoleForTenantCreation());
        } else {
            return true;
        }
    }

    static class UserAndTenant
    {
        @NotNull
        @Valid
        private User user;

        @NotNull
        @Valid
        private Tenant tenant;

        public UserAndTenant()
        {
        }

        public Tenant getTenant()
        {
            return tenant;
        }

        public void setTenant(Tenant tenant)
        {
            this.tenant = tenant;
        }

        public User getUser()
        {
            return user;
        }

        public void setUser(User user)
        {
            this.user = user;
        }
    }
}