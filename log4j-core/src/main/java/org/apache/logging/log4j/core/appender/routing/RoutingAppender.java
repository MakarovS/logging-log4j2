/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.appender.routing;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.script.Bindings;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LifeCycle2;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.script.AbstractScript;
import org.apache.logging.log4j.core.script.ScriptManager;
import org.apache.logging.log4j.core.util.Booleans;

/**
 * This Appender "routes" between various Appenders, some of which can be references to
 * Appenders defined earlier in the configuration while others can be dynamically created
 * within this Appender as required. Routing is achieved by specifying a pattern on
 * the Routing appender declaration. The pattern should contain one or more substitution patterns of
 * the form "$${[key:]token}". The pattern will be resolved each time the Appender is called using
 * the built in StrSubstitutor and the StrLookup plugin that matches the specified key.
 */
@Plugin(name = "Routing", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class RoutingAppender extends AbstractAppender {

    public static final String STATIC_VARIABLES_KEY = "staticVariables";

    public static class Builder<B extends Builder<B>> extends AbstractAppender.Builder<B>
            implements org.apache.logging.log4j.core.util.Builder<RoutingAppender> {

        // Does not work unless the element is called "Script", I wanted "DefaultRounteScript"...
        @PluginElement("Script")
        private AbstractScript defaultRouteScript;

        @PluginElement("Routes")
        private Routes routes;

        @PluginElement("RewritePolicy")
        private RewritePolicy rewritePolicy;

        @PluginElement("PurgePolicy")
        private PurgePolicy purgePolicy;

        @Override
        public RoutingAppender build() {
            final String name = getName();
            if (name == null) {
                LOGGER.error("No name defined for this RoutingAppender");
                return null;
            }
            if (routes == null) {
                LOGGER.error("No routes defined for RoutingAppender {}", name);
                return null;
            }
            return new RoutingAppender(name, getFilter(), isIgnoreExceptions(), routes, rewritePolicy,
                    getConfiguration(), purgePolicy, defaultRouteScript, getPropertyArray());
        }

        public Routes getRoutes() {
            return routes;
        }

        public AbstractScript getDefaultRouteScript() {
            return defaultRouteScript;
        }

        public RewritePolicy getRewritePolicy() {
            return rewritePolicy;
        }

        public PurgePolicy getPurgePolicy() {
            return purgePolicy;
        }

        public B setRoutes(@SuppressWarnings("hiding") final Routes routes) {
            this.routes = routes;
            return asBuilder();
        }

        public B setDefaultRouteScript(@SuppressWarnings("hiding") final AbstractScript defaultRouteScript) {
            this.defaultRouteScript = defaultRouteScript;
            return asBuilder();
        }

        public B setRewritePolicy(@SuppressWarnings("hiding") final RewritePolicy rewritePolicy) {
            this.rewritePolicy = rewritePolicy;
            return asBuilder();
        }

        public void setPurgePolicy(@SuppressWarnings("hiding") final PurgePolicy purgePolicy) {
            this.purgePolicy = purgePolicy;
        }

    }

    @PluginBuilderFactory
    public static <B extends Builder<B>> B newBuilder() {
        return new Builder<B>().asBuilder();
    }

    private static final String DEFAULT_KEY = "ROUTING_APPENDER_DEFAULT";

    private final Routes routes;
    private Route defaultRoute;
    private final Configuration configuration;
    private final ConcurrentMap<String, AppenderControl> appenders = new ConcurrentHashMap<>();
    private final RewritePolicy rewritePolicy;
    private final PurgePolicy purgePolicy;
    private final AbstractScript defaultRouteScript;
    private final ConcurrentMap<Object, Object> scriptStaticVariables = new ConcurrentHashMap<>();

    private RoutingAppender(final String name, final Filter filter, final boolean ignoreExceptions, final Routes routes,
            final RewritePolicy rewritePolicy, final Configuration configuration, final PurgePolicy purgePolicy,
            final AbstractScript defaultRouteScript, Property[] properties) {
        super(name, filter, null, ignoreExceptions, properties);
        this.routes = routes;
        this.configuration = configuration;
        this.rewritePolicy = rewritePolicy;
        this.purgePolicy = purgePolicy;
        if (this.purgePolicy != null) {
            this.purgePolicy.initialize(this);
        }
        this.defaultRouteScript = defaultRouteScript;
        Route defRoute = null;
        for (final Route route : routes.getRoutes()) {
            if (route.getKey() == null) {
                if (defRoute == null) {
                    defRoute = route;
                } else {
                    error("Multiple default routes. Route " + route.toString() + " will be ignored");
                }
            }
        }
        defaultRoute = defRoute;
    }

    @Override
    public void start() {
        if (defaultRouteScript != null) {
            if (configuration == null) {
                error("No Configuration defined for RoutingAppender; required for Script element.");
            } else {
                final ScriptManager scriptManager = configuration.getScriptManager();
                scriptManager.addScript(defaultRouteScript);
                final Bindings bindings = scriptManager.createBindings(defaultRouteScript);
                bindings.put(STATIC_VARIABLES_KEY, scriptStaticVariables);
                final Object object = scriptManager.execute(defaultRouteScript.getName(), bindings);
                final Route route = routes.getRoute(Objects.toString(object, null));
                if (route != null) {
                    defaultRoute = route;
                }
            }
        }
        // Register all the static routes.
        for (final Route route : routes.getRoutes()) {
            if (route.getAppenderRef() != null) {
                final Appender appender = configuration.getAppender(route.getAppenderRef());
                if (appender != null) {
                    final String key = route == defaultRoute ? DEFAULT_KEY : route.getKey();
                    appenders.put(key, new AppenderControl(appender, null, null));
                } else {
                    error("Appender " + route.getAppenderRef() + " cannot be located. Route ignored");
                }
            }
        }
        super.start();
    }

    @Override
    public boolean stop(final long timeout, final TimeUnit timeUnit) {
        setStopping();
        super.stop(timeout, timeUnit, false);
        final Map<String, Appender> map = configuration.getAppenders();
        for (final Map.Entry<String, AppenderControl> entry : appenders.entrySet()) {
            final Appender appender = entry.getValue().getAppender();
            if (!map.containsKey(appender.getName())) {
                if (appender instanceof LifeCycle2) {
                    ((LifeCycle2) appender).stop(timeout, timeUnit);
                } else {
                    appender.stop();
                }
            }
        }
        setStopped();
        return true;
    }

    @Override
    public void append(LogEvent event) {
        if (rewritePolicy != null) {
            event = rewritePolicy.rewrite(event);
        }
        final String pattern = routes.getPattern(event, scriptStaticVariables);
        final String key = pattern != null ? configuration.getStrSubstitutor().replace(event, pattern) : defaultRoute.getKey();
        final AppenderControl control = getControl(key, event);
        if (control != null) {
            control.callAppender(event);
        }

        if (purgePolicy != null) {
            purgePolicy.update(key, event);
        }
    }

    private synchronized AppenderControl getControl(final String key, final LogEvent event) {
        AppenderControl control = appenders.get(key);
        if (control != null) {
            return control;
        }
        Route route = null;
        for (final Route r : routes.getRoutes()) {
            if (r.getAppenderRef() == null && key.equals(r.getKey())) {
                route = r;
                break;
            }
        }
        if (route == null) {
            route = defaultRoute;
            control = appenders.get(DEFAULT_KEY);
            if (control != null) {
                return control;
            }
        }
        if (route != null) {
            final Appender app = createAppender(route, event);
            if (app == null) {
                return null;
            }
            control = new AppenderControl(app, null, null);
            appenders.put(key, control);
        }

        return control;
    }

    private Appender createAppender(final Route route, final LogEvent event) {
        final Node routeNode = route.getNode();
        for (final Node node : routeNode.getChildren()) {
            if (node.getType().getElementName().equals(Appender.ELEMENT_TYPE)) {
                final Node appNode = new Node(node);
                configuration.createConfiguration(appNode, event);
                if (appNode.getObject() instanceof Appender) {
                    final Appender app = appNode.getObject();
                    app.start();
                    return app;
                }
                error("Unable to create Appender of type " + node.getName());
                return null;
            }
        }
        error("No Appender was configured for route " + route.getKey());
        return null;
    }

    public Map<String, AppenderControl> getAppenders() {
        return Collections.unmodifiableMap(appenders);
    }

    /**
     * Deletes the specified appender.
     *
     * @param key The appender's key
     */
    public void deleteAppender(final String key) {
        LOGGER.debug("Deleting route with " + key + " key ");
        final AppenderControl control = appenders.remove(key);
        if (null != control) {
            LOGGER.debug("Stopping route with " + key + " key");
            control.getAppender().stop();
        } else {
            LOGGER.debug("Route with " + key + " key already deleted");
        }
    }

    public Route getDefaultRoute() {
        return defaultRoute;
    }

    public AbstractScript getDefaultRouteScript() {
        return defaultRouteScript;
    }

    public PurgePolicy getPurgePolicy() {
        return purgePolicy;
    }

    public RewritePolicy getRewritePolicy() {
        return rewritePolicy;
    }

    public Routes getRoutes() {
        return routes;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public ConcurrentMap<Object, Object> getScriptStaticVariables() {
        return scriptStaticVariables;
    }
}
