package dev.fixmyisland;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * Headless LSPosed module (libxposed API 102) that automatically detects third-party audio
 * players and injects them into OriginOS / Origin Island whitelists inside
 * {@code com.vivo.musicwidgetmix}.
 */
public final class HookEntry extends XposedModule {
    private static final String HOOK_TAG = "OriginIslandMusic";
    private static final String TARGET_MIX = "com.vivo.musicwidgetmix";
    private static final String CLS_MAIN_APP = "com.vivo.musicwidgetmix.MainApplication";
    private static final String CLS_SERVICE = "com.vivo.musicwidgetmix.service.MusicWidgetMixService";
    private static final String CLS_UTILS_D = "com.vivo.musicwidgetmix.utils.d";
    private static final String CLS_LIST_UTILS = "com.vivo.musicwidgetmix.utils.MusicListUtils";
    private static final String CLS_WHITELIST_HOLDER = "k4.f0";
    private static final String CLS_ISLAND_NOTIF_MGR = "k4.e";

    private static final String[] AUDIO_SERVICE_ACTIONS = {
            "android.media.browse.MediaBrowserService",
            "androidx.media3.session.MediaSessionService",
            "androidx.media3.session.MediaLibraryService"
    };
    private static final long MANIFEST_CACHE_TTL_MS = 30_000L;

    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<String> DISCOVERED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static volatile Set<String> cachedManifestPlayers = Collections.emptySet();
    private static volatile long lastManifestScanMs;

    private static WeakReference<Context> appContextRef = new WeakReference<>(null);
    private static volatile ClassLoader mixClassLoader;
    private static volatile boolean sessionListenerRegistered;

