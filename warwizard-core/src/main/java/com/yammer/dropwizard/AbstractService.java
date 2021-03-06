package com.yammer.dropwizard;

import com.google.common.collect.Iterables;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.servlet.GuiceServletContextListener;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import com.yammer.dropwizard.config.AdminConfiguration;
import com.yammer.dropwizard.config.Configuration;
import com.yammer.dropwizard.config.ConfigurationFactory;
import com.yammer.dropwizard.config.LoggingFactory;
import com.yammer.dropwizard.jersey.*;
import com.yammer.dropwizard.jersey.caching.CacheControlledResourceMethodDispatchAdapter;
import com.yammer.dropwizard.lifecycle.Lifecycle;
import com.yammer.dropwizard.servlets.BasicAuthFilter;
import com.yammer.metrics.HealthChecks;
import com.yammer.metrics.core.HealthCheckRegistry;
import com.yammer.metrics.jersey.InstrumentedResourceMethodDispatchAdapter;
import com.yammer.metrics.reporting.AdminServlet;
import com.yammer.metrics.util.DeadlockHealthCheck;

import javax.servlet.ServletContextEvent;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;

public abstract class AbstractService<T extends Configuration> extends GuiceServletContextListener {
    static {
        LoggingFactory.bootstrap();
    }

    protected abstract String getConfigurationLocation();

    @SuppressWarnings("unchecked")
    public final Class<T> getConfigurationClass() {
        Type t = getClass();
        while (t instanceof Class<?>) {
            t = ((Class<?>) t).getGenericSuperclass();
        }
        // Similar to [Issue-89] (see {@link com.yammer.dropwizard.cli.ConfiguredCommand#getConfigurationClass})
        if (t instanceof ParameterizedType) {
            // should typically have one of type parameters (first one) that matches:
            for (Type param : ((ParameterizedType) t).getActualTypeArguments()) {
                if (param instanceof Class<?>) {
                    final Class<?> cls = (Class<?>) param;
                    if (Configuration.class.isAssignableFrom(cls)) {
                        return (Class<T>) cls;
                    }
                }
            }
        }
        throw new IllegalStateException("Can not figure out Configuration type parameterization for "+getClass().getName());
    }

    @Override
    protected Injector getInjector() {
        final T conf;
        try {
            conf = ConfigurationFactory.forClass(getConfigurationClass()).build(getConfigurationLocation());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        new LoggingFactory(conf.getLoggingConfiguration(), getClass().getSimpleName()).configure();
        return Guice.createInjector(Iterables.concat(Collections.singletonList(new JerseyServletModule() {
            @Override
            protected void configureServlets() {
                bind(GuiceContainer.class).to(DropwizardGuiceContainer.class);

                // Bind Jersey @Provider classes
                bindCatchallExceptionMapper(binder());
                bind(InvalidEntityExceptionMapper.class);
                bind(JsonProcessingExceptionMapper.class);
                bind(InstrumentedResourceMethodDispatchAdapter.class).toInstance(new InstrumentedResourceMethodDispatchAdapter());
                bind(CacheControlledResourceMethodDispatchAdapter.class);
                bind(OptionalQueryParamInjectableProvider.class);

                bind(Lifecycle.class).toInstance(lifecycle);
                bind(getConfigurationClass()).toInstance(conf);
                if (conf.getAdminConfiguration().isPresent()) {
                    AdminConfiguration adminConf = conf.getAdminConfiguration().get();
                    filter("/admin/*").through(new BasicAuthFilter(adminConf.getUsername(), adminConf.getPassword()));
                    serve("/admin/*").with(new AdminServlet());
                }
                bind(HealthCheckRegistry.class).toInstance(HealthChecks.defaultRegistry());
                HealthChecks.defaultRegistry().register(new DeadlockHealthCheck());
                serve("/*").with(GuiceContainer.class);
            }
        }), createModules(conf)));
    }

    protected void bindCatchallExceptionMapper(Binder binder) {
        // create a subclass to pin it to Throwable
        binder.bind(LoggingExceptionMapper.class).toInstance(new LoggingExceptionMapper<Throwable>() {});
    }

    protected abstract Iterable<Module> createModules(T configuration);

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        lifecycle.stop();
        super.contextDestroyed(servletContextEvent);
    }

    private final Lifecycle lifecycle = new Lifecycle();
}
