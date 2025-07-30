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

package org.wso2.carbon.identity.organization.service.invoker;

import org.wso2.carbon.identity.organization.service.invoker.exception.ServiceInvokerException;

/**
 * Interface for invoking methods on OSGi services dynamically.
 * This interface allows for type-safe method invocations on registered OSGi services.
 */
public interface ServiceInvoker {

    /**
     * Dynamically invokes a method on a registered OSGi service with a type-safe return value.
     *
     * @param <T> The expected return type of the method. The caller should specify this.
     * @param serviceName The component name of the target service.
     * @param methodName The name of the method to invoke.
     * @param parameterTypes An array of Class objects representing the parameter types of the method to be invoked.
     * @param args The arguments to pass to the method invocation.
     * @return The result of the method invocation.
     * @throws ServiceInvokerException If the service is not found, the method does not exist,
     * or the invocation fails.
     */
    <T> T invoke(String serviceName, String methodName, Class<?>[] parameterTypes, Object... args) throws
            ServiceInvokerException;
}