    static volatile XposedInterface xposed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        xposed = this;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        ClassLoader cl = classLoader(param);
        if (!TARGET_MIX.equals(param.getPackageName())) {
            tryHookUtilsIfPresent(cl);
            return;
        }
        mixClassLoader = cl;
        hookMainApp(cl);
        hookService(cl);
        tryHookUtilsIfPresent(cl);
        hookListUtils(cl);
        hookIslandWhitelist(cl);
    }

    private static ClassLoader classLoader(PackageReadyParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            if (cl != null) return cl;
        } catch (Throwable ignored) {
        }
        try {
            return param.getDefaultClassLoader();
        } catch (Throwable ignored) {
            return HookEntry.class.getClassLoader();
        }
    }

    private static void ensureInit(Context ctx, ClassLoader cl) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        appContextRef = new WeakReference<>(app);
        registerSessionListener(app, cl);
        syncDynamicPackages(app, cl);
    }

    private static void registerSessionListener(Context app, ClassLoader cl) {
        if (sessionListenerRegistered || app == null) return;
        synchronized (HookEntry.class) {
            if (sessionListenerRegistered) return;
            try {
                MediaSessionManager msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
                if (msm == null) return;
                Handler handler = new Handler(Looper.getMainLooper());
                MediaSessionManager.OnActiveSessionsChangedListener listener = controllers -> {
                    if (controllers == null) return;
                    ClassLoader targetCl = mixClassLoader != null ? mixClassLoader : cl;
                    for (MediaController mc : controllers) {
                        if (mc == null) continue;
                        String pkg = basePackage(mc.getPackageName());
                        if (!isSystemOrSelf(pkg)) {
                            DISCOVERED_PLAYERS.add(pkg);
                            injectPackage(targetCl, pkg);
                        }
                    }
                };
                try {
                    msm.addOnActiveSessionsChangedListener(listener, null, handler);
                } catch (SecurityException se) {
                    msm.addOnActiveSessionsChangedListener(listener,
                            new ComponentName(app, NotificationListenerService.class), handler);
                }
                sessionListenerRegistered = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookMainApp(ClassLoader cl) {
        try {
            Class<?> appCls = Class.forName(CLS_MAIN_APP, false, cl);
            synchronized (INSTALLED) {
                if (!INSTALLED.add(appCls)) return;
            }
            interceptMethods(appCls, "onCreate", chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Context) ensureInit((Context) self, cl);
                return res;
            });
            interceptMethods(appCls, "f0", chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Context) ensureInit((Context) self, cl);
                return res;
            });
        } catch (Throwable t) {
            hookError("hookMainApp", t);
        }
    }

    private static void hookService(ClassLoader cl) {
        try {
            Class<?> svcCls = Class.forName(CLS_SERVICE, false, cl);
            synchronized (INSTALLED) {
                if (!INSTALLED.add(svcCls)) return;
            }
            interceptMethods(svcCls, "onCreate", chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Context) ensureInit((Context) self, cl);
                return res;
            });
        } catch (Throwable ignored) {
        }
    }

    private static void tryHookUtilsIfPresent(ClassLoader cl) {
        if (cl == null) return;
        try {
            Class<?> dCls = Class.forName(CLS_UTILS_D, false, cl);
            synchronized (INSTALLED) {
                if (!INSTALLED.add(dCls)) return;
            }
            Hooker checkHooker = chain -> {
                Object res = chain.proceed();
                if (res instanceof Boolean && (Boolean) res) return res;
                List<Object> args = chain.getArgs();
                if (args.size() >= 2 && args.get(1) instanceof String) {
                    Context ctx = args.get(0) instanceof Context ? (Context) args.get(0) : appContextRef.get();
                    if (ctx != null) ensureInit(ctx, cl);
                    String basePkg = basePackage((String) args.get(1));
                    if (shouldAllowPackage(ctx, basePkg)) {
                        injectPackage(cl, basePkg);
                        return Boolean.TRUE;
                    }
                }
                return res;
            };
            for (String m : new String[]{"W", "X", "T", "d"}) {
                try {
                    interceptMethods(dCls, m, checkHooker);
                } catch (Throwable ignored) {
                }
            }

            Hooker listHooker = chain -> {
                Object res = chain.proceed();
                if (!(res instanceof List)) return res;
                Context ctx = null;
                List<Object> args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof Context) {
                    ctx = (Context) args.get(0);
                    ensureInit(ctx, cl);
                } else {
                    ctx = appContextRef.get();
                }
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) res;
                Set<String> extras = collectAutoDetectedPackages(ctx);
                for (String pkg : extras) {
                    if (!list.contains(pkg)) {
                        try {
                            list.add(pkg);
                        } catch (UnsupportedOperationException e) {
                            List<Object> copy = new ArrayList<>(list);
                            for (String p : extras) if (!copy.contains(p)) copy.add(p);
                            return copy;
                        }
                    }
                }
                return res;
            };
            for (String m : new String[]{"r", "q", "w", "v", "F", "G"}) {
                try {
                    interceptMethods(dCls, m, listHooker);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookListUtils(ClassLoader cl) {
        try {
            Class<?> listCls = Class.forName(CLS_LIST_UTILS, false, cl);
            synchronized (INSTALLED) {
                if (!INSTALLED.add(listCls)) return;
            }
            interceptMethods(listCls, "g", chain -> {
                Object res = chain.proceed();
                if (!(res instanceof List)) return res;
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) res;
                Context ctx = appContextRef.get();
                List<Object> filtered = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof String && shouldAllowPackage(ctx, basePackage((String) item))) {
                        continue;
                    }
                    filtered.add(item);
                }
                return filtered;
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookIslandWhitelist(ClassLoader cl) {
        try {
            Class<?> holderCls = Class.forName(CLS_WHITELIST_HOLDER, false, cl);
            boolean shouldHookHolder;
            synchronized (INSTALLED) {
                shouldHookHolder = INSTALLED.add(holderCls);
            }
            if (shouldHookHolder) {
                for (String methodName : new String[]{"b", "c", "d", "e"}) {
                    interceptMethods(holderCls, methodName, chain -> {
                        Object res = chain.proceed();
                        if (!(res instanceof List)) return res;
                        @SuppressWarnings("unchecked")
                        List<String> orig = (List<String>) res;
                        Set<String> extras = collectAutoDetectedPackages(appContextRef.get());
                        if (extras.isEmpty()) return res;
                        Set<String> merged = new LinkedHashSet<>();
                        synchronized (orig) {
                            merged.addAll(orig);
                        }
                        merged.addAll(extras);
                        return new ArrayList<>(merged);
                    });
                }
            }
        } catch (Throwable t) {
            hookError("hookIslandWhitelist holder", t);
        }

        try {
            Class<?> islandMgrCls = Class.forName(CLS_ISLAND_NOTIF_MGR, false, cl);
            boolean shouldHookMgr;
            synchronized (INSTALLED) {
                shouldHookMgr = INSTALLED.add(islandMgrCls);
            }
            if (shouldHookMgr) {
                interceptMethods(islandMgrCls, "B", chain -> {
                    Object arg0 = chain.getArg(0);
                    if (arg0 instanceof String) {
                        String pkg = basePackage((String) arg0);
                        Context ctx = appContextRef.get();
                        if (shouldAllowPackage(ctx, pkg)) {
                            injectPackage(cl, pkg);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            hookError("hookIslandWhitelist mgr", t);
        }
    }

    private static String basePackage(String processOrPkg) {
        if (processOrPkg == null) return "";
        int colon = processOrPkg.indexOf(':');
        return (colon > 0 ? processOrPkg.substring(0, colon) : processOrPkg).trim();
    }

    private static boolean isSystemOrSelf(String pkg) {
        return pkg == null || pkg.isEmpty()
                || "android".equals(pkg)
                || TARGET_MIX.equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.android.server.telecom".equals(pkg)
                || "com.android.incallui".equals(pkg)
                || "com.android.phone".equals(pkg)
                || "com.android.bluetooth".equals(pkg)
                || "com.android.dialer".equals(pkg)
                || "com.vivo.dialer".equals(pkg)
                || pkg.startsWith("com.android.providers.");
    }

    private static boolean shouldAllowPackage(Context ctx, String basePkg) {
        if (isSystemOrSelf(basePkg)) return false;
        if (DISCOVERED_PLAYERS.contains(basePkg)) return true;
        if (ctx == null) return false;
        if (getManifestPlayers(ctx).contains(basePkg)) return true;
        if (hasAudioManifestSignals(ctx, basePkg)) {
            DISCOVERED_PLAYERS.add(basePkg);
            return true;
        }
        if (hasActiveMediaSession(ctx, basePkg)) {
            DISCOVERED_PLAYERS.add(basePkg);
            return true;
        }
        return false;
    }

    private static boolean hasAudioManifestSignals(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) return false;
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai != null && ai.category == ApplicationInfo.CATEGORY_AUDIO) {
                return true;
            }
            for (String action : AUDIO_SERVICE_ACTIONS) {
                Intent svcIntent = new Intent(action).setPackage(pkg);
                List<ResolveInfo> services = pm.queryIntentServices(svcIntent, 0);
                if (services != null && !services.isEmpty()) return true;
            }
            Intent musicIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_APP_MUSIC)
                    .setPackage(pkg);
            List<ResolveInfo> activities = pm.queryIntentActivities(musicIntent, 0);
            return activities != null && !activities.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<MediaController> getActiveSessionsCompat(Context ctx, MediaSessionManager msm) {
        if (msm == null) return Collections.emptyList();
        try {
            List<MediaController> sessions = msm.getActiveSessions(null);
            if (sessions != null) return sessions;
        } catch (Throwable ignored) {
        }
        if (ctx != null) {
            try {
                List<MediaController> sessions = msm.getActiveSessions(
                        new ComponentName(ctx, NotificationListenerService.class));
                if (sessions != null) return sessions;
            } catch (Throwable ignored) {
            }
        }
        return Collections.emptyList();
    }

    private static boolean hasActiveMediaSession(Context ctx, String pkg) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : getActiveSessionsCompat(ctx, msm)) {
                if (mc != null && pkg.equals(basePackage(mc.getPackageName()))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Set<String> getManifestPlayers(Context ctx) {
        if (ctx == null) return Collections.emptySet();
        long now = System.currentTimeMillis();
        if (now - lastManifestScanMs < MANIFEST_CACHE_TTL_MS && !cachedManifestPlayers.isEmpty()) {
            return cachedManifestPlayers;
        }
        Set<String> scanned = new LinkedHashSet<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai == null || isSystemOrSelf(ai.packageName)) continue;
                    if (ai.category == ApplicationInfo.CATEGORY_AUDIO) {
                        scanned.add(ai.packageName);
                    }
                }
                Intent musicIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC);
                List<ResolveInfo> musicActivities = pm.queryIntentActivities(musicIntent, 0);
                if (musicActivities != null) {
                    for (ResolveInfo ri : musicActivities) {
                        if (ri != null && ri.activityInfo != null && !isSystemOrSelf(ri.activityInfo.packageName)) {
                            scanned.add(ri.activityInfo.packageName);
                        }
                    }
                }
                for (String action : AUDIO_SERVICE_ACTIONS) {
                    List<ResolveInfo> services = pm.queryIntentServices(new Intent(action), 0);
                    if (services != null) {
                        for (ResolveInfo ri : services) {
                            if (ri != null && ri.serviceInfo != null && !isSystemOrSelf(ri.serviceInfo.packageName)) {
                                scanned.add(ri.serviceInfo.packageName);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        cachedManifestPlayers = Collections.unmodifiableSet(scanned);
        lastManifestScanMs = now;
        return cachedManifestPlayers;
    }

    private static Set<String> collectAutoDetectedPackages(Context ctx) {
        Set<String> out = new LinkedHashSet<>(DISCOVERED_PLAYERS);
        if (ctx == null) return out;
        out.addAll(getManifestPlayers(ctx));
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : getActiveSessionsCompat(ctx, msm)) {
                if (mc == null) continue;
                String p = basePackage(mc.getPackageName());
                if (!isSystemOrSelf(p)) {
                    DISCOVERED_PLAYERS.add(p);
                    out.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void syncDynamicPackages(Context ctx, ClassLoader cl) {
        if (ctx == null || cl == null) return;
        for (String pkg : collectAutoDetectedPackages(ctx)) {
            injectPackage(cl, pkg);
        }
    }

    private static void injectPackage(ClassLoader cl, String pkg) {
        if (cl == null || pkg == null || pkg.isEmpty()) return;
        try {
            Class<?> appCls = Class.forName(CLS_MAIN_APP, false, cl);
            for (String fieldName : new String[]{"g0", "h0", "j0", "f0"}) {
                try {
                    Field f = appCls.getField(fieldName);
                    Object val = f.get(null);
                    if (val instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> list = (List<String>) val;
                        synchronized (list) {
                            if (!list.contains(pkg)) list.add(pkg);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                Field c0Field = appCls.getField("c0");
                Object c0Val = c0Field.get(null);
                if (c0Val instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Boolean> map = (java.util.Map<String, Boolean>) c0Val;
                    synchronized (map) {
                        map.put(pkg, Boolean.TRUE);
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> holderCls = Class.forName(CLS_WHITELIST_HOLDER, false, cl);
            Method getInstance = holderCls.getMethod("a");
            Object holder = getInstance.invoke(null);
            if (holder != null) {
                for (String methodName : new String[]{"c", "b", "d", "e"}) {
                    try {
                        Method m = holderCls.getMethod(methodName);
                        Object val = m.invoke(holder);
                        if (val instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> list = (List<String>) val;
                            synchronized (list) {
                                if (!list.contains(pkg)) list.add(pkg);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ libxposed API 102 hooks

    static HookHandle intercept(Executable target, Hooker hooker) {
        XposedInterface x = xposed;
        if (x == null) throw new IllegalStateException("Xposed module is not loaded");
        return x.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
    }

    static List<HookHandle> interceptMethods(Class<?> type, String name, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) targets.add(m);
        }
        return interceptAll(targets, hooker);
    }

    private static List<HookHandle> interceptAll(List<Executable> targets, Hooker hooker) {
        List<HookHandle> out = new ArrayList<>(targets.size());
        Throwable failure = null;
        for (Executable e : targets) {
            try {
                out.add(intercept(e, hooker));
            } catch (Throwable t) {
                failure = t;
            }
        }
        if (out.isEmpty() && failure != null) {
            if (failure instanceof Error) throw (Error) failure;
            throw failure instanceof RuntimeException ? (RuntimeException) failure : new RuntimeException(failure);
        }
        return out;
    }

    private static void hookError(String what, Throwable t) {
        XposedInterface x = xposed;
        if (x != null) x.log(Log.ERROR, HOOK_TAG, what, t);
        else Log.e(HOOK_TAG, what, t);
    }
}
