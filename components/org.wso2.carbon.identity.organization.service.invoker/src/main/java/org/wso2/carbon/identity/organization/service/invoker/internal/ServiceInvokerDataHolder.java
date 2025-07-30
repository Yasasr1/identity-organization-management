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

import org.wso2.carbon.identity.role.v2.mgt.core.RoleManagementService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton class that serves as a centralized data holder for service instances used in the service invoker.
 */
public class ServiceInvokerDataHolder {

    private static final ServiceInvokerDataHolder INSTANCE = new ServiceInvokerDataHolder();

    private RoleManagementService roleManagementServiceV2;
    private final Map<String, Object> serviceInstances = new ConcurrentHashMap<>();

    private ServiceInvokerDataHolder() {

    }

    /**
     * Retrieves the singleton instance of the ServiceInvokerDataHolder class.
     *
     * @return The singleton instance of ServiceInvokerDataHolder.
     */
    public static ServiceInvokerDataHolder getInstance() {

        return INSTANCE;
    }

    /**
     * Retrieves the current instance of the RoleManagementService.
     *
     * @return The current RoleManagementService instance.
     */
    public RoleManagementService getRoleManagementServiceV2() {

        return roleManagementServiceV2;
    }

    /**
     * Sets the RoleManagementService instance.
     *
     * @param roleManagementServiceV2 The RoleManagementService instance to set.
     */
    public void setRoleManagementServiceV2(RoleManagementService roleManagementServiceV2) {

        this.roleManagementServiceV2 = roleManagementServiceV2;
        if (roleManagementServiceV2 != null) {
            registerServiceInstance(roleManagementServiceV2);
        } else {
            serviceInstances.remove(RoleManagementService.class.getName());
        }
    }

    /**
     * Registers a service instance in the data holder.
     *
     * @param serviceInstance The service instance to register.
     */
    public void registerServiceInstance(Object serviceInstance) {

        if (serviceInstance != null) {
            serviceInstances.put(serviceInstance.getClass().getName(), serviceInstance);
        }
    }

    /**
     * Retrieves a service instance by its class name.
     *
     * @param className The class name of the service instance to retrieve.
     * @return The service instance if found, otherwise null.
     */
    public Object getServiceInstance(String className) {

        return serviceInstances.get(className);
    }
}
