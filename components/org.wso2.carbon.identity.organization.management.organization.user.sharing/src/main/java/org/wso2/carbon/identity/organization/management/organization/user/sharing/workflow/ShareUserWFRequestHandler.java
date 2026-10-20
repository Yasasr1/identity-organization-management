/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
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

package org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.UserSharingPolicyHandlerServiceImplV2;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.RoleAssignmentMode;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.exception.UserSharingMgtClientException;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.exception.UserSharingMgtException;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.exception.UserSharingMgtServerException;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.internal.OrganizationUserSharingDataHolder;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.models.dos.RoleAssignmentDO;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.models.dos.RoleWithAudienceDO;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.models.dos.SelectiveUserShareOrgDetailsV2DO;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.util.SharingInitiatorContext;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.resource.sharing.policy.management.constant.PolicyEnum;
import org.wso2.carbon.identity.workflow.mgt.WorkflowExecutorResult;
import org.wso2.carbon.identity.workflow.mgt.WorkflowManagementService;
import org.wso2.carbon.identity.workflow.mgt.bean.Entity;
import org.wso2.carbon.identity.workflow.mgt.exception.WorkflowException;
import org.wso2.carbon.identity.workflow.mgt.extension.AbstractWorkflowRequestHandler;
import org.wso2.carbon.identity.workflow.mgt.util.WorkflowDataType;
import org.wso2.carbon.identity.workflow.mgt.util.WorkflowRequestStatus;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.ErrorMessage.ERROR_CODE_USER_SHARE_APPROVAL_PENDING;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.ErrorMessage.ERROR_CODE_USER_SHARE_WORKFLOW;

/**
 * Workflow request handler that puts sharing a user into an organization on hold until the share is approved.
 * <p>
 * While the request is pending nothing is created in the target organization; the pending workflow request (and its
 * entity lock on the user + organization pair) is the pending record. On approval the share is executed with the
 * (optionally edited) role assignments returned by the approver.
 */
public class ShareUserWFRequestHandler extends AbstractWorkflowRequestHandler {

    public static final String EVENT_ID = "SHARE_USER";
    private static final String ENTITY_TYPE = "SHARED_USER";
    // Entity lock and event of the organization creation approval (AddOrganizationWFRequestHandler).
    private static final String ORGANIZATION_ENTITY_TYPE = "ORGANIZATION";
    private static final String ADD_ORGANIZATION_EVENT_ID = "ADD_ORGANIZATION";
    private static final Log LOG = LogFactory.getLog(ShareUserWFRequestHandler.class);
    private static final Log AUDIT_LOG = CarbonConstants.AUDIT_LOG;

    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
    public static final String RESIDENT_ORG_ID = "residentOrganizationId";
    public static final String RESIDENT_ORG_NAME = "residentOrganizationName";
    public static final String TARGET_ORG_ID = "targetOrganizationId";
    public static final String TARGET_ORG_NAME = "targetOrganizationName";
    public static final String POLICY = "policy";
    public static final String ROLE_ASSIGNMENT_MODE = "roleAssignmentMode";
    public static final String ROLES = "roles";
    public static final String INITIATOR_USER_ID = "initiatorUserId";
    public static final String INITIATOR_USERNAME = "initiatorUsername";

    // Keys accepted in the callback response.
    public static final String RESPONSE_ROLES = "roles";
    public static final String RESPONSE_REASON = "reason";
    public static final String RESPONSE_DECIDED_BY = "decidedBy";

    private static final String ROLE_NAME = "roleName";
    private static final String AUDIENCE_NAME = "audienceName";
    private static final String AUDIENCE_TYPE = "audienceType";

    private static final Map<String, String> PARAM_DEFINITIONS = new LinkedHashMap<>();

