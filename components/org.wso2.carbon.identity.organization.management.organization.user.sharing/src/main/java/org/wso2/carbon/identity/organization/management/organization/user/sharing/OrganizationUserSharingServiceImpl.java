/*
 * Copyright (c) 2023-2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.organization.management.organization.user.sharing;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.annotation.bundle.Capability;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.core.model.ExpressionNode;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.services.IdentityEventService;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.EditOperation;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.SharedType;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.dao.OrganizationUserSharingDAO;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.dao.OrganizationUserSharingDAOImpl;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.exception.UserSharingMgtException;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.internal.OrganizationUserSharingDataHolder;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.models.UserAssociation;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementServerException;
import org.wso2.carbon.identity.role.v2.mgt.core.exception.IdentityRoleManagementException;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.CLAIM_MANAGED_ORGANIZATION;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.DEFAULT_PROFILE;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.EVENT_POST_SHARE_USER;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.EVENT_POST_UNSHARE_USER;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.EVENT_PROP_ASSOCIATED_ORGANIZATION_ID;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.EVENT_PROP_ASSOCIATED_USER_ID;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.EVENT_PROP_SHARED_ORGANIZATION_ID;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.EVENT_PROP_SHARED_TYPE;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.EVENT_PROP_SHARED_USER_ID;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.ID_CLAIM_READ_ONLY;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.PRIMARY_DOMAIN;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.PROCESS_ADD_SHARED_USER;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.PROCESS_DELETE_SHARED_USER;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_CREATE_SHARED_USER;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_DELETE_SHARED_USER;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.handleServerException;

/**
 * Implementation of the organization user association service.
 */
@Capability(
        namespace = "osgi.service",
        attribute = {
                "objectClass=org.wso2.carbon.identity.organization.management.organization.user.sharing." +
                        "OrganizationUserSharingService",
                "service.scope=singleton"
        }
)
public class OrganizationUserSharingServiceImpl implements OrganizationUserSharingService {

    private static final Log LOG = LogFactory.getLog(OrganizationUserSharingServiceImpl.class);
    private final OrganizationUserSharingDAO organizationUserSharingDAO = new OrganizationUserSharingDAOImpl();

    @Override
    public void shareOrganizationUser(String orgId, String associatedUserId, String associatedOrgId)
            throws OrganizationManagementException {

        shareOrganizationUserWithOrWithoutType(orgId, associatedUserId, associatedOrgId, SharedType.NOT_SPECIFIED);
    }

    @Override
    public void shareOrganizationUser(String orgId, String associatedUserId, String associatedOrgId,
                                      SharedType sharedType) throws OrganizationManagementException {

        if (sharedType == null) {
            shareOrganizationUser(orgId, associatedUserId, associatedOrgId);
        }

        shareOrganizationUserWithOrWithoutType(orgId, associatedUserId, associatedOrgId, sharedType);
    }

    @Override
    public boolean unshareOrganizationUsers(String associatedUserId, String associatedOrgId)
            throws OrganizationManagementException {

        List<UserAssociation> userAssociationList =
                organizationUserSharingDAO.getUserAssociationsOfAssociatedUser(associatedUserId, associatedOrgId);
        // Removing the shared users from the shared organizations.
        for (UserAssociation userAssociation : userAssociationList) {
            removeSharedUser(userAssociation);
        }
        return true;
    }

    @Override
    public boolean unshareOrganizationUserInSharedOrganization(String associatedUserId, String sharedOrgId)
            throws OrganizationManagementException {

        UserAssociation userAssociation =
                organizationUserSharingDAO.getUserAssociationOfAssociatedUserByOrgId(associatedUserId, sharedOrgId);

        // Removing the shared user from the shared organization.
        removeSharedUser(userAssociation);
        return true;
    }

    @Override
    public boolean deleteUserAssociation(String userId, String associatedOrgId) throws OrganizationManagementException {

        return organizationUserSharingDAO.deleteUserAssociationOfUserByAssociatedOrg(userId, associatedOrgId);
    }

    @Override
    public boolean deleteUserAssociationsByOrganizationId(String orgId) throws OrganizationManagementException {

        return organizationUserSharingDAO.deleteUserAssociationsByOrganizationId(orgId);
    }

