package org.example.retry;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Modifier;
import java.util.Map;

/** Uses Spring proxies so the annotation wraps only the external adapter invocation. */
public final class RetryAnnotationPostProcessor implements BeanPostProcessor {
    private final BeanFactory beans;

    public RetryAnnotationPostProcessor(BeanFactory beans) { this.beans = beans; }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        boolean annotated = false;
        for (var method : bean.getClass().getMethods()) {
            if (AnnotatedElementUtils.findMergedAnnotation(method, RetryableOperation.class) == null) continue;
            if (Modifier.isFinal(method.getModifiers()) || Modifier.isFinal(bean.getClass().getModifiers())) {
                throw new IllegalArgumentException("Retry annotations require non-final methods and classes: " + beanName);
            }
            var annotation = method.getAnnotation(RetryableOperation.class);
            policies().policy(annotation.policy());
            beans.getBean(annotation.classifier());
            annotated = true;
        }
        if (!annotated) return bean;
        var proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            var annotation = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), RetryableOperation.class);
            if (annotation == null) return invocation.proceed();
            RetryPolicy policy = policies().policy(annotation.policy());
            RetryClassifier classifier = beans.getBean(annotation.classifier());
            RetryExecutor executor = beans.getBean(RetryExecutor.class);
            return executor.execute(annotation.policy(), policy, classifier,
                    () -> ((ProxyMethodInvocation) invocation).invocableClone().proceed());
        });
        return proxy.getProxy();
    }

    private PolicyRegistry policies() { return beans.getBean(PolicyRegistry.class); }

    public record PolicyRegistry(Map<String, RetryPolicy> policies) {
        public PolicyRegistry { policies = Map.copyOf(policies); }
        public RetryPolicy policy(String name) {
            RetryPolicy policy = policies.get(name);
            if (policy == null) throw new IllegalArgumentException("Unknown retry policy: " + name);
            return policy;
        }
    }
}