    static {
        PARAM_DEFINITIONS.put(USER_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(USERNAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(RESIDENT_ORG_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(RESIDENT_ORG_NAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(TARGET_ORG_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(TARGET_ORG_NAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(POLICY, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(ROLE_ASSIGNMENT_MODE, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(INITIATOR_USER_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(INITIATOR_USERNAME, WorkflowDataType.STRING_TYPE);
    }

    /**
     * Start approval workflows for the user shares that need approval.
     *
     * @param userIds       Users requested to be shared.
     * @param organizations Target organizations of the share.
     * @param initiator     Context of the share initiator.
     * @return {@code null} if no approval workflow applies (share everything immediately), otherwise the
     * organizations each user must be shared with immediately, keyed by user id.
     * @throws UserSharingMgtException If a share is already pending or the workflow could not be engaged.
     */
    public Map<String, List<SelectiveUserShareOrgDetailsV2DO>> engageWorkflows(
            List<String> userIds, List<SelectiveUserShareOrgDetailsV2DO> organizations,
            SharingInitiatorContext initiator) throws UserSharingMgtException {

        if (Boolean.TRUE.equals(getWorkFlowCompleted()) || userIds == null || userIds.isEmpty() ||
                organizations == null || organizations.isEmpty()) {
            return null;
        }
        try {
            if (!isAssociated()) {
                return null;
            }
        } catch (WorkflowException e) {
            throw new UserSharingMgtServerException(ERROR_CODE_USER_SHARE_WORKFLOW.getCode(),
                    ERROR_CODE_USER_SHARE_WORKFLOW.getMessage(), e.getMessage(), e);
        }

        Map<String, List<SelectiveUserShareOrgDetailsV2DO>> immediateShares = new LinkedHashMap<>();
        for (String userId : userIds) {
            List<SelectiveUserShareOrgDetailsV2DO> orgsToShareNow = new ArrayList<>();
            for (SelectiveUserShareOrgDetailsV2DO organization : organizations) {
                /* Only new shares need approval. Role updates of an existing share are left as they are, and so
                   are shares into an organization whose own creation is still pending approval (e.g. the creator
                   being shared into the organization they just onboarded): that approval already covers them. */
                if (isAlreadyShared(userId, organization.getOrganizationId()) ||
                        isOrganizationCreationPending(organization.getOrganizationId()) ||
                        !startShareWorkflow(userId, organization, initiator)) {
                    orgsToShareNow.add(organization);
                }
            }
            immediateShares.put(userId, orgsToShareNow);
        }
        return immediateShares;
    }

    private boolean startShareWorkflow(String userId, SelectiveUserShareOrgDetailsV2DO organization,
                                       SharingInitiatorContext initiator) throws UserSharingMgtException {

        String orgId = organization.getOrganizationId();
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        Entity entity = new Entity(userId + ":" + orgId, ENTITY_TYPE, tenantId);
        try {
            WorkflowManagementService workflowService = getWorkflowManagementService();
            if (workflowService.entityHasPendingWorkflowsOfType(entity, EVENT_ID)) {
                throw new UserSharingMgtClientException(ERROR_CODE_USER_SHARE_APPROVAL_PENDING.getCode(),
                        ERROR_CODE_USER_SHARE_APPROVAL_PENDING.getMessage(),
                        String.format(ERROR_CODE_USER_SHARE_APPROVAL_PENDING.getDescription(), userId, orgId));
            }

            Map<String, Object> wfParams = new HashMap<>();
            wfParams.put(USER_ID, userId);
            wfParams.put(USERNAME, StringUtils.defaultString(resolveUsername(userId, tenantId)));
            wfParams.put(RESIDENT_ORG_ID, initiator.getSharingInitiatedOrgId());
            wfParams.put(RESIDENT_ORG_NAME, StringUtils.defaultString(
                    resolveOrganizationName(initiator.getSharingInitiatedOrgId())));
            wfParams.put(TARGET_ORG_ID, orgId);
            wfParams.put(TARGET_ORG_NAME, StringUtils.defaultString(resolveOrganizationName(orgId)));
            wfParams.put(POLICY, organization.getPolicy().name());
            RoleAssignmentDO roleAssignments = organization.getRoleAssignments();
            wfParams.put(ROLE_ASSIGNMENT_MODE, roleAssignments != null && roleAssignments.getMode() != null ?
                    roleAssignments.getMode().name() : RoleAssignmentMode.NONE.name());
            wfParams.put(ROLES, toRoleMaps(roleAssignments != null ? roleAssignments.getRoles() : null));
            wfParams.put(INITIATOR_USER_ID, StringUtils.defaultString(initiator.getSharingInitiatedUserId()));
            wfParams.put(INITIATOR_USERNAME, StringUtils.defaultString(initiator.getSharingInitiatedUsername()));

            String requestId = UUID.randomUUID().toString();
            WorkflowExecutorResult result = startWorkFlow(wfParams, new HashMap<>(), requestId);
            if (result.getExecutorResultState().state()) {
                // No workflow engaged (e.g. the association condition did not match): share immediately.
                return false;
            }
            workflowService.addRequestEntityRelationships(requestId, new Entity[]{entity});
            AUDIT_LOG.info(String.format("Initiator : %s | Action : User Share Approval Requested | Target : %s | " +
                            "Data : { \"Organization\" : \"%s\", \"RequestId\" : \"%s\" } | Result : Success",
                    initiator.getSharingInitiatedUsername(), userId, orgId, requestId));
            return true;
        } catch (WorkflowException e) {
            throw new UserSharingMgtServerException(ERROR_CODE_USER_SHARE_WORKFLOW.getCode(),
                    ERROR_CODE_USER_SHARE_WORKFLOW.getMessage(),
                    String.format(ERROR_CODE_USER_SHARE_WORKFLOW.getDescription(), userId, orgId), e);
        }
    }

    @Override
    public void onWorkflowCompletion(String status, Map<String, Object> requestParams,
                                     Map<String, Object> responseParams, int tenantId) throws WorkflowException {

        String userId = (String) requestParams.get(USER_ID);
        String targetOrgId = (String) requestParams.get(TARGET_ORG_ID);
        Map<String, Object> response = responseParams != null ? responseParams : Collections.emptyMap();

        if (!WorkflowRequestStatus.APPROVED.toString().equals(status) &&
                !WorkflowRequestStatus.SKIPPED.toString().equals(status)) {
            AUDIT_LOG.info(String.format("Initiator : %s | Action : User Share Rejected | Target : %s | " +
                            "Data : { \"Organization\" : \"%s\", \"Status\" : \"%s\", \"Reason\" : \"%s\" } | " +
                            "Result : Success", response.get(RESPONSE_DECIDED_BY), userId, targetOrgId, status,
                    response.get(RESPONSE_REASON)));
            return;
        }

        List<RoleWithAudienceDO> roles = response.containsKey(RESPONSE_ROLES) ?
                fromRoleMaps(response.get(RESPONSE_ROLES)) : fromRoleMaps(requestParams.get(ROLES));
        RoleAssignmentDO roleAssignment = new RoleAssignmentDO();
        RoleAssignmentMode mode = RoleAssignmentMode.valueOf((String) requestParams.get(ROLE_ASSIGNMENT_MODE));
        if (response.containsKey(RESPONSE_ROLES)) {
            // An approver supplied the roles, so they must be applied explicitly.
            mode = roles.isEmpty() ? RoleAssignmentMode.NONE : RoleAssignmentMode.SELECTED;
        }
        roleAssignment.setMode(mode);
        roleAssignment.setRoles(roles);

        SelectiveUserShareOrgDetailsV2DO organization = new SelectiveUserShareOrgDetailsV2DO();
        organization.setOrganizationId(targetOrgId);
        organization.setPolicy(PolicyEnum.valueOf((String) requestParams.get(POLICY)));
        organization.setRoleAssignments(roleAssignment);

        // The callback runs in a tenant flow without a user. Act as the user who requested the share.
        PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        String initiatorUsername = (String) requestParams.get(INITIATOR_USERNAME);
        if (StringUtils.isNotBlank(initiatorUsername)) {
            carbonContext.setUsername(initiatorUsername);
        }
        try {
            new UserSharingPolicyHandlerServiceImplV2().shareApprovedUser(userId,
                    Collections.singletonList(organization), (String) requestParams.get(RESIDENT_ORG_ID),
                    (String) requestParams.get(INITIATOR_USER_ID));
        } catch (UserSharingMgtException e) {
            throw new WorkflowException("Error while sharing user: " + userId + " with organization: " +
                    targetOrgId + " after approval.", e);
        }
        AUDIT_LOG.info(String.format("Initiator : %s | Action : User Share Approved | Target : %s | " +
                        "Data : { \"Organization\" : \"%s\", \"Roles\" : \"%s\" } | Result : Success",
                response.get(RESPONSE_DECIDED_BY), userId, targetOrgId, toRoleMaps(roles)));
    }

    @Override
    public boolean retryNeedAtCallback() {

        return true;
    }

    @Override
    public String getEventId() {

        return EVENT_ID;
    }

    @Override
    public Map<String, String> getParamDefinitions() {

        return PARAM_DEFINITIONS;
    }

    @Override
    public String getFriendlyName() {

        return "Share User";
    }

    @Override
    public String getDescription() {

        return "Triggered when a user is shared with an organization.";
    }

    @Override
    public String getCategory() {

        return "Organization Operations";
    }

    private boolean isAlreadyShared(String userId, String orgId) throws UserSharingMgtServerException {

        try {
            return OrganizationUserSharingDataHolder.getInstance().getOrganizationUserSharingService()
                    .getUserAssociationOfAssociatedUserByOrgId(userId, orgId) != null;
        } catch (OrganizationManagementException e) {
            throw new UserSharingMgtServerException(ERROR_CODE_USER_SHARE_WORKFLOW.getCode(),
                    ERROR_CODE_USER_SHARE_WORKFLOW.getMessage(),
                    String.format(ERROR_CODE_USER_SHARE_WORKFLOW.getDescription(), userId, orgId), e);
        }
    }

    static boolean isOrganizationCreationPending(String orgId) throws UserSharingMgtServerException {

        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        try {
            return getWorkflowManagementService().entityHasPendingWorkflowsOfType(
                    new Entity(orgId, ORGANIZATION_ENTITY_TYPE, tenantId), ADD_ORGANIZATION_EVENT_ID);
        } catch (WorkflowException e) {
            throw new UserSharingMgtServerException(ERROR_CODE_USER_SHARE_WORKFLOW.getCode(),
                    ERROR_CODE_USER_SHARE_WORKFLOW.getMessage(),
                    "Error while checking pending creation approval of organization: " + orgId, e);
        }
    }

    static String resolveUsername(String userId, int tenantId) {

        try {
            AbstractUserStoreManager userStoreManager = (AbstractUserStoreManager) OrganizationUserSharingDataHolder
                    .getInstance().getRealmService().getTenantUserRealm(tenantId).getUserStoreManager();
            return userStoreManager.getUserNameFromUserID(userId);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            LOG.debug("Unable to resolve the username of user: " + userId, e);
            return null;
        }
    }

    static String resolveOrganizationName(String orgId) {

        try {
            return OrganizationUserSharingDataHolder.getInstance().getOrganizationManager()
                    .getOrganizationNameById(orgId);
        } catch (OrganizationManagementException e) {
            LOG.debug("Unable to resolve the name of organization: " + orgId, e);
            return null;
        }
    }

    static ArrayList<HashMap<String, String>> toRoleMaps(List<RoleWithAudienceDO> roles) {

        ArrayList<HashMap<String, String>> roleMaps = new ArrayList<>();
        if (roles == null) {
            return roleMaps;
        }
        for (RoleWithAudienceDO role : roles) {
            HashMap<String, String> roleMap = new HashMap<>();
            roleMap.put(ROLE_NAME, role.getRoleName());
            roleMap.put(AUDIENCE_NAME, role.getAudienceName());
            roleMap.put(AUDIENCE_TYPE, role.getAudienceType());
            roleMaps.add(roleMap);
        }
        return roleMaps;
    }

    static List<RoleWithAudienceDO> fromRoleMaps(Object value) {

        List<RoleWithAudienceDO> roles = new ArrayList<>();
        if (!(value instanceof Collection)) {
            return roles;
        }
        for (Object item : (Collection<?>) value) {
            if (item instanceof Map) {
                Map<?, ?> roleMap = (Map<?, ?>) item;
                roles.add(new RoleWithAudienceDO(asString(roleMap.get(ROLE_NAME)),
                        asString(roleMap.get(AUDIENCE_NAME)), asString(roleMap.get(AUDIENCE_TYPE))));
            }
        }
        return roles;
    }

    private static String asString(Object value) {

        return value == null ? null : value.toString();
    }

    static WorkflowManagementService getWorkflowManagementService() throws WorkflowException {

        WorkflowManagementService service = (WorkflowManagementService) PrivilegedCarbonContext
                .getThreadLocalCarbonContext().getOSGiService(WorkflowManagementService.class, null);
        if (service == null) {
            throw new WorkflowException("Workflow management service is not available.");
        }
        return service;
    }
}