    @Override
    public UserAssociation getUserAssociationOfAssociatedUserByOrgId(String associatedUserId, String orgId)
            throws OrganizationManagementException {

        return organizationUserSharingDAO.getUserAssociationOfAssociatedUserByOrgId(associatedUserId, orgId);
    }

    @Override
    public UserAssociation getUserAssociation(String sharedUserId, String sharedOrganizationId)
            throws OrganizationManagementException {

        return organizationUserSharingDAO.getUserAssociation(sharedUserId, sharedOrganizationId);
    }

    @Override
    public List<UserAssociation> getUserAssociationsOfGivenUser(String actualUserId, String residentOrgId)
            throws OrganizationManagementException {

        return organizationUserSharingDAO.getUserAssociationsOfAssociatedUser(actualUserId, residentOrgId);
    }

    @Override
    public List<UserAssociation> getUserAssociationsOfGivenUser(String actualUserId, String residentOrgId,
                                                                List<String> orgIdsScope,
                                                                List<ExpressionNode> expressionNodes,
                                                                String sortOrder, int limit)
            throws OrganizationManagementException {

        return organizationUserSharingDAO.getUserAssociationsOfAssociatedUser(actualUserId, residentOrgId,
                orgIdsScope, expressionNodes, sortOrder, limit);
    }

    @Override
    public List<UserAssociation> getUserAssociationsOfGivenUser(String actualUserId, String residentOrgId,
                                                                SharedType sharedType)
            throws OrganizationManagementException {

        return organizationUserSharingDAO.getUserAssociationsOfAssociatedUser(actualUserId, residentOrgId, sharedType);
    }

    @Override
    public boolean hasUserAssociations(String associatedUserId, String associatedOrgId)
            throws OrganizationManagementServerException {

        return organizationUserSharingDAO.hasUserAssociations(associatedUserId, associatedOrgId);
    }

    @Override
    public boolean hasUserAssociationsInOrganizations(String associatedUserId, String associatedOrgId,
                                                 List<String> orgIds)
            throws OrganizationManagementServerException {

        return organizationUserSharingDAO.hasUserAssociationsInOrganizations(associatedUserId, associatedOrgId, orgIds);
    }

    @Override
    public List<String> getNonDeletableUserRoleAssignments(String roleId,
                                                           List<String> deletedDomainQualifiedUserNamesList,
                                                           String tenantDomain, String requestingOrgId)
            throws IdentityRoleManagementException {

        return organizationUserSharingDAO.getNonDeletableUserRoleAssignments(roleId,
                deletedDomainQualifiedUserNamesList,
                tenantDomain, requestingOrgId);
    }

    @Override
    public List<String> getSharedUserRolesFromUserRoles(List<String> allUserRolesOfSharedUser, String tenantDomain)
            throws IdentityRoleManagementException {

        return organizationUserSharingDAO.getSharedUserRolesFromUserRoles(allUserRolesOfSharedUser, tenantDomain);
    }

    @Override
    public void addEditRestrictionsForSharedUserRole(String roleId, String username, String tenantDomain,
                                                      String domainName, EditOperation editOperation,
                                                      String permittedOrgId)
            throws UserSharingMgtException {

        organizationUserSharingDAO.addEditRestrictionsForSharedUserRole(roleId, username, tenantDomain, domainName,
                editOperation, permittedOrgId);
    }

    @Override
    public List<String> getRolesSharedWithUserInOrganization(String username, int tenantId, String domainName)
            throws UserSharingMgtException {

        return organizationUserSharingDAO.getRolesSharedWithUserInOrganization(username, tenantId, domainName);
    }

    @Override
    public List<UserAssociation> getUserAssociationsOfGivenUserOnGivenOrgs(String associatedUserId, List<String> orgIds)
            throws OrganizationManagementServerException {

        return organizationUserSharingDAO.getUserAssociationsOfGivenUserOnGivenOrgs(associatedUserId, orgIds);
    }

    @Override
    public void updateSharedTypeOfUserAssociation(int id, SharedType sharedType)
            throws OrganizationManagementServerException {

        organizationUserSharingDAO.updateSharedTypeOfUserAssociation(id, sharedType);
    }

