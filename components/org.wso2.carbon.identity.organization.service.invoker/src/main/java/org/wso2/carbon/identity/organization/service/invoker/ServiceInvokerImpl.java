package org.wso2.carbon.identity.organization.service.invoker;

import org.wso2.carbon.identity.organization.service.invoker.exception.ServiceInvokerException;
import org.wso2.carbon.identity.organization.service.invoker.internal.ServiceInvokerDataHolder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Implementation of the ServiceInvoker interface.
 * This class is responsible for invoking services based on the provided service name, method name,
 * parameter types, and arguments.
 */
public class ServiceInvokerImpl implements ServiceInvoker {

    @Override
    public <T> T invoke(String serviceName, String methodName, Class<?>[] parameterTypes, Object... args)
            throws ServiceInvokerException {

        Object serviceInstance = ServiceInvokerDataHolder.getInstance()
                .getServiceInstance(serviceName);

        if (serviceInstance == null) {
            throw new ServiceInvokerException("Service instance for service name: " + serviceName + " not found.");
        }

        try {
            Method method = serviceInstance.getClass().getMethod(methodName, parameterTypes);
            // The caller must ensure that the method return type matches T.
            return (T) method.invoke(serviceInstance, args);
        } catch (NoSuchMethodException e) {
            throw new ServiceInvokerException("Method: " + methodName + " does not exist in class: " + serviceName, e);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new ServiceInvokerException("Error invoking method: " + methodName + " on service: " + serviceName,
                    e);
        }
    }
}
