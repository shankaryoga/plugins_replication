// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication;

import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupBackends;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.RequestScopedReviewDbProvider;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.PerRequestProjectControlCache;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.util.RequestContext;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.servlet.RequestScoped;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class Destination {
  private static final Logger repLog = ReplicationQueue.repLog;
  private final ReplicationStateListener stateLog;

  private final int poolThreads;
  private final String poolName;

  private final RemoteConfig remote;
  private final String[] adminUrls;
  private final String[] urls;
  private final String[] projects;
  private final String[] authGroupNames;
  private final int delay;
  private final int retryDelay;
  private final Object stateLock = new Object();
  private final int lockErrorMaxRetries;
  private final Map<URIish, PushOne> pending = new HashMap<>();
  private final Map<URIish, PushOne> inFlight = new HashMap<>();
  private final PushOne.Factory opFactory;
  private final ProjectControl.Factory projectControlFactory;
  private final GitRepositoryManager gitManager;
  private final boolean createMissingRepos;
  private final boolean replicatePermissions;
  private final boolean replicateProjectDeletions;
  private final String remoteNameStyle;
  private volatile WorkQueue.Executor pool;
  private final PerThreadRequestScope.Scoper threadScoper;

  protected static enum RetryReason {
    TRANSPORT_ERROR, COLLISION, REPOSITORY_MISSING;
  }

  public static class QueueInfo {
    public final Map<URIish, PushOne> pending;
    public final Map<URIish, PushOne> inFlight;

    public QueueInfo(Map<URIish, PushOne> pending,
        Map<URIish, PushOne> inFlight) {
      this.pending = ImmutableMap.copyOf(pending);
      this.inFlight = ImmutableMap.copyOf(inFlight);
    }
  }

  protected Destination(Injector injector,
      RemoteConfig rc,
      Config cfg,
      RemoteSiteUser.Factory replicationUserFactory,
      PluginUser pluginUser,
      GitRepositoryManager gitRepositoryManager,
      GroupBackend groupBackend,
      ReplicationStateListener stateLog,
      GroupIncludeCache groupIncludeCache) {
    remote = rc;
    gitManager = gitRepositoryManager;
    this.stateLog = stateLog;

    delay = Math.max(0,
        getTimeUnit(rc, cfg, "replicationdelay", 15, TimeUnit.SECONDS));
    retryDelay = Math.max(0,
        getTimeUnit(rc, cfg, "replicationretry", 1, TimeUnit.MINUTES));
    lockErrorMaxRetries = cfg.getInt("replication", "lockErrorMaxRetries", 0);
    adminUrls = cfg.getStringList("remote", rc.getName(), "adminUrl");
    urls = cfg.getStringList("remote", rc.getName(), "url");

    poolThreads = Math.max(0, getInt(rc, cfg, "threads", 1));
    poolName = "ReplicateTo-" + rc.getName();
    createMissingRepos =
        cfg.getBoolean("remote", rc.getName(), "createMissingRepositories", true);
    replicatePermissions =
        cfg.getBoolean("remote", rc.getName(), "replicatePermissions", true);
    replicateProjectDeletions =
        cfg.getBoolean("remote", rc.getName(), "replicateProjectDeletions", false);
    remoteNameStyle = MoreObjects.firstNonNull(
        cfg.getString("remote", rc.getName(), "remoteNameStyle"), "slash");
    projects = cfg.getStringList("remote", rc.getName(), "projects");

    final CurrentUser remoteUser;
    authGroupNames = cfg.getStringList("remote", rc.getName(), "authGroup");
    if (authGroupNames.length > 0) {
      ImmutableSet.Builder<AccountGroup.UUID> builder = ImmutableSet.builder();
      for (String name : authGroupNames) {
        GroupReference g = GroupBackends.findExactSuggestion(groupBackend, name);
        if (g != null) {
          builder.add(g.getUUID());
          addRecursiveParents(g.getUUID(), builder, groupIncludeCache);
        } else {
          repLog.warn(String.format(
              "Group \"%s\" not recognized, removing from authGroup", name));
        }
      }
      remoteUser = replicationUserFactory.create(
          new ListGroupMembership(builder.build()));
    } else {
      remoteUser = pluginUser;
    }

    Injector child = injector.createChildInjector(new FactoryModule() {
      @Override
      protected void configure() {
        bindScope(RequestScoped.class, PerThreadRequestScope.REQUEST);
        bind(PerThreadRequestScope.Propagator.class);
        bind(PerRequestProjectControlCache.class).in(RequestScoped.class);

        bind(Destination.class).toInstance(Destination.this);
        bind(RemoteConfig.class).toInstance(remote);
        install(new FactoryModuleBuilder().build(PushOne.Factory.class));
      }

      @Provides
      public PerThreadRequestScope.Scoper provideScoper(
          final PerThreadRequestScope.Propagator propagator,
          final Provider<RequestScopedReviewDbProvider> dbProvider) {
        final RequestContext requestContext = new RequestContext() {
          @Override
          public CurrentUser getUser() {
            return remoteUser;
          }

          @Override
          public Provider<ReviewDb> getReviewDbProvider() {
            return dbProvider.get();
          }
        };
        return new PerThreadRequestScope.Scoper() {
          @Override
          public <T> Callable<T> scope(Callable<T> callable) {
            return propagator.scope(requestContext, callable);
          }
        };
      }
    });

    projectControlFactory = child.getInstance(ProjectControl.Factory.class);
    opFactory = child.getInstance(PushOne.Factory.class);
    threadScoper = child.getInstance(PerThreadRequestScope.Scoper.class);
  }

  private void addRecursiveParents(AccountGroup.UUID g,
      Builder<AccountGroup.UUID> builder, GroupIncludeCache groupIncludeCache) {
    for (AccountGroup.UUID p : groupIncludeCache.parentGroupsOf(g)) {
      if (builder.build().contains(p)) {
        continue;
      }
      builder.add(p);
      addRecursiveParents(p, builder, groupIncludeCache);
    }
  }

  public QueueInfo getQueueInfo() {
    synchronized (stateLock) {
      return new QueueInfo(pending, inFlight);
    }
  }

  public void start(WorkQueue workQueue) {
    pool = workQueue.createQueue(poolThreads, poolName);
  }

  public int shutdown() {
    int cnt = 0;
    if (pool != null) {
      for (Runnable r : pool.getQueue()) {
        repLog.warn(String.format("Cancelling replication event %s", r));
      }
      cnt = pool.shutdownNow().size();
      pool.unregisterWorkQueue();
      pool = null;
    }
    return cnt;
  }

  private static int getInt(
      RemoteConfig rc, Config cfg, String name, int defValue) {
    return cfg.getInt("remote", rc.getName(), name, defValue);
  }

  private static int getTimeUnit(
      RemoteConfig rc, Config cfg, String name, int defValue, TimeUnit unit) {
    return (int)ConfigUtil.getTimeUnit(
        cfg, "remote", rc.getName(), name, defValue, unit);
  }

  private boolean isVisible(final Project.NameKey project,
      ReplicationState... states) {
    try {
      return threadScoper.scope(new Callable<Boolean>() {
        @Override
        public Boolean call() throws NoSuchProjectException {
          return controlFor(project).isVisible();
        }
      }).call();
    } catch (NoSuchProjectException err) {
      stateLog.error(String.format("source project %s not available", project),
          err, states);
    } catch (Exception e) {
      Throwables.propagateIfPossible(e);
      throw new RuntimeException(e);
    }
    return false;
  }

  void schedule(Project.NameKey project, String ref, URIish uri,
      ReplicationState state) {
    repLog.info("scheduling replication {}:{} => {}", project, ref, uri);
    if (!isVisible(project, state)) {
      return;
    }

    if (!replicatePermissions) {
      PushOne e;
      synchronized (stateLock) {
        e = pending.get(uri);
      }
      if (e == null) {
        try (Repository git = gitManager.openRepository(project)) {
          try {
            Ref head = git.exactRef(Constants.HEAD);
            if (head != null
                && head.isSymbolic()
                && RefNames.REFS_CONFIG.equals(head.getLeaf().getName())) {
              return;
            }
          } catch (IOException err) {
            stateLog.error(String.format(
                "cannot check type of project %s", project), err, state);
            return;
          }
        } catch (IOException err) {
          stateLog.error(String.format(
              "source project %s not available", project), err, state);
          return;
        }
      }
    }

    synchronized (stateLock) {
      PushOne e = pending.get(uri);
      if (e == null) {
        e = opFactory.create(project, uri);
        pool.schedule(e, delay, TimeUnit.SECONDS);
        pending.put(uri, e);
      }
      e.addRef(ref);
      state.increasePushTaskCount(project.get(), ref);
      e.addState(ref, state);
      repLog.info("scheduled {}:{} => {} to run after {}s", project, ref,
          e, delay);
    }
  }

  /**
   * It schedules again a PushOp instance.
   * <p>
   * If the reason for rescheduling is to avoid a collision
   * with an in-flight push to the same URI, we don't
   * mark the operation as "retrying," and we schedule
   * using the replication delay, rather than the retry
   * delay.  Otherwise,  the operation is marked as
   * "retrying" and scheduled to run following the
   * minutes count determined by class attribute retryDelay.
   * <p>
   * In case the PushOp instance to be scheduled has same
   * URI than one marked as "retrying," it adds to the one
   * pending the refs list of the parameter instance.
   * <p>
   * In case the PushOp instance to be scheduled has the
   * same URI as one pending, but not marked "retrying," it
   * indicates the one pending should be canceled when it
   * starts executing, removes it from pending list, and
   * adds its refs to the parameter instance. The parameter
   * instance is scheduled for retry.
   * <p>
   * Notice all operations to indicate a PushOp should be
   * canceled, or it is retrying, or remove/add it from/to
   * pending Map should be protected by synchronizing on the
   * stateLock object.
   *
   * @param pushOp The PushOp instance to be scheduled.
   */
  void reschedule(PushOne pushOp, RetryReason reason) {
    synchronized (stateLock) {
      URIish uri = pushOp.getURI();
      PushOne pendingPushOp = pending.get(uri);

      if (pendingPushOp != null) {
        // There is one PushOp instance already pending to same URI.

        if (pendingPushOp.isRetrying()) {
          // The one pending is one already retrying, so it should
          // maintain it and add to it the refs of the one passed
          // as parameter to the method.

          // This scenario would happen if a PushOp has started running
          // and then before it failed due transport exception, another
          // one to same URI started. The first one would fail and would
          // be rescheduled, being present in pending list. When the
          // second one fails, it will also be rescheduled and then,
          // here, find out replication to its URI is already pending
          // for retry (blocking).
          pendingPushOp.addRefs(pushOp.getRefs());
          pendingPushOp.addStates(pushOp.getStates());
          pushOp.removeStates();

        } else {
          // The one pending is one that is NOT retrying, it was just
          // scheduled believing no problem would happen. The one pending
          // should be canceled, and this is done by setting its canceled
          // flag, removing it from pending list, and adding its refs to
          // the pushOp instance that should then, later, in this method,
          // be scheduled for retry.

          // Notice that the PushOp found pending will start running and,
          // when notifying it is starting (with pending lock protection),
          // it will see it was canceled and then it will do nothing with
          // pending list and it will not execute its run implementation.

          pendingPushOp.cancel();
          pending.remove(uri);

          pushOp.addRefs(pendingPushOp.getRefs());
          pushOp.addStates(pendingPushOp.getStates());
          pendingPushOp.removeStates();
        }
      }

      if (pendingPushOp == null || !pendingPushOp.isRetrying()) {
        pending.put(uri, pushOp);
        switch (reason) {
          case COLLISION:
            pool.schedule(pushOp, delay, TimeUnit.SECONDS);
            break;
          case TRANSPORT_ERROR:
          case REPOSITORY_MISSING:
          default:
            pushOp.setToRetry();
            pool.schedule(pushOp, retryDelay, TimeUnit.MINUTES);
            break;
        }
      }
    }
  }

  ProjectControl controlFor(Project.NameKey project)
      throws NoSuchProjectException {
    return projectControlFactory.controlFor(project);
  }

  boolean requestRunway(PushOne op) {
    synchronized (stateLock) {
      if (op.wasCanceled()) {
        return false;
      }
      pending.remove(op.getURI());
      if (inFlight.containsKey(op.getURI())) {
        return false;
      }
      inFlight.put(op.getURI(), op);
    }
    return true;
  }

  void notifyFinished(PushOne op) {
    synchronized (stateLock) {
      inFlight.remove(op.getURI());
    }
  }

  boolean wouldPushProject(Project.NameKey project) {
    if (!isVisible(project)) {
      return false;
    }

    // by default push all projects
    if (projects.length < 1) {
      return true;
    }

    return (new ReplicationFilter(Arrays.asList(projects))).matches(project);
  }

  boolean isSingleProjectMatch() {
    boolean ret = (projects.length == 1);
    if (ret) {
      String projectMatch = projects[0];
      if (ReplicationFilter.getPatternType(projectMatch)
          != ReplicationFilter.PatternType.EXACT_MATCH) {
        // projectMatch is either regular expression, or wild-card.
        //
        // Even though they might refer to a single project now, they need not
        // after new projects have been created. Hence, we do not treat them as
        // matching a single project.
        ret = false;
      }
    }
    return ret;
  }

  boolean wouldPushRef(String ref) {
    if (!replicatePermissions && RefNames.REFS_CONFIG.equals(ref)) {
      return false;
    }
    for (RefSpec s : remote.getPushRefSpecs()) {
      if (s.matchSource(ref)) {
        return true;
      }
    }
    return false;
  }

  boolean isCreateMissingRepos() {
    return createMissingRepos;
  }

  boolean isReplicatePermissions() {
    return replicatePermissions;
  }

  boolean isReplicateProjectDeletions() {
    return replicateProjectDeletions;
  }

  List<URIish> getURIs(Project.NameKey project, String urlMatch) {
    List<URIish> r = Lists.newArrayListWithCapacity(remote.getURIs().size());
    for (URIish uri : remote.getURIs()) {
      if (matches(uri, urlMatch)) {
        String name = project.get();
        if (needsUrlEncoding(uri)) {
          name = encode(name);
        }
        if (remoteNameStyle.equals("dash")) {
          name = name.replace("/", "-");
        } else if(remoteNameStyle.equals("underscore")) {
          name = name.replace("/", "_");
        } else if (remoteNameStyle.equals("basenameOnly")) {
          name = FilenameUtils.getBaseName(name);
        } else if (!remoteNameStyle.equals("slash")) {
          repLog.debug(String.format(
              "Unknown remoteNameStyle: %s, falling back to slash",
              remoteNameStyle));
        }
        String replacedPath = ReplicationQueue.replaceName(uri.getPath(), name,
            isSingleProjectMatch());
        if (replacedPath != null) {
          uri = uri.setPath(replacedPath);
          r.add(uri);
        }
      }
    }
    return r;
  }

  static boolean needsUrlEncoding(URIish uri) {
    return "http".equalsIgnoreCase(uri.getScheme())
      || "https".equalsIgnoreCase(uri.getScheme())
      || "amazon-s3".equalsIgnoreCase(uri.getScheme());
  }

  static String encode(String str) {
    try {
      // Some cleanup is required. The '/' character is always encoded as %2F
      // however remote servers will expect it to be not encoded as part of the
      // path used to the repository. Space is incorrectly encoded as '+' for this
      // context. In the path part of a URI space should be %20, but in form data
      // space is '+'. Our cleanup replace fixes these two issues.
      return URLEncoder.encode(str, "UTF-8")
        .replaceAll("%2[fF]", "/")
        .replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  String[] getAdminUrls() {
    return adminUrls;
  }

  String[] getUrls() {
    return urls;
  }

  RemoteConfig getRemoteConfig() {
    return remote;
  }

  String[] getAuthGroupNames() {
    return authGroupNames;
  }

  String[] getProjects() {
    return projects;
  }

  int getLockErrorMaxRetries() {
    return lockErrorMaxRetries;
  }

  private static boolean matches(URIish uri, String urlMatch) {
    if (urlMatch == null || urlMatch.equals("") || urlMatch.equals("*")) {
      return true;
    }
    return uri.toString().contains(urlMatch);
  }
}