    private AbstractUserStoreManager getAbstractUserStoreManager(int tenantId) throws UserStoreException {

        RealmService realmService = OrganizationUserSharingDataHolder.getInstance().getRealmService();
        UserRealm tenantUserRealm = realmService.getTenantUserRealm(tenantId);
        return (AbstractUserStoreManager) tenantUserRealm.getUserStoreManager();
    }

    private OrganizationManager getOrganizationManager() {

        return OrganizationUserSharingDataHolder.getInstance().getOrganizationManager();
    }

    private String generatePassword() {

        UUID uuid = UUID.randomUUID();
        return uuid.toString().substring(0, 12);
    }

    private void removeSharedUser(UserAssociation userAssociation) throws OrganizationManagementException {

        if (userAssociation != null) {
            String userId = userAssociation.getUserId();
            String organizationId = userAssociation.getOrganizationId();
            String tenantDomain = getOrganizationManager().resolveTenantDomain(organizationId);
            int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
            String initiatingTenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
            String userName = null;
            try {
                AbstractUserStoreManager sharedOrgUserStoreManager = getAbstractUserStoreManager(tenantId);
                userName = resolveUserName(sharedOrgUserStoreManager, userId);
                deleteUserInTenantFlow(sharedOrgUserStoreManager, userId, tenantDomain, organizationId);
            } catch (UserStoreException e) {
                throw handleServerException(ERROR_CODE_ERROR_DELETE_SHARED_USER, e, userId, organizationId);
            }
            fireUserSharingEvent(EVENT_POST_UNSHARE_USER, initiatingTenantDomain, userId, organizationId,
                    userAssociation.getAssociatedUserId(), userAssociation.getUserResidentOrganizationId(), userName,
                    userAssociation.getSharedType());
        }
    }

    private void deleteUserInTenantFlow(AbstractUserStoreManager userStoreManager, String userId,
                                        String tenantDomain, String organizationId) throws UserStoreException {

        try {
            String requestInitiator = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
            startTenantFlow(tenantDomain);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setOrganizationId(organizationId);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(requestInitiator);
            // Marks the deletion as the removal of a shared profile, as PROCESS_ADD_SHARED_USER does for its creation.
            IdentityUtil.threadLocalProperties.get().put(PROCESS_DELETE_SHARED_USER, true);
            userStoreManager.deleteUserWithID(userId);
        } finally {
            IdentityUtil.threadLocalProperties.get().remove(PROCESS_DELETE_SHARED_USER);
            endTenantFlow();
        }
    }

    private void startTenantFlow(String tenantDomain) {

        PrivilegedCarbonContext.startTenantFlow();
        PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
    }

    private void endTenantFlow() {

        PrivilegedCarbonContext.endTenantFlow();
    }

