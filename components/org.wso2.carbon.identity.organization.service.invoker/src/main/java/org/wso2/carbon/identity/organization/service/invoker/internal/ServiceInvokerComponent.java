/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
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

package org.wso2.carbon.identity.organization.service.invoker.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.identity.organization.service.invoker.ServiceInvoker;
import org.wso2.carbon.identity.organization.service.invoker.ServiceInvokerImpl;
import org.wso2.carbon.identity.role.v2.mgt.core.RoleManagementService;

/**
 * OSGi component responsible for managing the activation and deactivation of the organization service invoker service.
 */
@Component(
        name = "org.wso2.carbon.identity.organization.service.invoker",
        immediate = true)
public class ServiceInvokerComponent {

    private static final Log LOG = LogFactory.getLog(ServiceInvokerComponent.class);

    /**
     * Activates the OSGi component.
     * This method is called when the component is activated in the OSGi environment.
     *
     * @param componentContext The component context.
     */
    @Activate
    protected void activate(ComponentContext componentContext) {

        try {
            BundleContext bundleContext = componentContext.getBundleContext();
            bundleContext.registerService(ServiceInvoker.class.getName(), new ServiceInvokerImpl(), null);
            LOG.debug("Organization service invoker component activated successfully.");
        } catch (Exception e) {
            LOG.error("Error while activating the organization service invoker component.", e);
        }
    }

    /**
     * Deactivates the OSGi component.
     * This method is called when the component is deactivated in the OSGi environment.
     *
     * @param componentContext The component context.
     */
    @Deactivate
    protected void deactivate(ComponentContext componentContext) {

        LOG.debug("Organization service invoker component deactivated.");
    }

    /**
     * Sets the RoleManagementServiceV2 instance.
     *
     * @param roleManagementService The RoleManagementService instance to set.
     */
    @Reference(
            name = "org.wso2.carbon.identity.role.v2.mgt.core.RoleManagementService",
            service = org.wso2.carbon.identity.role.v2.mgt.core.RoleManagementService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetRoleManagementServiceV2")
    protected void setRoleManagementServiceV2(RoleManagementService roleManagementService) {

        ServiceInvokerDataHolder.getInstance().setRoleManagementServiceV2(roleManagementService);
        LOG.debug("RoleManagementServiceV2 set in ServiceInvokerComponent bundle.");
    }

    /**
     * Unsets the RoleManagementServiceV2 instance.
     *
     * @param roleManagementService The RoleManagementService instance to unset.
     */
    protected void unsetRoleManagementServiceV2(RoleManagementService roleManagementService) {

        ServiceInvokerDataHolder.getInstance().setRoleManagementServiceV2(null);
        LOG.debug("RoleManagementServiceV2 unset in ServiceInvokerComponent bundle.");
    }
}
