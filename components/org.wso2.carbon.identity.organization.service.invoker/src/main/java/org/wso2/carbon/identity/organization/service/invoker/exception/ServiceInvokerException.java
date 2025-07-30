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

package org.wso2.carbon.identity.organization.service.invoker.exception;

/**
 * Exception class for service invoker related errors.
 */
public class ServiceInvokerException extends Exception {

    /**
     * Constructs a new exception with the specified message.
     *
     * @param message Detailed message.
     */
    public ServiceInvokerException(String message) {

        super(message);
    }

    /**
     * Constructs a new exception with the specified cause.
     *
     * @param e Cause as {@link Throwable}.
     */
    public ServiceInvokerException(Throwable e) {

        super(e);
    }

    /**
     * Constructs a new exception with the specified message and cause.
     *
     * @param message Detailed message.
     * @param e       Cause as {@link Throwable}.
     */
    public ServiceInvokerException(String message, Throwable e) {

        super(message, e);
    }
}