    private void shareOrganizationUserWithOrWithoutType(String orgId, String associatedUserId, String associatedOrgId,
                                                        SharedType sharedType) throws OrganizationManagementException {

        String initiatingTenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        String sharedUserId;
        String sharedUserName;
        try {
            String suborgTenantDomain = getOrganizationManager().resolveTenantDomain(orgId);
            startTenantFlow(suborgTenantDomain);
            IdentityUtil.threadLocalProperties.get().put(PROCESS_ADD_SHARED_USER, true);
            int associatedUserTenantId =
                    IdentityTenantUtil.getTenantId(getOrganizationManager().resolveTenantDomain(associatedOrgId));
            AbstractUserStoreManager userStoreManager = getAbstractUserStoreManager(associatedUserTenantId);
            String userName = userStoreManager.getUser(associatedUserId, null).getUsername();

            HashMap<String, String> userClaims = new HashMap<>();
            userClaims.put(CLAIM_MANAGED_ORGANIZATION, associatedOrgId);
            userClaims.put(ID_CLAIM_READ_ONLY, "true");
            UserCoreUtil.setSkipPasswordPatternValidationThreadLocal(true);

            int tenantId = IdentityTenantUtil.getTenantId(suborgTenantDomain);
            String domain = IdentityUtil.getProperty("OrganizationUserInvitation.PrimaryUserDomain");
            userStoreManager = getAbstractUserStoreManager(tenantId);

            if (PRIMARY_DOMAIN.equalsIgnoreCase(domain)) {
                userStoreManager.addUser(userName, generatePassword(), null, userClaims,
                        DEFAULT_PROFILE);
            } else {
                // Wait for the user store manager to be available in the user realm.
                UserStoreManager defaultUserStore = null;
                int threadSleepTime = Integer.parseInt(
                        IdentityUtil.getProperty("OrganizationUserInvitation.AssociationWaitTime"));
                int waited = 0;
                int waitIntervals = 500;
                while (defaultUserStore == null) {
                    if (waited > threadSleepTime) {
                        break;
                    }
                    Thread.sleep(waitIntervals);
                    waited += waitIntervals;
                    defaultUserStore = getAbstractUserStoreManager(tenantId).getSecondaryUserStoreManager(domain);
                }
                if (defaultUserStore == null) {
                    throw new OrganizationManagementException("Error while retrieving user store manager for domain: " +
                            domain);
                }
                defaultUserStore.addUser(userName, generatePassword(), null, userClaims, DEFAULT_PROFILE);
            }
            String userId = userStoreManager.getUserIDFromUserName(UserCoreUtil.addDomainToName(userName, domain));
            sharedUserId = userId;
            sharedUserName = userName;
            if (SharedType.NOT_SPECIFIED.equals(sharedType)) {
                organizationUserSharingDAO.createOrganizationUserAssociation(userId, orgId, associatedUserId,
                        associatedOrgId);
            } else {
                organizationUserSharingDAO.createOrganizationUserAssociation(userId, orgId, associatedUserId,
                        associatedOrgId, sharedType);
            }
        } catch (UserStoreException | InterruptedException e) {
            throw handleServerException(ERROR_CODE_ERROR_CREATE_SHARED_USER, e, orgId);
        } finally {
            IdentityUtil.threadLocalProperties.get().remove(PROCESS_ADD_SHARED_USER);
            endTenantFlow();
        }
        fireUserSharingEvent(EVENT_POST_SHARE_USER, initiatingTenantDomain, sharedUserId, orgId, associatedUserId,
                associatedOrgId, sharedUserName, sharedType);
    }

    private String resolveUserName(AbstractUserStoreManager userStoreManager, String userId) {

        try {
            return userStoreManager.getUser(userId, null).getUsername();
        } catch (UserStoreException e) {
            LOG.debug("Could not resolve the username of shared user: " + userId, e);
            return null;
        }
    }

    /**
     * Fire an event announcing that a user was shared with, or unshared from, an organization. The event is raised
     * in the organization that initiated the operation, so that its webhooks, and those of its ancestors subscribed
     * to it, are notified. A failure to fire the event is logged and does not fail the share.
     */
    private void fireUserSharingEvent(String eventName, String initiatingTenantDomain, String sharedUserId,
                                      String sharedOrgId, String associatedUserId, String associatedOrgId,
                                      String userName, SharedType sharedType) {

        IdentityEventService eventService = OrganizationUserSharingDataHolder.getInstance().getIdentityEventService();
        if (eventService == null) {
            LOG.debug("Identity event service is not available. Skipping the " + eventName + " event.");
            return;
        }
        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put(IdentityEventConstants.EventProperty.TENANT_DOMAIN, initiatingTenantDomain);
        eventProperties.put(EVENT_PROP_SHARED_USER_ID, sharedUserId);
        eventProperties.put(EVENT_PROP_SHARED_ORGANIZATION_ID, sharedOrgId);
        eventProperties.put(EVENT_PROP_ASSOCIATED_USER_ID, associatedUserId);
        eventProperties.put(EVENT_PROP_ASSOCIATED_ORGANIZATION_ID, associatedOrgId);
        if (userName != null) {
            eventProperties.put(IdentityEventConstants.EventProperty.USER_NAME, userName);
        }
        if (sharedType != null) {
            eventProperties.put(EVENT_PROP_SHARED_TYPE, sharedType.name());
        }
        try {
            eventService.handleEvent(new Event(eventName, eventProperties));
        } catch (IdentityEventException | RuntimeException e) {
            LOG.warn("Error while firing the " + eventName + " event for user: " + associatedUserId +
                    " and organization: " + sharedOrgId, e);
        }
    }
}
