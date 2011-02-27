/**
 * Copyright (C) 2011 Ovea <dev@ovea.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ovea.jetty.session;

import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
public abstract class SessionManagerSkeleton<T extends SessionManagerSkeleton.SessionSkeleton> extends AbstractSessionManager {

    private final ConcurrentMap<String, T> sessions = new ConcurrentHashMap<String, T>();

    @Override
    public void doStart() throws Exception {
        sessions.clear();
        super.doStart();
    }

    @Override
    public void doStop() throws Exception {
        sessions.clear();
        super.doStop();
    }

    @Override
    protected final void addSession(AbstractSessionManager.Session session) {
        if (isRunning()) {
            T sessionSkeleton = (T) session;
            String clusterId = getClusterId(session);
            sessions.put(clusterId, sessionSkeleton);
            sessionSkeleton.willPassivate();
            storeSession(sessionSkeleton);
            sessionSkeleton.didActivate();
        }
    }

    @Override
    public final void removeSession(AbstractSessionManager.Session session, boolean invalidate) {
        T sessionSkeleton = (T) session;
        // Remove session from context and global maps
        boolean removed = false;
        String clusterId = getClusterId(session);
        synchronized (this) {
            //take this session out of the map of sessions for this context
            if (getSession(clusterId) != null) {
                removed = true;
                removeSession(clusterId);
            }
        }
        if (removed) {
            // Remove session from all context and global id maps
            _sessionIdManager.removeSession(session);
            if (invalidate)
                _sessionIdManager.invalidateAll(clusterId);
            if (invalidate && _sessionListeners != null) {
                HttpSessionEvent event = new HttpSessionEvent(session);
                for (int i = LazyList.size(_sessionListeners); i-- > 0;)
                    ((HttpSessionListener) LazyList.get(_sessionListeners, i)).sessionDestroyed(event);
            }
            if (!invalidate) {
                sessionSkeleton.willPassivate();
            }
        }
    }

    @Override
    protected final boolean removeSession(String clusterId) {
        synchronized (this) {
            T session = sessions.remove(clusterId);
            try {
                if (session != null)
                    deleteSession(session);
            } catch (Exception e) {
                Log.warn("Problem deleting session id=" + clusterId, e);
            }
            return session != null;
        }
    }

    @Override
    public final SessionManagerSkeleton.SessionSkeleton getSession(String clusterId) {
        synchronized (sessions) {
            T current = sessions.get(clusterId);
            T loaded = loadSession(clusterId, current);
            if (loaded != null) {
                sessions.put(clusterId, loaded);
                if (current != loaded)
                    loaded.didActivate();
            }
            return loaded;
        }
    }

    @Override
    @Deprecated
    public final Map getSessionMap() {
        return Collections.unmodifiableMap(sessions);
    }

    @Override
    protected final void invalidateSessions() {
        //Do nothing - we don't want to remove and
        //invalidate all the sessions because this
        //method is called from doStop(), and just
        //because this context is stopping does not
        //mean that we should remove the session from
        //any other nodes
    }

    public final void invalidateSession(String clusterId) {
        Session session = sessions.get(clusterId);
        if (session != null)
            session.invalidate();
    }

    public final void expire(List<String> expired) {
        if (isStopping() || isStopped())
            return;
        ClassLoader old_loader = Thread.currentThread().getContextClassLoader();
        try {
            for (String expiredClusterId : expired) {
                if (Log.isDebugEnabled())
                    Log.debug("Expiring session id " + expiredClusterId);
                T session = sessions.get(expiredClusterId);
                if (session != null)
                    session.timeout();
            }
        } catch (Throwable t) {
            if (t instanceof ThreadDeath)
                throw ((ThreadDeath) t);
            else
                Log.warn("Problem expiring sessions", t);
        } finally {
            Thread.currentThread().setContextClassLoader(old_loader);
        }
    }

    protected final String getVirtualHost() {
        String vhost = "0.0.0.0";
        if (_context == null)
            return vhost;
        String[] vhosts = _context.getContextHandler().getVirtualHosts();
        if (vhosts == null || vhosts.length == 0 || vhosts[0] == null)
            return vhost;
        return vhosts[0];
    }

    protected final String getCanonicalizedContext() {
        if (_context.getContextPath() == null) return "";
        return _context.getContextPath().replace('/', '_').replace('.', '_').replace('\\', '_');
    }

    protected abstract void storeSession(T session);

    protected abstract void deleteSession(T session);

    protected abstract T loadSession(String clusterId, T current);

    public abstract class SessionSkeleton extends AbstractSessionManager.Session {

        private static final long serialVersionUID = -466877123269635865L;

        public SessionSkeleton(HttpServletRequest request) {
            super(request);
        }

        public SessionSkeleton(long created, long accessed, String clusterId) {
            super(created, accessed, clusterId);
        }

        @Override
        public String getClusterId() {
            return super.getClusterId();
        }

        @Override
        public boolean isValid() {
            return super.isValid();
        }

        @Override
        public void willPassivate() {
            super.willPassivate();
        }

        @Override
        public void didActivate() {
            super.didActivate();
        }

        @Override
        public void timeout() throws IllegalStateException {
            Log.debug("Timing out session id={}", _clusterId);
            super.timeout();
        }

    }
}