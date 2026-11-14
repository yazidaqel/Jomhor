package ma.jomhor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

/**
 * Counts the JDBC statements created through the application's DataSource (every connection it hands out), for the
 * "no SQL on this path" checks (plan lot-5 §6). Read {@link #count()} before and after the call under test.
 */
public class StatementCounter implements BeanPostProcessor {

    private static final Set<String> STATEMENTS = Set.of("createStatement", "prepareStatement", "prepareCall");

    private final AtomicLong count = new AtomicLong();

    public long count() {
        return count.get();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource ds)) {
            return bean;
        }
        // Every interface of the pool (AutoCloseable too, so the context still closes it).
        return proxy(ClassUtils.getAllInterfaces(ds), ds, (p, m, args) -> {
            Object result = call(m, ds, args);
            return result instanceof Connection c ? proxy(new Class<?>[] {Connection.class}, c, (cp, cm, cargs) -> {
                if (STATEMENTS.contains(cm.getName())) {
                    count.incrementAndGet();
                }
                return call(cm, c, cargs);
            }) : result;
        });
    }

    /** equals and hashCode by proxy identity, so Spring finds a transaction's connection as the one it handed out. */
    private static Object proxy(Class<?>[] types, Object target, InvocationHandler handler) {
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), types, (p, m, args) ->
                switch (m.getName()) {
                    case "equals" -> p == args[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> handler.invoke(p, m, args);
                });
    }

    private static Object call(Method m, Object target, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
